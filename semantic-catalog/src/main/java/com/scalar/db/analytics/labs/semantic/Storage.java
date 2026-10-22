/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.SemanticCatalogException.Kind;
import com.scalar.db.analytics.labs.semantic.model.Json;
import com.scalar.db.analytics.labs.semantic.model.LogEntry;
import com.scalar.db.analytics.labs.semantic.storage.Repositories;
import com.scalar.db.analytics.labs.semantic.storage.Rows;
import com.scalar.db.analytics.labs.semantic.storage.StorageConfiguration;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The storage adapter: the Semantic Catalog's tables in the Physical Catalog's store database, in
 * the {@code semantic_catalog} schema, accessed through Spring Data JDBC. PostgreSQL is assumed.
 *
 * <p>Every domain shares the tables, keyed by domain. Writers to one domain are serialized by a
 * PostgreSQL advisory lock held for the write transaction, so several processes can work on one
 * domain: each takes the lock, catches up on the commits others wrote, and then writes the next
 * commit, together with the build task and review presentation updates and the call identifier it
 * belongs to. No database type leaves this class.
 */
final class Storage implements AutoCloseable {
  private static final String SCHEMA = Rows.SCHEMA;

  /** Schema migrations, applied in order; the index plus one is the schema version. */
  private static final List<List<String>> MIGRATIONS =
      List.of(
          List.of(
              "CREATE TABLE "
                  + SCHEMA
                  + ".domains (name TEXT PRIMARY KEY, created_at TIMESTAMPTZ NOT NULL)",
              "CREATE TABLE "
                  + SCHEMA
                  + ".commits (domain TEXT NOT NULL, commit_no BIGINT NOT NULL,"
                  + " written_at TIMESTAMPTZ NOT NULL, operation TEXT NOT NULL, principal TEXT NOT NULL,"
                  + " PRIMARY KEY (domain, commit_no))",
              "CREATE TABLE "
                  + SCHEMA
                  + ".log_records (domain TEXT NOT NULL, commit_no BIGINT NOT NULL,"
                  + " ordinal INTEGER NOT NULL, record_type TEXT NOT NULL, payload TEXT NOT NULL,"
                  + " PRIMARY KEY (domain, commit_no, ordinal))",
              "CREATE TABLE "
                  + SCHEMA
                  + ".fact_records (domain TEXT NOT NULL, fact_urn TEXT NOT NULL,"
                  + " address_urn TEXT NOT NULL, state TEXT NOT NULL, latest_revision TEXT NOT NULL,"
                  + " PRIMARY KEY (domain, fact_urn))",
              "CREATE TABLE "
                  + SCHEMA
                  + ".presentable_periods (domain TEXT NOT NULL, fact_urn TEXT NOT NULL,"
                  + " confirmed_commit BIGINT NOT NULL, retired_commit BIGINT)",
              "CREATE INDEX presentable_periods_fact ON "
                  + SCHEMA
                  + ".presentable_periods (domain, fact_urn)",
              "CREATE TABLE "
                  + SCHEMA
                  + ".address_selections (domain TEXT NOT NULL, address_urn TEXT NOT NULL,"
                  + " from_commit BIGINT NOT NULL, to_commit BIGINT, selected_fact TEXT, eligible TEXT NOT NULL)",
              "CREATE INDEX address_selections_address ON "
                  + SCHEMA
                  + ".address_selections (domain, address_urn)",
              "CREATE TABLE "
                  + SCHEMA
                  + ".build_tasks (id TEXT PRIMARY KEY, domain TEXT NOT NULL,"
                  + " seq BIGINT NOT NULL, record TEXT NOT NULL)",
              "CREATE TABLE "
                  + SCHEMA
                  + ".review_presentations (id TEXT PRIMARY KEY, domain TEXT NOT NULL,"
                  + " seq BIGINT NOT NULL, record TEXT NOT NULL)",
              "CREATE TABLE "
                  + SCHEMA
                  + ".call_records (id TEXT PRIMARY KEY, domain TEXT NOT NULL,"
                  + " operation TEXT NOT NULL, input_digest TEXT NOT NULL, output TEXT NOT NULL,"
                  + " recorded_at TIMESTAMPTZ NOT NULL)",
              "CREATE TABLE "
                  + SCHEMA
                  + ".derived_records (domain TEXT NOT NULL, input_resource TEXT NOT NULL,"
                  + " version TEXT NOT NULL, record TEXT NOT NULL, recorded_at TIMESTAMPTZ NOT NULL,"
                  + " PRIMARY KEY (domain, input_resource, version))"));

  /** Provisional: how long call identifiers are retained for resend detection. */
  static final Duration CALL_RETENTION = Duration.ofDays(30);

  private final AnnotationConfigApplicationContext context;
  private final String domain;
  private final NamedParameterJdbcOperations jdbc;
  private final JdbcAggregateOperations aggregates;
  private final TransactionTemplate transactions;
  private final Repositories.Domains domains;
  private final Repositories.BuildTasks buildTasks;
  private final Repositories.Presentations presentations;
  private final Repositories.Calls calls;

  private Storage(AnnotationConfigApplicationContext context, String domain) {
    this.context = context;
    this.domain = domain;
    jdbc = context.getBean(NamedParameterJdbcOperations.class);
    aggregates = context.getBean(JdbcAggregateOperations.class);
    transactions = context.getBean(TransactionTemplate.class);
    domains = context.getBean(Repositories.Domains.class);
    buildTasks = context.getBean(Repositories.BuildTasks.class);
    presentations = context.getBean(Repositories.Presentations.class);
    calls = context.getBean(Repositories.Calls.class);
  }

  /** Connects to the store, migrates the schema, and binds the storage to one domain. */
  static Storage open(StorageSettings settings, String domain) {
    HikariDataSource dataSource = null;
    AnnotationConfigApplicationContext context = null;
    try {
      var config = new HikariConfig();
      config.setJdbcUrl(settings.jdbcUrl());
      if (settings.username() != null) {
        config.setUsername(settings.username());
      }
      config.setPassword(settings.password());
      config.setMaximumPoolSize(4);
      config.setConnectionTimeout(10_000);
      config.setPoolName("semantic-catalog");
      dataSource = new HikariDataSource(config);
      var source = dataSource;
      context = new AnnotationConfigApplicationContext();
      context.registerBean("dataSource", DataSource.class, () -> source);
      context.register(StorageConfiguration.class);
      context.refresh();
      var storage = new Storage(context, domain);
      storage.migrate();
      return storage;
    } catch (RuntimeException e) {
      if (context != null) {
        context.close();
      }
      if (dataSource != null) {
        dataSource.close();
      }
      throw failure("Could not open the Semantic Catalog storage.", e);
    }
  }

  private void migrate() {
    transactions.executeWithoutResult(
        status -> {
          // One process migrates at a time.
          jdbc.getJdbcOperations()
              .execute("SELECT pg_advisory_xact_lock(hashtextextended('sdba:migration', 0))");
          jdbc.getJdbcOperations().execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
          jdbc.getJdbcOperations()
              .execute(
                  "CREATE TABLE IF NOT EXISTS "
                      + SCHEMA
                      + ".schema_version (version INTEGER NOT NULL)");
          var current =
              Objects.requireNonNullElse(
                  jdbc.queryForObject(
                      "SELECT max(version) FROM " + SCHEMA + ".schema_version",
                      Map.of(),
                      Integer.class),
                  0);
          for (var index = current; index < MIGRATIONS.size(); index++) {
            for (var sql : MIGRATIONS.get(index)) {
              jdbc.getJdbcOperations().execute(sql);
            }
            jdbc.update(
                "INSERT INTO " + SCHEMA + ".schema_version VALUES (:version)",
                Map.of("version", index + 1));
          }
        });
  }

  boolean domainExists() {
    return guarded(() -> domains.existsById(domain));
  }

  void createDomain() {
    guarded(
        () -> {
          if (domains.existsById(domain)) {
            throw new SemanticCatalogException(
                Kind.INVALID_INPUT, "The Semantic Catalog domain already exists.");
          }
          aggregates.insert(new Rows.DomainRow(domain, Instant.now()));
          return null;
        });
  }

  /**
   * Runs a write in one transaction that holds the domain's writer lock, so the body sees every
   * commit written before it and no other writer interleaves.
   */
  <T> T writeTransaction(Supplier<T> body) {
    return guarded(
        () ->
            transactions.execute(
                status -> {
                  jdbc.query(
                      "SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))",
                      Map.of("key", "sdba:domain:" + domain),
                      (org.springframework.jdbc.core.ResultSetExtractor<@Nullable Void>)
                          rows -> null);
                  return body.get();
                }));
  }

  /** The log records after a commit, in order. */
  List<Commit.LogRecord> readLog(long afterCommit) {
    return guarded(
        () ->
            jdbc.query(
                "SELECT commit_no, ordinal, payload FROM "
                    + SCHEMA
                    + ".log_records"
                    + " WHERE domain = :domain AND commit_no > :after ORDER BY commit_no, ordinal",
                Map.of("domain", domain, "after", afterCommit),
                (row, index) ->
                    new Commit.LogRecord(
                        row.getLong(1),
                        row.getInt(2),
                        Json.read(row.getString(3), LogEntry.class))));
  }

  /** Everything one write records. A null commit writes only records kept outside the log. */
  record Write(
      @Nullable Commit commit,
      @Nullable CatalogState state,
      Collection<BuildTask> tasks,
      Collection<ReviewPresentation> presentations,
      @Nullable CallRecord call,
      @Nullable DerivedRecord derived) {}

  record CallRecord(String callId, String operation, String inputDigest, JsonNode output) {}

  record DerivedRecord(String inputResource, String version, JsonNode record) {}

  /** Writes inside a {@link #writeTransaction}. */
  void write(Write write) {
    var now = Timestamp.from(Instant.now());
    var commit = write.commit();
    if (commit != null && !commit.empty()) {
      jdbc.update(
          "INSERT INTO "
              + SCHEMA
              + ".commits VALUES (:domain, :commit, :now, :operation, :principal)",
          new MapSqlParameterSource()
              .addValue("domain", domain)
              .addValue("commit", commit.number)
              .addValue("now", now)
              .addValue("operation", commit.operation)
              .addValue("principal", commit.principal));
      var batch = new ArrayList<MapSqlParameterSource>();
      for (var record : commit.records()) {
        var payload = Json.tree(record.entry());
        batch.add(
            new MapSqlParameterSource()
                .addValue("domain", domain)
                .addValue("commit", record.commit())
                .addValue("ordinal", record.ordinal())
                .addValue("type", payload.path("record").asText())
                .addValue("payload", Json.write(payload)));
      }
      jdbc.batchUpdate(
          "INSERT INTO "
              + SCHEMA
              + ".log_records VALUES (:domain, :commit, :ordinal, :type, :payload)",
          batch.toArray(MapSqlParameterSource[]::new));
      materialize(Objects.requireNonNull(write.state()), commit.facts(), commit.addresses());
    }
    for (var task : write.tasks()) {
      upsert(
          new Rows.BuildTaskRow(
              domain + "/" + task.id(), domain, task.sequence(), Json.write(task)),
          buildTasks.existsById(domain + "/" + task.id()));
    }
    for (var presentation : write.presentations()) {
      var id = domain + "/" + presentation.id();
      var sequence =
          Long.parseLong(presentation.id().substring(presentation.id().lastIndexOf('-') + 1));
      upsert(
          new Rows.PresentationRow(id, domain, sequence, Json.write(presentation)),
          presentations.existsById(id));
    }
    var call = write.call();
    if (call != null) {
      aggregates.insert(
          new Rows.CallRow(
              domain + "/" + call.callId(),
              domain,
              call.operation(),
              call.inputDigest(),
              Json.write(call.output()),
              Instant.now()));
      calls.deleteRecordedBefore(Instant.now().minus(CALL_RETENTION));
    }
    var derived = write.derived();
    if (derived != null) {
      jdbc.update(
          "INSERT INTO "
              + SCHEMA
              + ".derived_records VALUES (:domain, :resource, :version, :record, :now)"
              + " ON CONFLICT (domain, input_resource, version) DO UPDATE SET record = EXCLUDED.record",
          new MapSqlParameterSource()
              .addValue("domain", domain)
              .addValue("resource", derived.inputResource())
              .addValue("version", derived.version())
              .addValue("record", Json.write(derived.record()))
              .addValue("now", now));
    }
  }

  /** Writes records kept outside the log in a transaction of their own. */
  void writeOutsideLog(Write write) {
    writeTransaction(
        () -> {
          write(write);
          return null;
        });
  }

  private <T> void upsert(T row, boolean exists) {
    if (exists) {
      aggregates.update(row);
    } else {
      aggregates.insert(row);
    }
  }

  /** Rewrites the materialized rows of the Facts and Fact Addresses a commit touched. */
  private void materialize(
      CatalogState state, Collection<String> facts, Collection<String> addresses) {
    for (var urn : facts) {
      var fact = state.requireFact(urn);
      var key = Map.of("domain", domain, "urn", urn);
      jdbc.update(
          "DELETE FROM " + SCHEMA + ".fact_records WHERE domain = :domain AND fact_urn = :urn",
          key);
      jdbc.update(
          "DELETE FROM "
              + SCHEMA
              + ".presentable_periods WHERE domain = :domain AND fact_urn = :urn",
          key);
      for (var row : MaterializedRows.fact(fact)) {
        insert("fact_records", row);
      }
      for (var row : MaterializedRows.periods(fact)) {
        insert("presentable_periods", row);
      }
    }
    for (var urn : addresses) {
      var address = Objects.requireNonNull(state.address(urn));
      jdbc.update(
          "DELETE FROM "
              + SCHEMA
              + ".address_selections WHERE domain = :domain AND address_urn = :urn",
          Map.of("domain", domain, "urn", urn));
      for (var row : MaterializedRows.selections(address)) {
        insert("address_selections", row);
      }
    }
  }

  private void insert(String table, List<@Nullable Object> row) {
    var parameters = new MapSqlParameterSource().addValue("p0", domain);
    var names = new StringBuilder(":p0");
    for (var i = 0; i < row.size(); i++) {
      parameters.addValue("p" + (i + 1), row.get(i));
      names.append(", :p").append(i + 1);
    }
    jdbc.update("INSERT INTO " + SCHEMA + "." + table + " VALUES (" + names + ")", parameters);
  }

  /** The materialized rows as stored, for the invariant check. */
  TreeSet<String> materializedRows() {
    return guarded(
        () -> {
          var rows = new TreeSet<String>();
          var key = Map.of("domain", domain);
          jdbc.query(
              "SELECT fact_urn, address_urn, state, latest_revision FROM "
                  + SCHEMA
                  + ".fact_records WHERE domain = :domain",
              key,
              (row, index) ->
                  rows.add(
                      MaterializedRows.encode(
                          "fact",
                          row.getString(1),
                          row.getString(2),
                          row.getString(3),
                          row.getString(4))));
          jdbc.query(
              "SELECT fact_urn, confirmed_commit, retired_commit FROM "
                  + SCHEMA
                  + ".presentable_periods WHERE domain = :domain",
              key,
              (row, index) ->
                  rows.add(
                      MaterializedRows.encode(
                          "period",
                          row.getString(1),
                          row.getLong(2),
                          row.getObject(3) == null ? null : row.getLong(3))));
          jdbc.query(
              "SELECT address_urn, from_commit, to_commit, selected_fact, eligible FROM "
                  + SCHEMA
                  + ".address_selections WHERE domain = :domain",
              key,
              (row, index) ->
                  rows.add(
                      MaterializedRows.encode(
                          "selection",
                          row.getString(1),
                          row.getLong(2),
                          row.getObject(3) == null ? null : row.getLong(3),
                          row.getString(4),
                          row.getString(5))));
          return rows;
        });
  }

  List<BuildTask> tasks() {
    return guarded(
        () ->
            buildTasks.findByDomainOrderBySeq(domain).stream()
                .map(row -> Json.read(row.record(), BuildTask.class))
                .toList());
  }

  List<ReviewPresentation> presentations() {
    return guarded(
        () ->
            presentations.findByDomainOrderBySeq(domain).stream()
                .map(row -> Json.read(row.record(), ReviewPresentation.class))
                .toList());
  }

  Optional<CallRecord> call(String callId) {
    return guarded(
        () ->
            calls
                .findById(domain + "/" + callId)
                .map(
                    row ->
                        new CallRecord(
                            callId, row.operation(), row.inputDigest(), Json.read(row.output()))));
  }

  boolean derivedRecordExists(String inputResource, String version) {
    return guarded(
        () ->
            Objects.requireNonNullElse(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM "
                            + SCHEMA
                            + ".derived_records"
                            + " WHERE domain = :domain AND input_resource = :resource AND version = :version",
                        Map.of("domain", domain, "resource", inputResource, "version", version),
                        Long.class),
                    0L)
                > 0);
  }

  @Override
  public void close() {
    var dataSource = context.getBean(DataSource.class);
    context.close();
    if (dataSource instanceof HikariDataSource hikari) {
      hikari.close();
    }
  }

  private static <T> T guarded(Supplier<T> body) {
    try {
      return body.get();
    } catch (DataAccessException e) {
      throw failure("Could not access the Semantic Catalog storage.", e);
    }
  }

  private static SemanticCatalogException failure(String message, Exception cause) {
    return new SemanticCatalogException(Kind.STORAGE_FAILURE, message, cause);
  }
}
