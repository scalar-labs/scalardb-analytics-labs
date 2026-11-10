/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.storage;

import com.scalar.db.analytics.labs.semantic.model.LogPosition;
import com.scalar.db.analytics.labs.semantic.model.LogRecordJson;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.projection.IdentityLookup;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.EdgeIdentity;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.ElementRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.FactAddressRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.FactRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.InputResourceRow;
import com.scalar.db.analytics.labs.semantic.projection.PositionedRecord;
import com.scalar.db.analytics.labs.semantic.projection.ProjectionSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
   */
  public void insertLogRecord(
      String domain,
      PositionedRecord record,
      @Nullable String targetFact,
      @Nullable String targetInputResource) {
    jdbc.sql(
            "INSERT INTO semantic_catalog.log_records"
                + " (domain, commit_number, ordinal, kind, target_fact, target_input_resource,"
                + " content)"
                + " VALUES (:domain, :commit, :ordinal, :kind, :fact, :resource,"
                + " CAST(:content AS jsonb))")
        .param("domain", domain)
        .param("commit", record.position().commit())
        .param("ordinal", record.position().ordinal())
        .param("kind", record.record().kind())
        .param("fact", targetFact, Types.VARCHAR)
        .param("resource", targetInputResource, Types.VARCHAR)
        .param("content", LogRecordJson.write(record.record()))
        .update();
  }

  // Materialized rows

  /**
   * Writes a materialized row a commit inserted or changed. Rows of records that never change after
   * creation are inserted, so a row a concurrent writer created first violates its unique
   * constraint.
   */
  public void writeRow(String domain, MaterializedRow row) {
    switch (row) {
      case ElementRow element -> {
        var edge = element.edge();
        jdbc.sql(
                "INSERT INTO semantic_catalog.elements (domain, element, kind, parent,"
                    + " created_commit, edge_end_a, edge_end_b, edge_directed,"
                    + " edge_identity_key, edge_identifying_part)"
                    + " VALUES (:domain, :element, :kind, :parent, :created, :a, :b, :directed,"
                    + " :key, CAST(:part AS jsonb))")
            .param("domain", domain)
            .param("element", element.element())
            .param("kind", element.kind().name())
            .param("parent", element.parent(), Types.VARCHAR)
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
                      + " (domain, input_resource, kind, table_identifier, question,"
                      + " created_commit)"
                      + " VALUES (:domain, :resource, :kind, :table, :question, :created)")
              .param("domain", domain)
              .param("resource", resource.inputResource())
              .param("kind", resource.kind())
              .param("table", resource.tableIdentifier(), Types.VARCHAR)
              .param("question", resource.question(), Types.VARCHAR)
              .param("created", resource.createdCommit())
              .update();
    }
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
    public Optional<FactRow> fact(String fact) {
      return jdbc.sql(FACTS + " WHERE domain = ? AND fact = ?")
          .params(domain, fact)
          .query(StoreTransaction.this::fact)
          .optional();
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
                          .list()));
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
                  + " WHERE f.domain = :domain AND e.kind = :kind AND a.predicate = :predicate"
                  + " AND a.scope_description IS NULL AND a.predicate_definition IS NULL"
                  + " AND f.identity_key = :value"
                  + " ORDER BY a.element LIMIT 1")
          .param("domain", domain)
          .param("kind", kind.name())
          .param("predicate", Vocabulary.TECHNICAL_REFERENCE)
          .param("value", identityKey)
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
    public Optional<String> inputResourceByQuestion(String question) {
      return jdbc.sql(
              "SELECT input_resource FROM semantic_catalog.input_resources"
                  + " WHERE domain = ? AND question = ?")
          .params(domain, question)
          .query(String.class)
          .optional();
    }
  }

  // Row mapping

  private static final String LOG_RECORDS =
      "SELECT commit_number, ordinal, content::text AS content FROM semantic_catalog.log_records";

  private static final String ELEMENTS =
      "SELECT element, kind, parent, created_commit, edge_end_a, edge_end_b, edge_directed,"
          + " edge_identity_key, edge_identifying_part::text AS edge_identifying_part"
          + " FROM semantic_catalog.elements";

  private static final String ADDRESSES =
      "SELECT fact_address, element, predicate, scope_description,"
          + " scope_entities::text AS scope_entities, predicate_definition, created_commit"
          + " FROM semantic_catalog.fact_addresses";

  private static final String FACTS =
      "SELECT fact, fact_address, identity_key, created_commit FROM semantic_catalog.facts";

  private static final String RESOURCES =
      "SELECT input_resource, kind, table_identifier, question, created_commit"
          + " FROM semantic_catalog.input_resources";

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
        rs.getString("kind"),
        rs.getString("table_identifier"),
        rs.getString("question"),
        rs.getLong("created_commit"));
  }
}
