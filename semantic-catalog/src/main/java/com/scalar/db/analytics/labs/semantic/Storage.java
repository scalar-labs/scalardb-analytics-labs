/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.SemanticCatalogException.Kind;
import com.scalar.db.analytics.labs.semantic.model.Json;
import com.scalar.db.analytics.labs.semantic.model.LogEntry;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The storage adapter: one embedded DuckDB database per domain, separate from the Query Engine's.
 *
 * <p>The log, the materialized state, and the records kept outside the log share one database, so
 * one transaction writes a commit together with the build task and review presentation updates and
 * the call identifier it belongs to. DuckDB admits one writing process per database file, which
 * makes this process the domain's single serialized writer. No DuckDB type leaves this class.
 */
final class Storage implements AutoCloseable {
  /** Schema migrations, applied in order; the index plus one is the schema version. */
  private static final List<List<String>> MIGRATIONS =
      List.of(
          List.of(
              "CREATE TABLE schema_version (version INTEGER NOT NULL)",
              "CREATE TABLE domain_info (name VARCHAR NOT NULL, created_at TIMESTAMP NOT NULL)",
              "CREATE TABLE commits (commit_no BIGINT PRIMARY KEY, written_at TIMESTAMP NOT NULL,"
                  + " operation VARCHAR NOT NULL, principal VARCHAR NOT NULL)",
              "CREATE TABLE log_records (commit_no BIGINT NOT NULL, ordinal INTEGER NOT NULL,"
                  + " record_type VARCHAR NOT NULL, payload VARCHAR NOT NULL,"
                  + " PRIMARY KEY (commit_no, ordinal))",
              "CREATE TABLE fact_records (fact_urn VARCHAR PRIMARY KEY, address_urn VARCHAR NOT NULL,"
                  + " state VARCHAR NOT NULL, latest_revision VARCHAR NOT NULL)",
              "CREATE TABLE presentable_periods (fact_urn VARCHAR NOT NULL,"
                  + " confirmed_commit BIGINT NOT NULL, retired_commit BIGINT)",
              "CREATE TABLE address_selections (address_urn VARCHAR NOT NULL,"
                  + " from_commit BIGINT NOT NULL, to_commit BIGINT, selected_fact VARCHAR,"
                  + " eligible VARCHAR NOT NULL)",
              "CREATE TABLE build_tasks (task_id VARCHAR PRIMARY KEY, seq BIGINT NOT NULL,"
                  + " record VARCHAR NOT NULL)",
              "CREATE TABLE review_presentations (presentation_id VARCHAR PRIMARY KEY,"
                  + " record VARCHAR NOT NULL)",
              "CREATE TABLE call_records (call_id VARCHAR PRIMARY KEY, operation VARCHAR NOT NULL,"
                  + " input_digest VARCHAR NOT NULL, output VARCHAR NOT NULL,"
                  + " recorded_at TIMESTAMP NOT NULL)",
              "CREATE TABLE derived_records (input_resource VARCHAR NOT NULL,"
                  + " version VARCHAR NOT NULL, record VARCHAR NOT NULL,"
                  + " recorded_at TIMESTAMP NOT NULL, PRIMARY KEY (input_resource, version))"));

  /** Provisional: how long call identifiers are retained for resend detection. */
  static final Duration CALL_RETENTION = Duration.ofDays(30);

  private final Connection connection;

  private Storage(Connection connection) {
    this.connection = connection;
  }

  static Storage open(Path file) {
    try {
      Class.forName("org.duckdb.DuckDBDriver");
      var connection = DriverManager.getConnection("jdbc:duckdb:" + file.toAbsolutePath());
      var storage = new Storage(connection);
      storage.migrate();
      return storage;
    } catch (ClassNotFoundException | SQLException e) {
      throw failure("Could not open the Semantic Catalog storage.", e);
    }
  }

  private void migrate() throws SQLException {
    var current = 0;
    try (var statement = connection.createStatement()) {
      var exists =
          statement.executeQuery(
              "SELECT count(*) FROM information_schema.tables WHERE table_name = 'schema_version'");
      exists.next();
      if (exists.getLong(1) > 0) {
        var version = statement.executeQuery("SELECT max(version) FROM schema_version");
        version.next();
        current = version.getInt(1);
      }
    }
    for (var index = current; index < MIGRATIONS.size(); index++) {
      connection.setAutoCommit(false);
      try (var statement = connection.createStatement()) {
        for (var sql : MIGRATIONS.get(index)) {
          statement.execute(sql);
        }
        statement.execute("INSERT INTO schema_version VALUES (" + (index + 1) + ")");
        connection.commit();
      } catch (SQLException e) {
        connection.rollback();
        throw e;
      } finally {
        connection.setAutoCommit(true);
      }
    }
  }

  @Nullable String domainName() {
    try (var statement = connection.createStatement()) {
      var rows = statement.executeQuery("SELECT name FROM domain_info");
      return rows.next() ? rows.getString(1) : null;
    } catch (SQLException e) {
      throw failure("Could not read the domain.", e);
    }
  }

  void createDomain(String name) {
    try (var statement = connection.prepareStatement("INSERT INTO domain_info VALUES (?, ?)")) {
      statement.setString(1, name);
      statement.setTimestamp(2, Timestamp.from(Instant.now()));
      statement.executeUpdate();
    } catch (SQLException e) {
      throw failure("Could not create the domain.", e);
    }
  }

  List<Commit.LogRecord> readLog() {
    var records = new ArrayList<Commit.LogRecord>();
    try (var statement = connection.createStatement()) {
      var rows =
          statement.executeQuery(
              "SELECT commit_no, ordinal, payload FROM log_records ORDER BY commit_no, ordinal");
      while (rows.next()) {
        records.add(
            new Commit.LogRecord(
                rows.getLong(1), rows.getInt(2), Json.read(rows.getString(3), LogEntry.class)));
      }
      return records;
    } catch (SQLException e) {
      throw failure("Could not read the log.", e);
    }
  }

  /** Everything one transaction writes. A null commit writes only records kept outside the log. */
  record Write(
      @Nullable Commit commit,
      @Nullable CatalogState state,
      Collection<BuildTask> tasks,
      Collection<ReviewPresentation> presentations,
      @Nullable CallRecord call,
      @Nullable DerivedRecord derived) {}

  record CallRecord(String callId, String operation, String inputDigest, JsonNode output) {}

  record DerivedRecord(String inputResource, String version, JsonNode record) {}

  void write(Write write) {
    try {
      connection.setAutoCommit(false);
      var now = Timestamp.from(Instant.now());
      var commit = write.commit();
      if (commit != null && !commit.empty()) {
        try (var statement =
            connection.prepareStatement("INSERT INTO commits VALUES (?, ?, ?, ?)")) {
          statement.setLong(1, commit.number);
          statement.setTimestamp(2, now);
          statement.setString(3, commit.operation);
          statement.setString(4, commit.principal);
          statement.executeUpdate();
        }
        try (var statement =
            connection.prepareStatement("INSERT INTO log_records VALUES (?, ?, ?, ?)")) {
          for (var record : commit.records()) {
            var payload = Json.tree(record.entry());
            statement.setLong(1, record.commit());
            statement.setInt(2, record.ordinal());
            statement.setString(3, payload.path("record").asText());
            statement.setString(4, Json.write(payload));
            statement.addBatch();
          }
          statement.executeBatch();
        }
        materialize(
            java.util.Objects.requireNonNull(write.state()), commit.facts(), commit.addresses());
      }
      for (var task : write.tasks()) {
        try (var statement =
            connection.prepareStatement("INSERT OR REPLACE INTO build_tasks VALUES (?, ?, ?)")) {
          statement.setString(1, task.id());
          statement.setLong(2, task.sequence());
          statement.setString(3, Json.write(task));
          statement.executeUpdate();
        }
      }
      for (var presentation : write.presentations()) {
        try (var statement =
            connection.prepareStatement(
                "INSERT OR REPLACE INTO review_presentations VALUES (?, ?)")) {
          statement.setString(1, presentation.id());
          statement.setString(2, Json.write(presentation));
          statement.executeUpdate();
        }
      }
      var call = write.call();
      if (call != null) {
        try (var statement =
            connection.prepareStatement("INSERT INTO call_records VALUES (?, ?, ?, ?, ?)")) {
          statement.setString(1, call.callId());
          statement.setString(2, call.operation());
          statement.setString(3, call.inputDigest());
          statement.setString(4, Json.write(call.output()));
          statement.setTimestamp(5, now);
          statement.executeUpdate();
        }
        try (var statement =
            connection.prepareStatement("DELETE FROM call_records WHERE recorded_at < ?")) {
          statement.setTimestamp(1, Timestamp.from(Instant.now().minus(CALL_RETENTION)));
          statement.executeUpdate();
        }
      }
      var derived = write.derived();
      if (derived != null) {
        try (var statement =
            connection.prepareStatement(
                "INSERT OR REPLACE INTO derived_records VALUES (?, ?, ?, ?)")) {
          statement.setString(1, derived.inputResource());
          statement.setString(2, derived.version());
          statement.setString(3, Json.write(derived.record()));
          statement.setTimestamp(4, now);
          statement.executeUpdate();
        }
      }
      connection.commit();
    } catch (SQLException | RuntimeException e) {
      try {
        connection.rollback();
      } catch (SQLException rollbackFailure) {
        e.addSuppressed(rollbackFailure);
      }
      throw failure("Could not write to the Semantic Catalog storage.", e);
    } finally {
      try {
        connection.setAutoCommit(true);
      } catch (SQLException ignored) {
        // The next write reports a broken connection.
      }
    }
  }

  /** Rewrites the materialized rows of the Facts and Fact Addresses a commit touched. */
  private void materialize(
      CatalogState state, Collection<String> facts, Collection<String> addresses)
      throws SQLException {
    for (var urn : facts) {
      var fact = state.requireFact(urn);
      delete("DELETE FROM fact_records WHERE fact_urn = ?", urn);
      delete("DELETE FROM presentable_periods WHERE fact_urn = ?", urn);
      for (var row : MaterializedRows.fact(fact)) {
        insert("INSERT INTO fact_records VALUES (?, ?, ?, ?)", row);
      }
      for (var row : MaterializedRows.periods(fact)) {
        insert("INSERT INTO presentable_periods VALUES (?, ?, ?)", row);
      }
    }
    for (var urn : addresses) {
      var address = java.util.Objects.requireNonNull(state.address(urn));
      delete("DELETE FROM address_selections WHERE address_urn = ?", urn);
      for (var row : MaterializedRows.selections(address)) {
        insert("INSERT INTO address_selections VALUES (?, ?, ?, ?, ?)", row);
      }
    }
  }

  private void delete(String sql, String key) throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      statement.setString(1, key);
      statement.executeUpdate();
    }
  }

  private void insert(String sql, List<@Nullable Object> row) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (var i = 0; i < row.size(); i++) {
        statement.setObject(i + 1, row.get(i));
      }
      statement.executeUpdate();
    }
  }

  /** The materialized rows as stored, for the invariant check. */
  TreeSet<String> materializedRows() {
    var rows = new TreeSet<String>();
    try (var statement = connection.createStatement()) {
      var facts = statement.executeQuery("SELECT * FROM fact_records");
      while (facts.next()) {
        rows.add(
            MaterializedRows.encode(
                "fact",
                facts.getString(1),
                facts.getString(2),
                facts.getString(3),
                facts.getString(4)));
      }
      var periods = statement.executeQuery("SELECT * FROM presentable_periods");
      while (periods.next()) {
        var retired = periods.getObject(3);
        rows.add(
            MaterializedRows.encode(
                "period",
                periods.getString(1),
                periods.getLong(2),
                retired == null ? null : periods.getLong(3)));
      }
      var selections = statement.executeQuery("SELECT * FROM address_selections");
      while (selections.next()) {
        var to = selections.getObject(3);
        rows.add(
            MaterializedRows.encode(
                "selection",
                selections.getString(1),
                selections.getLong(2),
                to == null ? null : selections.getLong(3),
                selections.getString(4),
                selections.getString(5)));
      }
      return rows;
    } catch (SQLException e) {
      throw failure("Could not read the materialized state.", e);
    }
  }

  List<BuildTask> tasks() {
    var tasks = new ArrayList<BuildTask>();
    try (var statement = connection.createStatement()) {
      var rows = statement.executeQuery("SELECT record FROM build_tasks ORDER BY seq");
      while (rows.next()) {
        tasks.add(Json.read(rows.getString(1), BuildTask.class));
      }
      return tasks;
    } catch (SQLException e) {
      throw failure("Could not read build tasks.", e);
    }
  }

  List<ReviewPresentation> presentations() {
    var presentations = new ArrayList<ReviewPresentation>();
    try (var statement = connection.createStatement()) {
      var rows =
          statement.executeQuery(
              "SELECT record FROM review_presentations ORDER BY presentation_id");
      while (rows.next()) {
        presentations.add(Json.read(rows.getString(1), ReviewPresentation.class));
      }
      return presentations;
    } catch (SQLException e) {
      throw failure("Could not read review presentations.", e);
    }
  }

  Optional<CallRecord> call(String callId) {
    try (var statement =
        connection.prepareStatement(
            "SELECT operation, input_digest, output FROM call_records WHERE call_id = ?")) {
      statement.setString(1, callId);
      var rows = statement.executeQuery();
      if (!rows.next()) {
        return Optional.empty();
      }
      return Optional.of(
          new CallRecord(
              callId, rows.getString(1), rows.getString(2), Json.read(rows.getString(3))));
    } catch (SQLException e) {
      throw failure("Could not read call identifiers.", e);
    }
  }

  boolean derivedRecordExists(String inputResource, String version) {
    try (var statement =
        connection.prepareStatement(
            "SELECT count(*) FROM derived_records WHERE input_resource = ? AND version = ?")) {
      statement.setString(1, inputResource);
      statement.setString(2, version);
      var rows = statement.executeQuery();
      rows.next();
      return rows.getLong(1) > 0;
    } catch (SQLException e) {
      throw failure("Could not read derived records.", e);
    }
  }

  @Override
  public void close() {
    try {
      connection.close();
    } catch (SQLException e) {
      throw failure("Could not close the Semantic Catalog storage.", e);
    }
  }

  private static SemanticCatalogException failure(String message, Exception cause) {
    return new SemanticCatalogException(Kind.STORAGE_FAILURE, message, cause);
  }
}
