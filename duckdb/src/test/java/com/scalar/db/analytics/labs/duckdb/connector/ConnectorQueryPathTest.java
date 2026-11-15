/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.duckdb.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.analytics.labs.duckdb.DuckDbQueryEngine;
import com.scalar.db.analytics.labs.duckdb.SourceAttachment;
import com.scalar.db.analytics.labs.physical.DataSourceConnection;
import com.scalar.db.analytics.labs.physical.Secret;
import com.scalar.db.analytics.labs.query.QueryEngineException;
import com.scalar.db.analytics.labs.query.QueryError;
import com.scalar.db.analytics.labs.query.QueryResult;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.apache.arrow.vector.util.Text;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises the connector query path against a real PostgreSQL server: the engine loads the DuckDB
 * extension of the connectors distribution package, which attaches the source through the
 * PostgreSQL Connector.
 *
 * <p>Pushdown is asserted on the SQL the server logs, so the tests fail if a filter is evaluated
 * anywhere other than in the source.
 */
@Testcontainers
class ConnectorQueryPathTest {
  private static final String POSTGRES_PASSWORD = "pg-source-" + UUID.randomUUID();

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16.4")
          .withPassword(POSTGRES_PASSWORD)
          .withCommand("postgres", "-c", "fsync=off", "-c", "log_statement=all");

  private static @Nullable DuckDbQueryEngine sharedEngine;

  @BeforeAll
  static void prepareSourceAndStartEngine() throws Exception {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES_PASSWORD);
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE orders (id INT PRIMARY KEY, customer_id INT, amount NUMERIC(10, 2), "
              + "note TEXT)");
      statement.execute(
          "INSERT INTO orders SELECT g, g % 3 + 1, g * 1.5, 'order ' || g "
              + "FROM generate_series(1, 1000) AS g");
    }
    sharedEngine = new DuckDbQueryEngine(null, List.of(source("warehouse", POSTGRES_PASSWORD)));
  }

  @AfterAll
  static void closeEngine() {
    engine().close();
  }

  @Test
  void listsTheSchemasAndTablesOfTheSource() throws Exception {
    var tables =
        rows(
            engine()
                .execute(
                    """
                SELECT table_catalog, table_schema, table_name
                FROM information_schema.tables
                WHERE table_catalog = 'warehouse'
                """));

    assertThat(tables).contains(List.of("warehouse", "public", "orders"));
  }

  @Test
  void readsTypedValuesAndAggregatesInDuckDb() throws Exception {
    assertThat(rows(engine().execute("SELECT count(*) FROM warehouse.public.orders")))
        .containsExactly(List.of(1000L));
    assertThat(
            rows(
                engine()
                    .execute("SELECT id, amount, note FROM warehouse.public.orders WHERE id = 7")))
        .containsExactly(List.of(7, new BigDecimal("10.50"), "order 7"));
  }

  @Test
  void pushesTheFilterAndProjectionIntoPostgreSql() throws Exception {
    var marker = "pushdown-" + UUID.randomUUID();
    var sql = "SELECT id FROM warehouse.public.orders WHERE note = '" + marker + "' AND id > 10";

    assertThat(rows(engine().execute(sql))).isEmpty();

    var received = postgresStatementsContaining(marker);
    assertThat(received).isNotEmpty();
    assertThat(received.getLast()).contains("orders").doesNotContain("customer_id", "amount");
  }

  @Test
  void rejectsWrites() {
    assertThatThrownBy(
            () -> engine().execute("DELETE FROM warehouse.public.orders WHERE id = 1").close())
        .isInstanceOf(QueryEngineException.class);
  }

  @Test
  void aSourceThatCannotConnectFailsInitializationWithoutTheCredential() {
    var wrongPassword = "wrong-" + UUID.randomUUID();
    // Another engine in the same process loads its own copy of the extension and its library.
    assertThatThrownBy(
            () -> new DuckDbQueryEngine(null, List.of(source("broken", wrongPassword))).close())
        .isInstanceOfSatisfying(
            QueryEngineException.class,
            e -> {
              assertThat(e.error()).isInstanceOf(QueryError.InitializationFailed.class);
              assertThat(e.getMessage()).doesNotContain(wrongPassword);
            });
  }

  @Test
  void theConnectionSettingsStayOutOfTheSecretsTheEngineShows() throws Exception {
    var secrets = rows(engine().execute("SELECT secret_string FROM duckdb_secrets()"));

    assertThat(secrets).isNotEmpty();
    assertThat(secrets.toString()).doesNotContain(POSTGRES_PASSWORD);
  }

  private static DuckDbQueryEngine engine() {
    return Objects.requireNonNull(sharedEngine);
  }

  private static SourceAttachment source(String name, String password) {
    return ConnectorPath.attachment(
            name,
            new DataSourceConnection.PostgreSql(
                POSTGRES.getHost(),
                POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                POSTGRES.getDatabaseName(),
                POSTGRES.getUsername(),
                Secret.of(password)))
        .orElseThrow();
  }

  private static List<String> postgresStatementsContaining(String marker) {
    return POSTGRES
        .getLogs()
        .lines()
        .filter(
            line ->
                (line.contains("statement: ") || line.contains("execute "))
                    && line.contains(marker))
        .toList();
  }

  private static List<List<Object>> rows(QueryResult queryResult) throws Exception {
    var rows = new ArrayList<List<Object>>();
    try (var result = queryResult) {
      var reader = result.reader();
      while (reader.loadNextBatch()) {
        var root = reader.getVectorSchemaRoot();
        for (int row = 0; row < root.getRowCount(); row++) {
          var values = new ArrayList<Object>();
          for (var vector : root.getFieldVectors()) {
            var value = vector.getObject(row);
            values.add(value == null ? "" : value instanceof Text text ? text.toString() : value);
          }
          rows.add(values);
        }
      }
    }
    return rows;
  }
}
