/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.model.BuildTask;
import com.scalar.db.analytics.labs.semantic.model.LogPosition;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.LogRecord.FactEvent;
import com.scalar.db.analytics.labs.semantic.model.LogRecordJson;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.ReviewPresentation;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.InputKind;
import com.scalar.db.analytics.labs.semantic.projection.IdentityLookup;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.AddressSelectionRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.DependencyRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.EdgeIdentity;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.ElementRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.FactAddressRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.FactRecordRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.FactRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.InputResourceRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.PresentablePeriodRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.Treatment;
import com.scalar.db.analytics.labs.semantic.projection.PositionedRecord;
import com.scalar.db.analytics.labs.semantic.projection.ProjectionSource;
import com.scalar.db.analytics.labs.semantic.projection.SelectionRule;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The statements one transaction runs on the {@code semantic_catalog} schema. Every SQL statement
 * of the Semantic Catalog is in this class, so the tables stay behind the storage adapter.
 *
 * <p>An instance is valid only inside the transaction {@link JdbcStore} passes it to.
 */
public final class StoreTransaction {
  private final JdbcClient jdbc;

  StoreTransaction(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // Domains and commits

  /** Whether a domain exists in this transaction's snapshot. */
  public boolean domainExists(String domain) {
    return jdbc.sql("SELECT count(*) FROM semantic_catalog.domains WHERE domain = ?")
            .param(domain)
            .query(Long.class)
            .single()
        > 0;
  }

  /**
   * Inserts a domain.
   *
   * @throws org.springframework.dao.DuplicateKeyException when the domain exists, including one a
   *     concurrent transaction created
   */
  public void insertDomain(String domain) {
    jdbc.sql("INSERT INTO semantic_catalog.domains (domain, created_at) VALUES (?, now())")
        .param(domain)
        .update();
  }

  /** The last commit of a domain visible to this transaction, or 0 before the first. */
  public long lastCommit(String domain) {
    return jdbc.sql(
            "SELECT coalesce(max(commit_number), 0) FROM semantic_catalog.commits WHERE domain = ?")
        .param(domain)
        .query(Long.class)
        .single();
  }

  /**
   * Inserts a commit numbered one past the last commit in this transaction's snapshot.
   *
   * @throws CommitNumberTaken when a concurrent writer committed the same number first
   */
  public void insertCommit(String domain, long commit, String operation, String author) {
    try {
      jdbc.sql(
              "INSERT INTO semantic_catalog.commits"
                  + " (domain, commit_number, committed_at, operation, author)"
                  + " VALUES (?, ?, now(), ?, ?)")
          .params(domain, commit, operation, author)
          .update();
    } catch (DuplicateKeyException e) {
      throw new CommitNumberTaken(domain, commit, e);
    }
  }

  /**
   * Removes a commit this transaction inserted and wrote nothing under, so a write that turns out
   * to have nothing to record leaves no commit.
   */
  public void deleteCommit(String domain, long commit) {
    jdbc.sql("DELETE FROM semantic_catalog.commits WHERE domain = ? AND commit_number = ?")
        .params(domain, commit)
        .update();
  }

  /** A concurrent writer took the commit number, so the write starts again on top of it. */
  public static final class CommitNumberTaken extends RuntimeException {
    private static final long serialVersionUID = 1L;

    CommitNumberTaken(String domain, long commit, Throwable cause) {
      super("Commit " + commit + " of domain " + domain + " was written concurrently", cause);
    }
  }

  /**
   * Appends a log record of the commit inserted in this transaction.
   *
   * @param targetFact the Fact the record creates or concerns, indexed to fold a Fact's records
   * @param targetInputResource the Input Resource the record creates or observes, indexed to walk
   *     the observations of an Input Resource
   * @param targetFactAddress the Fact Address a conflict resolution targets, indexed to find a Fact
   *     Address's resolutions
   */
  public void insertLogRecord(
      String domain,
      PositionedRecord record,
      @Nullable String targetFact,
      @Nullable String targetInputResource,
      @Nullable String targetFactAddress) {
    jdbc.sql(
            "INSERT INTO semantic_catalog.log_records"
                + " (domain, commit_number, ordinal, kind, target_fact, target_input_resource,"
                + " target_fact_address, content)"
                + " VALUES (:domain, :commit, :ordinal, :kind, :fact, :resource, :address,"
                + " CAST(:content AS jsonb))")
        .param("domain", domain)
        .param("commit", record.position().commit())
        .param("ordinal", record.position().ordinal())
        .param("kind", record.record().kind())
        .param("fact", targetFact, Types.VARCHAR)
        .param("resource", targetInputResource, Types.VARCHAR)
        .param("address", targetFactAddress, Types.VARCHAR)
        .param("content", LogRecordJson.write(record.record()))
        .update();
  }

  // Materialized rows

  /**
   * Writes a materialized row a commit inserted or changed. Rows of records that never change after
   * creation are inserted, so a row a concurrent writer created first violates its unique
   * constraint. Rows that a later commit can change, a current Fact Record, a presentable period
   * the retirement closes, or a dependency whose flags change, are inserted or updated by their
   * primary key.
   */
  public void writeRow(String domain, MaterializedRow row) {
    switch (row) {
      case FactRecordRow record ->
          jdbc.sql(
                  "INSERT INTO semantic_catalog.fact_records (domain, fact, fact_address,"
                      + " latest_event_commit, latest_event_ordinal, state, awaiting_consent,"
                      + " open_retirement_proposal, unknown_answer_pending, record)"
                      + " VALUES (:domain, :fact, :address, :commit, :ordinal, :state, :awaiting,"
                      + " :proposal, :unknown, CAST(:record AS jsonb))"
                      + " ON CONFLICT (domain, fact) DO UPDATE SET"
                      + " latest_event_commit = EXCLUDED.latest_event_commit,"
                      + " latest_event_ordinal = EXCLUDED.latest_event_ordinal,"
                      + " state = EXCLUDED.state,"
                      + " awaiting_consent = EXCLUDED.awaiting_consent,"
                      + " open_retirement_proposal = EXCLUDED.open_retirement_proposal,"
                      + " unknown_answer_pending = EXCLUDED.unknown_answer_pending,"
                      + " record = EXCLUDED.record")
              .param("domain", domain)
              .param("fact", record.fact())
              .param("address", record.factAddress())
              .param("commit", record.latestEvent().commit())
              .param("ordinal", record.latestEvent().ordinal())
              .param("state", record.treatment().state().name())
              .param("awaiting", record.treatment().awaitingConsent())
              .param("proposal", record.treatment().openRetirementProposal(), Types.VARCHAR)
              .param("unknown", record.treatment().unknownAnswerPending())
              .param("record", LogRecordJson.writeNode(record.record()))
              .update();
      case AddressSelectionRow selection ->
          jdbc.sql(
                  "INSERT INTO semantic_catalog.address_selections"
                      + " (domain, fact_address, from_commit, to_commit, selected_fact,"
                      + " eligible_facts)"
                      + " VALUES (:domain, :address, :from, :to, :selected,"
                      + " CAST(:eligible AS jsonb))"
                      + " ON CONFLICT (domain, fact_address, from_commit) DO UPDATE SET"
                      + " to_commit = EXCLUDED.to_commit")
              .param("domain", domain)
              .param("address", selection.factAddress())
              .param("from", selection.fromCommit())
              .param("to", selection.toCommit(), Types.BIGINT)
              .param("selected", selection.selectedFact(), Types.VARCHAR)
              .param("eligible", LogRecordJson.writeStrings(selection.eligibleFacts()))
              .update();
      case PresentablePeriodRow period ->
          jdbc.sql(
                  "INSERT INTO semantic_catalog.presentable_periods"
                      + " (domain, fact, confirmed_commit, retired_commit)"
                      + " VALUES (:domain, :fact, :confirmed, :retired)"
                      + " ON CONFLICT (domain, fact, confirmed_commit) DO UPDATE SET"
                      + " retired_commit = EXCLUDED.retired_commit")
              .param("domain", domain)
              .param("fact", period.fact())
              .param("confirmed", period.confirmedCommit())
              .param("retired", period.retiredCommit(), Types.BIGINT)
              .update();
      case DependencyRow dependency ->
          jdbc.sql(
                  "INSERT INTO semantic_catalog.dependencies (domain, dependent_fact,"
                      + " depended_record, depended_fact, depended_input_resource, in_effect,"
                      + " for_confirmation)"
                      + " VALUES (:domain, :dependent, :record, :fact, :resource, :effect,"
                      + " :confirmation)"
                      + " ON CONFLICT (domain, dependent_fact, depended_record) DO UPDATE SET"
                      + " in_effect = EXCLUDED.in_effect,"
                      + " for_confirmation = EXCLUDED.for_confirmation")
              .param("domain", domain)
              .param("dependent", dependency.dependentFact())
              .param("record", dependency.dependedRecord())
              .param("fact", dependency.dependedFact(), Types.VARCHAR)
              .param("resource", dependency.dependedInputResource(), Types.VARCHAR)
              .param("effect", dependency.inEffect())
              .param("confirmation", dependency.forConfirmation())
              .update();
      case ElementRow element -> {
        var edge = element.edge();
        jdbc.sql(
                "INSERT INTO semantic_catalog.elements (domain, element, kind, parent,"
                    + " technical_reference, created_commit, edge_end_a, edge_end_b,"
                    + " edge_directed, edge_identity_key, edge_identifying_part)"
                    + " VALUES (:domain, :element, :kind, :parent, :reference, :created, :a, :b,"
                    + " :directed, :key, CAST(:part AS jsonb))")
            .param("domain", domain)
            .param("element", element.element())
            .param("kind", element.kind().name())
            .param("parent", element.parent(), Types.VARCHAR)
            .param("reference", element.technicalReference(), Types.VARCHAR)
            .param("created", element.createdCommit())
            .param("a", edge == null ? null : edge.endA(), Types.VARCHAR)
            .param("b", edge == null ? null : edge.endB(), Types.VARCHAR)
            .param("directed", edge == null ? null : edge.directed(), Types.BOOLEAN)
            .param("key", edge == null ? null : edge.identityKey(), Types.VARCHAR)
            .param(
                "part",
                edge == null ? null : LogRecordJson.writeNode(edge.identifyingPart()),
                Types.VARCHAR)
            .update();
      }
      case FactAddressRow address ->
          jdbc.sql(
                  "INSERT INTO semantic_catalog.fact_addresses (domain, fact_address, element,"
                      + " predicate, scope_description, scope_entities, predicate_definition,"
                      + " created_commit)"
                      + " VALUES (:domain, :address, :element, :predicate, :description,"
                      + " CAST(:entities AS jsonb), :definition, :created)")
              .param("domain", domain)
              .param("address", address.factAddress())
              .param("element", address.element())
              .param("predicate", address.predicate())
              .param("description", address.scopeDescription(), Types.VARCHAR)
              .param("entities", LogRecordJson.writeStrings(address.scopeEntities()))
              .param("definition", address.predicateDefinition(), Types.VARCHAR)
              .param("created", address.createdCommit())
              .update();
      case FactRow fact ->
          jdbc.sql(
                  "INSERT INTO semantic_catalog.facts"
                      + " (domain, fact, fact_address, identity_key, created_commit)"
                      + " VALUES (:domain, :fact, :address, :value, :created)")
              .param("domain", domain)
              .param("fact", fact.fact())
              .param("address", fact.factAddress())
              .param("value", fact.identityKey(), Types.VARCHAR)
              .param("created", fact.createdCommit())
              .update();
      case InputResourceRow resource ->
          jdbc.sql(
                  "INSERT INTO semantic_catalog.input_resources"
                      + " (domain, input_resource, kind, table_identifier, created_commit)"
                      + " VALUES (:domain, :resource, :kind, :table, :created)")
              .param("domain", domain)
              .param("resource", resource.inputResource())
              .param("kind", resource.kind().name())
              .param("table", resource.tableIdentifier(), Types.VARCHAR)
              .param("created", resource.createdCommit())
              .update();
    }
  }

  /**
   * Deletes a materialized row the applied records no longer derive. Only a dependency row
   * disappears, when an acceptance drops the reference and no confirmation keeps it.
   */
  public void deleteRow(String domain, MaterializedRow row) {
    if (!(row instanceof DependencyRow dependency)) {
      throw new IllegalStateException("Only dependency rows are deleted, not " + row.key());
    }
    jdbc.sql(
            "DELETE FROM semantic_catalog.dependencies"
                + " WHERE domain = ? AND dependent_fact = ? AND depended_record = ?")
        .params(domain, dependency.dependentFact(), dependency.dependedRecord())
        .update();
  }

  /** Every materialized row of a domain, for the replay check. */
  public List<MaterializedRow> rows(String domain) {
    var rows = new ArrayList<MaterializedRow>();
    rows.addAll(jdbc.sql(ELEMENTS + " WHERE domain = ?").param(domain).query(this::element).list());
    rows.addAll(
        jdbc.sql(ADDRESSES + " WHERE domain = ?").param(domain).query(this::address).list());
    rows.addAll(jdbc.sql(FACTS + " WHERE domain = ?").param(domain).query(this::fact).list());
    rows.addAll(
        jdbc.sql(RESOURCES + " WHERE domain = ?").param(domain).query(this::resource).list());
    rows.addAll(jdbc.sql(RECORDS + " WHERE domain = ?").param(domain).query(this::record).list());
    rows.addAll(jdbc.sql(PERIODS + " WHERE domain = ?").param(domain).query(this::period).list());
    rows.addAll(
        jdbc.sql(SELECTIONS + " WHERE domain = ?").param(domain).query(this::selection).list());
    rows.addAll(
        jdbc.sql(DEPENDENCIES + " WHERE domain = ?").param(domain).query(this::dependency).list());
    return rows;
  }

  // The log

  /** Every log record of a domain, in log order. */
  public List<PositionedRecord> logRecords(String domain) {
    return jdbc.sql(LOG_RECORDS + " WHERE domain = ? ORDER BY commit_number, ordinal")
        .param(domain)
        .query(this::positioned)
        .list();
  }

  // Reads at a commit

  /** The current Fact Record of a Fact, or empty when it does not exist or is not accepted. */
  public Optional<FactRecordRow> factRecord(String domain, String fact) {
    return jdbc.sql(RECORDS + " WHERE domain = ? AND fact = ?")
        .params(domain, fact)
        .query(this::record)
        .optional();
  }

  /**
   * The log records of one Fact up to a commit, in log order: the record creating it, then its Fact
   * Events.
   */
  public List<PositionedRecord> factRecords(String domain, String fact, long upToCommit) {
    return jdbc.sql(
            LOG_RECORDS
                + " WHERE domain = ? AND target_fact = ? AND commit_number <= ?"
                + " ORDER BY commit_number, ordinal")
        .params(domain, fact, upToCommit)
        .query(this::positioned)
        .list();
  }

  // Table metadata received, partly kept outside the log

  /**
   * A table's physical-schema Input Resource.
   *
   * @param inputResource its URN
   * @param latestVersion the latest table metadata version received for the table, or empty when
   *     none was received through table metadata registration
   */
  public record TableResource(String inputResource, OptionalLong latestVersion) {}

  /**
   * The physical-schema Input Resource of a table, locked for the rest of the transaction so that
   * registrations for one table run one at a time; a concurrent registration that already changed
   * it fails this transaction, which is then retried.
   */
  public Optional<TableResource> lockTableResource(String domain, String tableIdentifier) {
    return jdbc.sql(
            "SELECT input_resource, latest_table_metadata_version"
                + " FROM semantic_catalog.input_resources"
                + " WHERE domain = ? AND table_identifier = ? FOR UPDATE")
        .params(domain, tableIdentifier)
        .query(
            (rs, n) -> {
              var version = rs.getLong("latest_table_metadata_version");
              return new TableResource(
                  rs.getString("input_resource"),
                  rs.wasNull() ? OptionalLong.empty() : OptionalLong.of(version));
            })
        .optional();
  }

  /** The latest Input Observation of an Input Resource, with its URN. */
  public Optional<PositionedRecord> latestObservationRecord(String domain, String inputResource) {
    return jdbc.sql(
            LOG_RECORDS
                + " WHERE domain = ? AND target_input_resource = ?"
                + " AND kind = 'input-observation'"
                + " ORDER BY commit_number DESC, ordinal DESC LIMIT 1")
        .params(domain, inputResource)
        .query(this::positioned)
        .optional();
  }

  /** The log record at a log position. */
  public Optional<PositionedRecord> logRecordAt(String domain, LogPosition position) {
    return jdbc.sql(LOG_RECORDS + " WHERE domain = ? AND commit_number = ? AND ordinal = ?")
        .params(domain, position.commit(), position.ordinal())
        .query(this::positioned)
        .optional();
  }

  /** Records the latest table metadata version received for a physical-schema Input Resource. */
  public void setLatestTableMetadataVersion(String domain, String inputResource, long version) {
    jdbc.sql(
            "UPDATE semantic_catalog.input_resources SET latest_table_metadata_version = ?"
                + " WHERE domain = ? AND input_resource = ?")
        .params(version, domain, inputResource)
        .update();
  }

  /** Writes the derived record of one table metadata version. */
  public void insertDerivedRecord(
      String domain, String tableIdentifier, long version, JsonNode content) {
    jdbc.sql(
            "INSERT INTO semantic_catalog.derived_records"
                + " (domain, table_identifier, table_metadata_version, content)"
                + " VALUES (:domain, :table, :version, CAST(:content AS jsonb))")
        .param("domain", domain)
        .param("table", tableIdentifier)
        .param("version", version)
        .param("content", LogRecordJson.writeNode(content))
        .update();
  }

  /** The derived record of one table metadata version. */
  public Optional<JsonNode> derivedRecord(String domain, String tableIdentifier, long version) {
    return jdbc.sql(
            "SELECT content::text FROM semantic_catalog.derived_records"
                + " WHERE domain = ? AND table_identifier = ? AND table_metadata_version = ?")
        .params(domain, tableIdentifier, version)
        .query((rs, n) -> LogRecordJson.readNode(rs.getString(1)))
        .optional();
  }

  /**
   * The tables whose latest version received differs from the highest version among their derived
   * records, each described for the replay check.
   */
  public List<String> derivedVersionMismatches(String domain) {
    return jdbc.sql(
            "SELECT coalesce(r.table_identifier, d.table_identifier) AS table_identifier,"
                + " r.latest_table_metadata_version AS latest, d.highest"
                + " FROM (SELECT table_identifier, latest_table_metadata_version"
                + " FROM semantic_catalog.input_resources"
                + " WHERE domain = :domain AND table_identifier IS NOT NULL) r"
                + " FULL JOIN (SELECT table_identifier, max(table_metadata_version) AS highest"
                + " FROM semantic_catalog.derived_records WHERE domain = :domain"
                + " GROUP BY table_identifier) d"
                + " ON r.table_identifier = d.table_identifier"
                + " WHERE r.latest_table_metadata_version IS DISTINCT FROM d.highest")
        .param("domain", domain)
        .query(
            (rs, n) ->
                "table "
                    + rs.getString("table_identifier")
                    + ": latest version received "
                    + rs.getString("latest")
                    + ", highest derived record "
                    + rs.getString("highest"))
        .list();
  }

  // Review presentations, kept outside the log

  /**
   * Records a review presentation under the number one past the domain's last one in this snapshot.
   * Overlapping registrations collide on the primary key and retry.
   *
   * @return the number of the presentation
   */
  public long insertPresentation(String domain, ReviewPresentation presentation) {
    var number =
        jdbc.sql(
                    "SELECT coalesce(max(presentation), 0) FROM semantic_catalog.review_presentations"
                        + " WHERE domain = ?")
                .params(domain)
                .query(Long.class)
                .single()
            + 1;
    try {
      jdbc.sql(
              "INSERT INTO semantic_catalog.review_presentations (domain, presentation, content)"
                  + " VALUES (?, ?, CAST(? AS jsonb))")
          .params(domain, number, LogRecordJson.writeNode(LogRecordJson.toStored(presentation)))
          .update();
    } catch (DuplicateKeyException e) {
      throw new CommitNumberTaken(domain, number, e);
    }
    for (var fact :
        presentation.targets().stream().map(ReviewPresentation.Target::fact).distinct().toList()) {
      jdbc.sql(
              "INSERT INTO semantic_catalog.review_presentation_targets"
                  + " (domain, presentation, fact) VALUES (?, ?, ?)")
          .params(domain, number, fact)
          .update();
    }
    return number;
  }

  /** A review presentation by number. */
  public Optional<ReviewPresentation> presentation(String domain, long presentation) {
    return jdbc.sql(
            "SELECT content::text FROM semantic_catalog.review_presentations"
                + " WHERE domain = ? AND presentation = ?")
        .params(domain, presentation)
        .query(String.class)
        .optional()
        .map(
            json ->
                LogRecordJson.fromStored(LogRecordJson.readNode(json), ReviewPresentation.class));
  }

  /** Adds an event an answer or a conflict resolution recorded to its review presentation. */
  public void addPresentationEvent(String domain, long presentation, String logRecord) {
    jdbc.sql(
            "UPDATE semantic_catalog.review_presentations"
                + " SET content = jsonb_set(content, '{resultingEvents}',"
                + " (content -> 'resultingEvents') || to_jsonb(CAST(? AS text)))"
                + " WHERE domain = ? AND presentation = ?")
        .params(logRecord, domain, presentation)
        .update();
  }

  /** The numbers of the review presentations of each of some Facts, oldest first. */
  public Map<String, List<Long>> presentationsOf(String domain, Collection<String> facts) {
    var presentations = new HashMap<String, List<Long>>();
    if (facts.isEmpty()) {
      return presentations;
    }
    jdbc.sql(
            "SELECT fact, presentation FROM semantic_catalog.review_presentation_targets"
                + " WHERE domain = :domain AND fact = ANY(CAST(:facts AS text[]))"
                + " ORDER BY presentation")
        .param("domain", domain)
        .param("facts", array(facts))
        .query(
            (rs, row) ->
                presentations
                    .computeIfAbsent(rs.getString("fact"), key -> new ArrayList<>())
                    .add(rs.getLong("presentation")))
        .list();
    return presentations;
  }

  // Build tasks, kept outside the log

  /**
   * Records a build task under the number one past the domain's last one in this snapshot.
   * Overlapping creations collide on the primary key and retry.
   *
   * @return the task as recorded
   */
  public BuildTask insertBuildTask(
      String domain, BuildTask.Work work, List<String> triggers, BuildTask.Status status) {
    var number =
        jdbc.sql("SELECT coalesce(max(task), 0) FROM semantic_catalog.build_tasks WHERE domain = ?")
                .params(domain)
                .query(Long.class)
                .single()
            + 1;
    var task = new BuildTask(number, work, triggers, status);
    try {
      jdbc.sql(
              "INSERT INTO semantic_catalog.build_tasks"
                  + " (domain, task, kind, state, target, content)"
                  + " VALUES (:domain, :task, :kind, :state, :target, CAST(:content AS jsonb))")
          .param("domain", domain)
          .param("task", number)
          .param("kind", work.kind().value())
          .param("state", status.state().value())
          .param("target", work.target(), Types.VARCHAR)
          .param("content", LogRecordJson.writeNode(LogRecordJson.toStored(task)))
          .update();
    } catch (DuplicateKeyException e) {
      throw new CommitNumberTaken(domain, number, e);
    }
    return task;
  }

  /**
   * Replaces a build task's triggers and status. Overlapping updates of one task collide on its
   * row, and the later one retries.
   *
   * @param expected the state the task must still be in
   * @return whether the task was still in that state and was updated
   */
  public boolean updateBuildTask(String domain, BuildTask task, BuildTask.State expected) {
    return jdbc.sql(
                "UPDATE semantic_catalog.build_tasks"
                    + " SET state = :state, content = CAST(:content AS jsonb)"
                    + " WHERE domain = :domain AND task = :task AND state = :expected")
            .param("domain", domain)
            .param("task", task.number())
            .param("state", task.status().state().value())
            .param("content", LogRecordJson.writeNode(LogRecordJson.toStored(task)))
            .param("expected", expected.value())
            .update()
        > 0;
  }

  /** A build task by number. */
  public Optional<BuildTask> buildTask(String domain, long task) {
    return jdbc.sql(
            "SELECT content::text FROM semantic_catalog.build_tasks WHERE domain = ? AND task = ?")
        .params(domain, task)
        .query(String.class)
        .optional()
        .map(StoreTransaction::buildTaskOf);
  }

  /** The created build task of a kind for a target, if any. */
  public Optional<BuildTask> createdBuildTask(
      String domain, BuildTask.Kind kind, @Nullable String target) {
    return jdbc.sql(
            "SELECT content::text FROM semantic_catalog.build_tasks"
                + " WHERE domain = :domain AND state = 'created' AND kind = :kind"
                + " AND target IS NOT DISTINCT FROM :target ORDER BY task LIMIT 1")
        .param("domain", domain)
        .param("kind", kind.value())
        .param("target", target, Types.VARCHAR)
        .query(String.class)
        .optional()
        .map(StoreTransaction::buildTaskOf);
  }

  /** The oldest build task of a domain in a state, if any. */
  public Optional<BuildTask> firstBuildTaskIn(String domain, BuildTask.State state) {
    return jdbc.sql(
            "SELECT content::text FROM semantic_catalog.build_tasks"
                + " WHERE domain = ? AND state = ? ORDER BY task LIMIT 1")
        .params(domain, state.value())
        .query((rs, row) -> buildTaskOf(rs.getString(1)))
        .optional();
  }

  /** The build tasks of a domain in a state, oldest first. */
  public List<BuildTask> buildTasksIn(String domain, BuildTask.State state) {
    return jdbc.sql(
            "SELECT content::text FROM semantic_catalog.build_tasks"
                + " WHERE domain = ? AND state = ? ORDER BY task")
        .params(domain, state.value())
        .query((rs, row) -> buildTaskOf(rs.getString(1)))
        .list();
  }

  /** Every build task of a domain, oldest first. */
  public List<BuildTask> buildTasks(String domain) {
    return jdbc.sql(
            "SELECT content::text FROM semantic_catalog.build_tasks WHERE domain = ? ORDER BY task")
        .params(domain)
        .query((rs, row) -> buildTaskOf(rs.getString(1)))
        .list();
  }

  private static BuildTask buildTaskOf(String json) {
    return LogRecordJson.fromStored(LogRecordJson.readNode(json), BuildTask.class);
  }

  // Reads for review

  /** The URN of the user policy version in force at a commit: the latest one written by then. */
  public Optional<String> policyVersionAt(String domain, long commit) {
    return jdbc.sql(
            "SELECT commit_number, ordinal FROM semantic_catalog.log_records"
                + " WHERE domain = ? AND kind = 'policy-version' AND commit_number <= ?"
                + " ORDER BY commit_number DESC, ordinal DESC LIMIT 1")
        .params(domain, commit)
        .query(
            (rs, row) ->
                Urns.logRecord(
                    domain, new LogPosition(rs.getLong("commit_number"), rs.getInt("ordinal"))))
        .optional();
  }

  /**
   * The Facts with a policy evaluation that requested consent at or before a commit: every Fact
   * that may await consent at that commit.
   */
  public List<String> factsAskedForConsent(String domain, long commit) {
    return jdbc.sql(
            "SELECT DISTINCT target_fact FROM semantic_catalog.log_records"
                + " WHERE domain = ? AND kind = 'evaluate-policy' AND commit_number <= ?"
                + " AND content ->> 'result' = 'REQUEST_CONSENT' ORDER BY target_fact")
        .params(domain, commit)
        .query(StoreTransaction::firstString)
        .list();
  }

  /** The Fact Addresses in conflict at a commit, with their selections. */
  public List<AddressSelectionRow> conflictsAt(String domain, long commit) {
    return jdbc.sql(
            SELECTIONS
                + " WHERE domain = ? AND from_commit <= ? AND (to_commit IS NULL OR to_commit > ?)"
                + " AND jsonb_array_length(eligible_facts) > 1 ORDER BY fact_address")
        .params(domain, commit, commit)
        .query(this::selection)
        .list();
  }

  /** The current Fact Records of every candidate. */
  public List<FactRecordRow> candidateRecords(String domain) {
    return jdbc.sql(RECORDS + " WHERE domain = ? AND state = 'CANDIDATE' ORDER BY fact")
        .params(domain)
        .query(this::record)
        .list();
  }

  /** The current Fact Records of the confirmed Facts with an open retirement proposal. */
  public List<FactRecordRow> openRetirementRecords(String domain) {
    return jdbc.sql(
            RECORDS
                + " WHERE domain = ? AND state = 'CONFIRMED'"
                + " AND open_retirement_proposal IS NOT NULL ORDER BY fact")
        .params(domain)
        .query(this::record)
        .list();
  }

  /** The Fact Addresses among some, as they are at a commit. */
  public List<FactAddressRow> addressesAt(
      String domain, Collection<String> factAddresses, long commit) {
    if (factAddresses.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            ADDRESSES
                + " WHERE domain = :domain AND fact_address = ANY(CAST(:addresses AS text[]))"
                + " AND created_commit <= :commit")
        .param("domain", domain)
        .param("addresses", array(factAddresses))
        .param("commit", commit)
        .query(this::address)
        .list();
  }

  // Reads of many records at a commit, for the Catalog Snapshot. Each runs one query for the whole
  // set it is given.

  /** The elements among some that exist at a commit. */
  public List<ElementRow> elementsAt(String domain, Collection<String> elements, long commit) {
    if (elements.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            ELEMENTS
                + " WHERE domain = :domain AND element = ANY(CAST(:elements AS text[])) AND created_commit <= :commit")
        .param("domain", domain)
        .param("elements", array(elements))
        .param("commit", commit)
        .query(this::element)
        .list();
  }

  /**
   * The edges of a kind between two ends, for several such questions at once.
   *
   * @param kind the edge kind of the question
   * @param from the canonical form of the end the edge starts from; for an undirected edge, either
   *     end
   * @param to the canonical form of the other end
   */
  public record EdgeQuestion(ElementKind kind, String from, String to) {}

  /**
   * The edges that exist at a commit for each question, whatever their identifying part: a directed
   * edge from one end to the other, or an undirected edge between them. One query answers all.
   *
   * @return the edges per question, by the question's index
   */
  public Map<Integer, List<ElementRow>> edgesBetween(
      String domain, List<EdgeQuestion> questions, long commit) {
    var answers = new HashMap<Integer, List<ElementRow>>();
    if (questions.isEmpty()) {
      return answers;
    }
    var kinds = new String[questions.size()];
    var froms = new String[questions.size()];
    var tos = new String[questions.size()];
    var lows = new String[questions.size()];
    var highs = new String[questions.size()];
    for (var i = 0; i < questions.size(); i++) {
      var question = questions.get(i);
      var sorted = question.from().compareTo(question.to()) <= 0;
      kinds[i] = question.kind().name();
      froms[i] = question.from();
      tos[i] = question.to();
      lows[i] = sorted ? question.from() : question.to();
      highs[i] = sorted ? question.to() : question.from();
    }
    jdbc.sql(
            "SELECT q.i - 1 AS question, e.element, e.kind, e.parent, e.technical_reference,"
                + " e.created_commit,"
                + " e.edge_end_a, e.edge_end_b, e.edge_directed, e.edge_identity_key,"
                + " e.edge_identifying_part::text AS edge_identifying_part"
                + " FROM unnest(CAST(:kinds AS text[]), CAST(:froms AS text[]),"
                + " CAST(:tos AS text[]), CAST(:lows AS text[]), CAST(:highs AS text[]))"
                + " WITH ORDINALITY AS q(kind, from_end, to_end, low_end, high_end, i)"
                + " JOIN semantic_catalog.elements e"
                + " ON e.domain = :domain AND e.kind = q.kind AND e.created_commit <= :commit"
                + " AND ((e.edge_directed AND e.edge_end_a = q.from_end"
                + " AND e.edge_end_b = q.to_end)"
                + " OR (NOT e.edge_directed AND e.edge_end_a = q.low_end"
                + " AND e.edge_end_b = q.high_end))"
                + " ORDER BY q.i, e.element")
        .param("domain", domain)
        .param("commit", commit)
        .param("kinds", kinds)
        .param("froms", froms)
        .param("tos", tos)
        .param("lows", lows)
        .param("highs", highs)
        .query(
            (rs, n) -> {
              answers
                  .computeIfAbsent(rs.getInt("question"), key -> new ArrayList<>())
                  .add(element(rs, n));
              return n;
            })
        .list();
    return answers;
  }

  /**
   * The elements whose existence Fact is selected at a commit, optionally only those of a kind or
   * of a parent.
   */
  public List<ElementRow> presentedElements(
      String domain, @Nullable ElementKind kind, @Nullable String parent, long commit) {
    return jdbc.sql(
            "SELECT e.element, e.kind, e.parent, e.technical_reference, e.created_commit,"
                + " e.edge_end_a, e.edge_end_b,"
                + " e.edge_directed, e.edge_identity_key,"
                + " e.edge_identifying_part::text AS edge_identifying_part"
                + " FROM semantic_catalog.elements e"
                + " JOIN semantic_catalog.fact_addresses a"
                + " ON a.domain = e.domain AND a.element = e.element AND a.predicate = :existence"
                + " AND a.scope_description IS NULL AND a.predicate_definition IS NULL"
                + " JOIN semantic_catalog.address_selections s"
                + " ON s.domain = a.domain AND s.fact_address = a.fact_address"
                + " AND s.from_commit <= :commit"
                + " AND (s.to_commit IS NULL OR s.to_commit > :commit)"
                + " AND s.selected_fact IS NOT NULL"
                + " WHERE e.domain = :domain"
                + " AND (CAST(:kind AS text) IS NULL OR e.kind = :kind)"
                + " AND (CAST(:parent AS text) IS NULL OR e.parent = :parent)"
                + " ORDER BY e.element")
        .param("domain", domain)
        .param("existence", Vocabulary.EXISTENCE)
        .param("commit", commit)
        .param("kind", kind == null ? null : kind.name(), Types.VARCHAR)
        .param("parent", parent, Types.VARCHAR)
        .query(this::element)
        .list();
  }

  /**
   * The Fact Addresses of some elements that exist at a commit, except those of free-description
   * predicates, which cannot be requested by name.
   */
  public List<FactAddressRow> namedAddressesOf(
      String domain, Collection<String> elements, long commit) {
    if (elements.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            ADDRESSES
                + " WHERE domain = :domain AND element = ANY(CAST(:elements AS text[]))"
                + " AND created_commit <= :commit AND predicate_definition IS NULL"
                + " ORDER BY fact_address")
        .param("domain", domain)
        .param("elements", array(elements))
        .param("commit", commit)
        .query(this::address)
        .list();
  }

  /** The selections that hold at a commit at some Fact Addresses. */
  public List<AddressSelectionRow> selectionsAt(
      String domain, Collection<String> factAddresses, long commit) {
    if (factAddresses.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            SELECTIONS
                + " WHERE domain = :domain AND fact_address = ANY(CAST(:addresses AS text[]))"
                + " AND from_commit <= :commit AND (to_commit IS NULL OR to_commit > :commit)")
        .param("domain", domain)
        .param("addresses", array(factAddresses))
        .param("commit", commit)
        .query(this::selection)
        .list();
  }

  /** The current Fact Records of the Facts at some Fact Addresses. */
  public List<FactRecordRow> factRecordsAt(String domain, Collection<String> factAddresses) {
    if (factAddresses.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            RECORDS
                + " WHERE domain = :domain AND fact_address = ANY(CAST(:addresses AS text[])) ORDER BY fact")
        .param("domain", domain)
        .param("addresses", array(factAddresses))
        .query(this::record)
        .list();
  }

  /** The current Fact Records of some Facts. */
  public List<FactRecordRow> factRecordsOf(String domain, Collection<String> facts) {
    if (facts.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(RECORDS + " WHERE domain = :domain AND fact = ANY(CAST(:facts AS text[]))")
        .param("domain", domain)
        .param("facts", array(facts))
        .query(this::record)
        .list();
  }

  /** The latest Input Observation at or before a commit of each of some Input Resources. */
  public Map<String, LogPosition> latestObservationsAt(
      String domain, Collection<String> inputResources, long commit) {
    var latest = new HashMap<String, LogPosition>();
    if (inputResources.isEmpty()) {
      return latest;
    }
    jdbc.sql(
            "SELECT DISTINCT ON (target_input_resource) target_input_resource, commit_number,"
                + " ordinal FROM semantic_catalog.log_records"
                + " WHERE domain = :domain AND target_input_resource = ANY(CAST(:resources AS text[]))"
                + " AND kind = 'input-observation' AND commit_number <= :commit"
                + " ORDER BY target_input_resource, commit_number DESC, ordinal DESC")
        .param("domain", domain)
        .param("resources", array(inputResources))
        .param("commit", commit)
        .query(
            (rs, n) ->
                latest.put(
                    rs.getString("target_input_resource"),
                    new LogPosition(rs.getLong("commit_number"), rs.getInt("ordinal"))))
        .list();
    return latest;
  }

  /**
   * The log records of some Facts up to a commit, each Fact's in log order: the record creating it,
   * then its Fact Events. One query reads them all, for a read at a point before those Facts
   * changed.
   */
  public Map<String, List<PositionedRecord>> factLogsOf(
      String domain, Collection<String> facts, long upToCommit) {
    var records = new HashMap<String, List<PositionedRecord>>();
    if (facts.isEmpty()) {
      return records;
    }
    jdbc.sql(
            "SELECT target_fact, commit_number, ordinal, content::text AS content"
                + " FROM semantic_catalog.log_records"
                + " WHERE domain = :domain AND target_fact = ANY(CAST(:facts AS text[]))"
                + " AND commit_number <= :commit"
                + " ORDER BY target_fact, commit_number, ordinal")
        .param("domain", domain)
        .param("facts", array(facts))
        .param("commit", upToCommit)
        .query(
            (rs, n) -> {
              records
                  .computeIfAbsent(rs.getString("target_fact"), key -> new ArrayList<>())
                  .add(positioned(rs, n));
              return n;
            })
        .list();
    return records;
  }

  private static String[] array(Collection<String> values) {
    return values.toArray(String[]::new);
  }

  // Projection loading and identity, within this transaction

  /** Loads a domain's materialized records into a projection, in this transaction's snapshot. */
  public ProjectionSource projectionSource(String domain) {
    return new Source(domain);
  }

  /** Finds a domain's existing records by identity, in this transaction's snapshot. */
  public IdentityLookup identityLookup(String domain) {
    return new Identity(domain);
  }

  private final class Source implements ProjectionSource {
    private final String domain;

    Source(String domain) {
      this.domain = domain;
    }

    @Override
    public Optional<LoadedFact> fact(String fact) {
      return jdbc.sql(FACTS + " WHERE domain = ? AND fact = ?")
          .params(domain, fact)
          .query(StoreTransaction.this::fact)
          .optional()
          .map(
              row ->
                  new LoadedFact(
                      row,
                      jdbc.sql(RECORDS + " WHERE domain = ? AND fact = ?")
                          .params(domain, fact)
                          .query(StoreTransaction.this::record)
                          .optional()
                          .orElseThrow(
                              () ->
                                  new IllegalStateException(
                                      "Fact " + fact + " has no Fact Record")),
                      jdbc.sql(PERIODS + " WHERE domain = ? AND fact = ? ORDER BY confirmed_commit")
                          .params(domain, fact)
                          .query(StoreTransaction.this::period)
                          .list(),
                      jdbc.sql(DEPENDENCIES + " WHERE domain = ? AND dependent_fact = ?")
                          .params(domain, fact)
                          .query(StoreTransaction.this::dependency)
                          .list()));
    }

    @Override
    public Optional<LoadedAddress> address(String factAddress) {
      return jdbc.sql(ADDRESSES + " WHERE domain = ? AND fact_address = ?")
          .params(domain, factAddress)
          .query(StoreTransaction.this::address)
          .optional()
          .map(
              row ->
                  new LoadedAddress(
                      row,
                      jdbc.sql(
                              "SELECT fact FROM semantic_catalog.facts"
                                  + " WHERE domain = ? AND fact_address = ? ORDER BY fact")
                          .params(domain, factAddress)
                          .query(StoreTransaction::firstString)
                          .list(),
                      jdbc
                          .sql(
                              LOG_RECORDS
                                  + " WHERE domain = ? AND target_fact_address = ?"
                                  + " AND kind = 'resolve-conflict'"
                                  + " ORDER BY commit_number, ordinal")
                          .params(domain, factAddress)
                          .query(StoreTransaction.this::positioned)
                          .list()
                          .stream()
                          .map(StoreTransaction::resolution)
                          .toList(),
                      jdbc.sql(
                              SELECTIONS
                                  + " WHERE domain = ? AND fact_address = ? AND to_commit IS NULL")
                          .params(domain, factAddress)
                          .query(StoreTransaction.this::selection)
                          .optional()));
    }

    @Override
    public Optional<ElementRow> element(String element) {
      return jdbc.sql(ELEMENTS + " WHERE domain = ? AND element = ?")
          .params(domain, element)
          .query(StoreTransaction.this::element)
          .optional();
    }

    @Override
    public Optional<InputResourceRow> inputResource(String inputResource) {
      return jdbc.sql(RESOURCES + " WHERE domain = ? AND input_resource = ?")
          .params(domain, inputResource)
          .query(StoreTransaction.this::resource)
          .optional();
    }

    @Override
    public Optional<String> observationResource(String observation) {
      if (!Urns.kind(observation).equals(Urns.INPUT_OBSERVATION)) {
        return Optional.empty();
      }
      var at = Urns.position(observation);
      return jdbc.sql(
              "SELECT target_input_resource FROM semantic_catalog.log_records"
                  + " WHERE domain = ? AND commit_number = ? AND ordinal = ?"
                  + " AND kind = 'input-observation'")
          .params(domain, at.commit(), at.ordinal())
          .query(String.class)
          .optional();
    }

    @Override
    public Optional<String> latestObservation(String inputResource) {
      return jdbc.sql(
              "SELECT commit_number, ordinal FROM semantic_catalog.log_records"
                  + " WHERE domain = ? AND target_input_resource = ?"
                  + " AND kind = 'input-observation'"
                  + " ORDER BY commit_number DESC, ordinal DESC LIMIT 1")
          .params(domain, inputResource)
          .query(
              (rs, n) ->
                  Urns.of(
                      domain,
                      Urns.INPUT_OBSERVATION,
                      new LogPosition(rs.getLong("commit_number"), rs.getInt("ordinal"))))
          .optional();
    }

    @Override
    public boolean isPolicyVersion(String logRecord) {
      if (!Urns.kind(logRecord).equals(Urns.LOG_RECORD)) {
        return false;
      }
      var at = Urns.position(logRecord);
      return jdbc.sql(
                  "SELECT count(*) FROM semantic_catalog.log_records"
                      + " WHERE domain = ? AND commit_number = ? AND ordinal = ?"
                      + " AND kind = 'policy-version'")
              .params(domain, at.commit(), at.ordinal())
              .query(Long.class)
              .single()
          > 0;
    }

    @Override
    public Optional<PositionedRecord> latestPolicyVersion() {
      return jdbc.sql(
              LOG_RECORDS
                  + " WHERE domain = ? AND kind = 'policy-version'"
                  + " ORDER BY commit_number DESC, ordinal DESC LIMIT 1")
          .params(domain)
          .query(StoreTransaction.this::positioned)
          .optional();
    }

    @Override
    public Optional<String> factAddress(String fact) {
      return jdbc.sql(
              "SELECT fact_address FROM semantic_catalog.facts WHERE domain = ? AND fact = ?")
          .params(domain, fact)
          .query(String.class)
          .optional();
    }

    @Override
    public Optional<FactEvent> event(String fact, LogPosition at) {
      return jdbc.sql(
              LOG_RECORDS
                  + " WHERE domain = ? AND commit_number = ? AND ordinal = ? AND target_fact = ?")
          .params(domain, at.commit(), at.ordinal(), fact)
          .query(StoreTransaction.this::positioned)
          .optional()
          .map(PositionedRecord::record)
          .filter(FactEvent.class::isInstance)
          .map(FactEvent.class::cast);
    }

    @Override
    public Set<String> confirmedDependents(String fact) {
      return new LinkedHashSet<>(
          jdbc.sql(
                  "SELECT d.dependent_fact FROM semantic_catalog.dependencies d"
                      + " JOIN semantic_catalog.fact_records r"
                      + " ON r.domain = d.domain AND r.fact = d.dependent_fact"
                      + " WHERE d.domain = ? AND d.depended_fact = ? AND d.for_confirmation"
                      + " AND r.state = ? ORDER BY d.dependent_fact")
              .params(domain, fact, FactState.CONFIRMED.name())
              .query(StoreTransaction::firstString)
              .list());
    }

    @Override
    public List<SelectionRule.Fact> selectionFacts(String factAddress) {
      var presentableSince = new LinkedHashMap<String, @Nullable Long>();
      var dependencies = new HashMap<String, Set<String>>();
      jdbc.sql(
              "SELECT r.fact, p.confirmed_commit, d.depended_fact"
                  + " FROM semantic_catalog.fact_records r"
                  + " LEFT JOIN semantic_catalog.presentable_periods p"
                  + " ON p.domain = r.domain AND p.fact = r.fact AND p.retired_commit IS NULL"
                  + " LEFT JOIN semantic_catalog.dependencies d"
                  + " ON d.domain = r.domain AND d.dependent_fact = r.fact AND d.for_confirmation"
                  + " AND d.depended_fact IS NOT NULL"
                  + " WHERE r.domain = ? AND r.fact_address = ? ORDER BY r.fact")
          .params(domain, factAddress)
          .query(
              (rs, n) -> {
                var fact = rs.getString("fact");
                presentableSince.put(fact, nullableLong(rs, "confirmed_commit"));
                var depended = rs.getString("depended_fact");
                if (depended != null) {
                  dependencies.computeIfAbsent(fact, key -> new LinkedHashSet<>()).add(depended);
                }
                return fact;
              })
          .list();
      var facts = new ArrayList<SelectionRule.Fact>();
      presentableSince.forEach(
          (fact, since) ->
              facts.add(
                  new SelectionRule.Fact(fact, since, dependencies.getOrDefault(fact, Set.of()))));
      return facts;
    }

    @Override
    public Set<String> dependents(String fact) {
      return new LinkedHashSet<>(
          jdbc.sql(
                  "SELECT dependent_fact FROM semantic_catalog.dependencies"
                      + " WHERE domain = ? AND depended_fact = ? AND in_effect"
                      + " ORDER BY dependent_fact")
              .params(domain, fact)
              .query(StoreTransaction::firstString)
              .list());
    }
  }

  private final class Identity implements IdentityLookup {
    private final String domain;

    Identity(String domain) {
      this.domain = domain;
    }

    @Override
    public Optional<String> technicalElement(ElementKind kind, String identityKey) {
      return jdbc.sql(
              "SELECT a.element FROM semantic_catalog.facts f"
                  + " JOIN semantic_catalog.fact_addresses a"
                  + " ON a.domain = f.domain AND a.fact_address = f.fact_address"
                  + " JOIN semantic_catalog.elements e"
                  + " ON e.domain = a.domain AND e.element = a.element"
                  + " JOIN semantic_catalog.fact_records r"
                  + " ON r.domain = f.domain AND r.fact = f.fact"
                  + " WHERE f.domain = :domain AND e.kind = :kind AND a.predicate = :predicate"
                  + " AND a.scope_description IS NULL AND a.predicate_definition IS NULL"
                  + " AND f.identity_key = :value AND r.state IN (:states)"
                  + " ORDER BY a.element LIMIT 1")
          .param("domain", domain)
          .param("kind", kind.name())
          .param("predicate", Vocabulary.TECHNICAL_REFERENCE)
          .param("value", identityKey)
          .param("states", List.of(FactState.CANDIDATE.name(), FactState.CONFIRMED.name()))
          .query(String.class)
          .optional();
    }

    @Override
    public Optional<String> edge(ElementKind kind, EdgeIdentity identity) {
      return jdbc.sql(
              "SELECT element FROM semantic_catalog.elements WHERE domain = ? AND kind = ?"
                  + " AND edge_end_a = ? AND edge_end_b = ? AND edge_directed = ?"
                  + " AND edge_identity_key = ?")
          .params(
              domain,
              kind.name(),
              identity.endA(),
              identity.endB(),
              identity.directed(),
              identity.identityKey())
          .query(String.class)
          .optional();
    }

    @Override
    public Optional<String> emptyScopeAddress(String element, String predicate) {
      return jdbc.sql(
              "SELECT fact_address FROM semantic_catalog.fact_addresses"
                  + " WHERE domain = ? AND element = ? AND predicate = ?"
                  + " AND scope_description IS NULL AND predicate_definition IS NULL")
          .params(domain, element, predicate)
          .query(String.class)
          .optional();
    }

    @Override
    public Optional<String> closedRangeFact(String factAddress, String identityKey) {
      return jdbc.sql(
              "SELECT fact FROM semantic_catalog.facts"
                  + " WHERE domain = ? AND fact_address = ? AND identity_key = ?")
          .params(domain, factAddress, identityKey)
          .query(String.class)
          .optional();
    }

    @Override
    public Optional<String> inputResourceByTable(String tableIdentifier) {
      return jdbc.sql(
              "SELECT input_resource FROM semantic_catalog.input_resources"
                  + " WHERE domain = ? AND table_identifier = ?")
          .params(domain, tableIdentifier)
          .query(String.class)
          .optional();
    }

    @Override
    public boolean taken(String urn) {
      var kind = Urns.kind(urn);
      var sql =
          switch (kind) {
            case Urns.FACT_ADDRESS ->
                "SELECT 1 FROM semantic_catalog.fact_addresses"
                    + " WHERE domain = ? AND fact_address = ?";
            case Urns.FACT -> "SELECT 1 FROM semantic_catalog.facts WHERE domain = ? AND fact = ?";
            default -> {
              if (!Urns.isElementKind(kind)) {
                throw new IllegalArgumentException("Not a name-based URN: " + urn);
              }
              yield "SELECT 1 FROM semantic_catalog.elements WHERE domain = ? AND element = ?";
            }
          };
      return jdbc.sql(sql).params(domain, urn).query(Integer.class).optional().isPresent();
    }

    @Override
    public int factCount(String factAddress) {
      return jdbc.sql(
              "SELECT count(*) FROM semantic_catalog.facts WHERE domain = ? AND fact_address = ?")
          .params(domain, factAddress)
          .query(Integer.class)
          .single();
    }
  }

  // Row mapping

  private static final String LOG_RECORDS =
      "SELECT commit_number, ordinal, content::text AS content FROM semantic_catalog.log_records";

  private static final String ELEMENTS =
      "SELECT element, kind, parent, technical_reference, created_commit, edge_end_a, edge_end_b,"
          + " edge_directed,"
          + " edge_identity_key, edge_identifying_part::text AS edge_identifying_part"
          + " FROM semantic_catalog.elements";

  private static final String ADDRESSES =
      "SELECT fact_address, element, predicate, scope_description,"
          + " scope_entities::text AS scope_entities, predicate_definition, created_commit"
          + " FROM semantic_catalog.fact_addresses";

  private static final String FACTS =
      "SELECT fact, fact_address, identity_key, created_commit FROM semantic_catalog.facts";

  private static final String RESOURCES =
      "SELECT input_resource, kind, table_identifier, created_commit"
          + " FROM semantic_catalog.input_resources";

  private static final String RECORDS =
      "SELECT fact, fact_address, latest_event_commit, latest_event_ordinal, state,"
          + " awaiting_consent,"
          + " open_retirement_proposal, unknown_answer_pending, record::text AS record"
          + " FROM semantic_catalog.fact_records";

  private static final String SELECTIONS =
      "SELECT fact_address, from_commit, to_commit, selected_fact,"
          + " eligible_facts::text AS eligible_facts FROM semantic_catalog.address_selections";

  private static final String PERIODS =
      "SELECT fact, confirmed_commit, retired_commit FROM semantic_catalog.presentable_periods";

  private static final String DEPENDENCIES =
      "SELECT dependent_fact, depended_record, depended_fact, depended_input_resource,"
          + " in_effect, for_confirmation FROM semantic_catalog.dependencies";

  private static String firstString(ResultSet rs, int row) throws SQLException {
    return rs.getString(1);
  }

  private PositionedRecord positioned(ResultSet rs, int row) throws SQLException {
    return new PositionedRecord(
        new LogPosition(rs.getLong("commit_number"), rs.getInt("ordinal")),
        LogRecordJson.read(rs.getString("content")));
  }

  private ElementRow element(ResultSet rs, int row) throws SQLException {
    var endA = rs.getString("edge_end_a");
    var part = rs.getString("edge_identifying_part");
    EdgeIdentity edge =
        endA == null || part == null
            ? null
            : new EdgeIdentity(
                endA,
                rs.getString("edge_end_b"),
                rs.getBoolean("edge_directed"),
                rs.getString("edge_identity_key"),
                LogRecordJson.readNode(part));
    return new ElementRow(
        rs.getString("element"),
        ElementKind.valueOf(rs.getString("kind")),
        rs.getString("parent"),
        rs.getString("technical_reference"),
        rs.getLong("created_commit"),
        edge);
  }

  private FactAddressRow address(ResultSet rs, int row) throws SQLException {
    return new FactAddressRow(
        rs.getString("fact_address"),
        rs.getString("element"),
        rs.getString("predicate"),
        rs.getString("scope_description"),
        LogRecordJson.readStrings(rs.getString("scope_entities")),
        rs.getString("predicate_definition"),
        rs.getLong("created_commit"));
  }

  private FactRow fact(ResultSet rs, int row) throws SQLException {
    return new FactRow(
        rs.getString("fact"),
        rs.getString("fact_address"),
        rs.getString("identity_key"),
        rs.getLong("created_commit"));
  }

  private InputResourceRow resource(ResultSet rs, int row) throws SQLException {
    return new InputResourceRow(
        rs.getString("input_resource"),
        InputKind.valueOf(rs.getString("kind")),
        rs.getString("table_identifier"),
        rs.getLong("created_commit"));
  }

  private static Treatment treatment(ResultSet rs) throws SQLException {
    return new Treatment(
        FactState.valueOf(rs.getString("state")),
        rs.getBoolean("awaiting_consent"),
        rs.getString("open_retirement_proposal"),
        rs.getBoolean("unknown_answer_pending"));
  }

  private FactRecordRow record(ResultSet rs, int row) throws SQLException {
    return new FactRecordRow(
        rs.getString("fact"),
        rs.getString("fact_address"),
        new LogPosition(rs.getLong("latest_event_commit"), rs.getInt("latest_event_ordinal")),
        treatment(rs),
        LogRecordJson.readNode(rs.getString("record")));
  }

  private static SelectionRule.Resolution resolution(PositionedRecord positioned) {
    if (!(positioned.record() instanceof LogRecord.ResolveConflict resolution)) {
      throw new IllegalStateException("Not a conflict resolution: " + positioned.position());
    }
    return new SelectionRule.Resolution(
        resolution.chosen().urnValue(),
        resolution.passedOver().stream().map(Ref::urnValue).toList(),
        positioned.position().commit());
  }

  private AddressSelectionRow selection(ResultSet rs, int row) throws SQLException {
    return new AddressSelectionRow(
        rs.getString("fact_address"),
        rs.getLong("from_commit"),
        nullableLong(rs, "to_commit"),
        rs.getString("selected_fact"),
        LogRecordJson.readStrings(rs.getString("eligible_facts")));
  }

  private PresentablePeriodRow period(ResultSet rs, int row) throws SQLException {
    return new PresentablePeriodRow(
        rs.getString("fact"), rs.getLong("confirmed_commit"), nullableLong(rs, "retired_commit"));
  }

  private DependencyRow dependency(ResultSet rs, int row) throws SQLException {
    return new DependencyRow(
        rs.getString("dependent_fact"),
        rs.getString("depended_record"),
        rs.getString("depended_fact"),
        rs.getString("depended_input_resource"),
        rs.getBoolean("in_effect"),
        rs.getBoolean("for_confirmation"));
  }

  private static @Nullable Long nullableLong(ResultSet rs, String column) throws SQLException {
    var value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }
}
