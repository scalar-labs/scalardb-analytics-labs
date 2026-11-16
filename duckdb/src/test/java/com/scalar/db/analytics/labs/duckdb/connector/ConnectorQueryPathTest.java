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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.apache.arrow.vector.util.Text;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises the connector query path against real PostgreSQL and MySQL servers: the engine loads
 * the DuckDB extension of the connectors distribution package, which attaches each source through
 * its Connector.
 *
 * <p>Pushdown is asserted on the SQL the servers log, so the tests fail if a filter is evaluated
 * anywhere other than in the source.
 */
@Testcontainers
class ConnectorQueryPathTest {
  private static final String POSTGRES_PASSWORD = "pg-source-" + UUID.randomUUID();
  private static final String MYSQL_PASSWORD = "mysql-source-" + UUID.randomUUID();
  private static final String MYSQL_ROOT_PASSWORD = "mysql-root-" + UUID.randomUUID();
  private static final String MYSQL_DATABASE = "sales";
  private static final String MYSQL_USER = "analyst";
  private static final int MYSQL_PORT = 3306;

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16.4")
          .withPassword(POSTGRES_PASSWORD)
          .withCommand("postgres", "-c", "fsync=off", "-c", "log_statement=all");

  @Container
  private static final GenericContainer<?> MYSQL =
      new GenericContainer<>("mysql:8.4")
          .withEnv("MYSQL_ROOT_PASSWORD", MYSQL_ROOT_PASSWORD)
          .withEnv("MYSQL_DATABASE", MYSQL_DATABASE)
          .withEnv("MYSQL_USER", MYSQL_USER)
          .withEnv("MYSQL_PASSWORD", MYSQL_PASSWORD)
          .withCommand("--general-log=1", "--log-output=TABLE")
          .withExposedPorts(MYSQL_PORT)
          .waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306 .*", 1));

  private static @Nullable DuckDbQueryEngine sharedEngine;

  @BeforeAll
  static void prepareSourcesAndStartEngine() throws Exception {
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
    try (var connection = mysqlConnection(MYSQL_USER, MYSQL_PASSWORD);
        var statement = connection.createStatement()) {
      // The Connector compares text at MySQL only under a binary collation, so code takes one.
      statement.execute(
          "CREATE TABLE customers (id INT PRIMARY KEY, name VARCHAR(50), region VARCHAR(10), "
              + "code VARCHAR(80) COLLATE utf8mb4_0900_bin, active BOOLEAN)");
      statement.execute(
          "INSERT INTO customers VALUES (1, 'alice', 'east', 'c-1', TRUE), "
              + "(2, 'bob', 'west', 'c-2', FALSE), (3, 'carol', 'east', 'c-3', TRUE)");
    }
    sharedEngine =
        new DuckDbQueryEngine(
            null, List.of(postgresSource("warehouse", POSTGRES_PASSWORD), mysqlSource("crm")));
  }

  @AfterAll
  static void closeEngine() {
    engine().close();
  }

  @Test
  void listsTheSchemasAndTablesOfEachSource() throws Exception {
    var tables =
        rows(
            engine()
                .execute(
                    """
                SELECT table_catalog, table_schema, table_name
                FROM information_schema.tables
                WHERE table_catalog IN ('warehouse', 'crm')
                """));

    assertThat(tables).contains(List.of("warehouse", "public", "orders"));
    assertThat(tables).contains(List.of("crm", "sales", "customers"));
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
  void readsMySqlBooleansDeclaredAsTinyint1AsBoolean() throws Exception {
    assertThat(
            rows(
                engine()
                    .execute(
                        "SELECT name, active, typeof(active) FROM crm.sales.customers"
                            + " ORDER BY id")))
        .containsExactly(
            List.of("alice", true, "BOOLEAN"),
            List.of("bob", false, "BOOLEAN"),
            List.of("carol", true, "BOOLEAN"));
  }

  @Test
  void joinsAcrossSources() throws Exception {
    var totals =
        rows(
            engine()
                .execute(
                    """
                SELECT c.name, count(*) AS orders
                FROM warehouse.public.orders AS o
                JOIN crm.sales.customers AS c ON o.customer_id = c.id
                GROUP BY c.name
                ORDER BY c.name
                """));

    assertThat(totals)
        .containsExactly(List.of("alice", 333L), List.of("bob", 334L), List.of("carol", 333L));
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
  void pushesTheFilterAndProjectionIntoMySql() throws Exception {
    var marker = "pushdown-" + UUID.randomUUID();
    var sql = "SELECT name FROM crm.sales.customers WHERE code <> '" + marker + "' AND id > 1";

    assertThat(rows(engine().execute(sql))).containsExactly(List.of("bob"), List.of("carol"));

    var received = mysqlStatementsContaining(marker);
    assertThat(received).hasSize(1);
    assertThat(received.getFirst())
        .contains("`sales`.`customers`", "`code`", "`id`", "`name`")
        .doesNotContain("`region`", "`active`");
  }

  @Test
  void rejectsWrites() {
    assertThatThrownBy(
            () -> engine().execute("DELETE FROM warehouse.public.orders WHERE id = 1").close())
        .isInstanceOf(QueryEngineException.class);
    assertThatThrownBy(
            () -> engine().execute("DELETE FROM crm.sales.customers WHERE id = 1").close())
        .isInstanceOf(QueryEngineException.class);
  }

  @Test
  void aSourceThatCannotConnectFailsInitializationWithoutTheCredential() {
    var wrongPassword = "wrong-" + UUID.randomUUID();
    // Another engine in the same process loads its own copy of the extension and its library.
    assertThatThrownBy(
            () ->
                new DuckDbQueryEngine(null, List.of(postgresSource("broken", wrongPassword)))
                    .close())
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

    assertThat(secrets).hasSize(2);
    assertThat(secrets.toString()).doesNotContain(POSTGRES_PASSWORD, MYSQL_PASSWORD);
  }

  private static DuckDbQueryEngine engine() {
    return Objects.requireNonNull(sharedEngine);
  }

  private static SourceAttachment postgresSource(String name, String password) {
    return ConnectorPath.attachment(
        name,
        new DataSourceConnection.PostgreSql(
            POSTGRES.getHost(),
            POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
            POSTGRES.getDatabaseName(),
            POSTGRES.getUsername(),
            Secret.of(password)));
  }

  private static SourceAttachment mysqlSource(String name) {
    return ConnectorPath.attachment(
        name,
        new DataSourceConnection.MySql(
            MYSQL.getHost(),
            MYSQL.getMappedPort(MYSQL_PORT),
            Optional.of(MYSQL_DATABASE),
            MYSQL_USER,
            Secret.of(MYSQL_PASSWORD)));
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

  private static List<String> mysqlStatementsContaining(String marker) throws SQLException {
    var statements = new ArrayList<String>();
    try (var connection = mysqlConnection("root", MYSQL_ROOT_PASSWORD);
        var statement =
            connection.prepareStatement(
                "SELECT CONVERT(argument USING utf8mb4) FROM mysql.general_log "
                    + "WHERE command_type IN ('Query', 'Execute') "
                    + "AND CONVERT(argument USING utf8mb4) LIKE ? "
                    + "AND CONVERT(argument USING utf8mb4) NOT LIKE '%general_log%'")) {
      statement.setString(1, "%" + marker + "%");
      try (var resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          statements.add(resultSet.getString(1));
        }
      }
    }
    return statements;
  }

  private static Connection mysqlConnection(String user, String password) throws SQLException {
    return DriverManager.getConnection(
        "jdbc:mariadb://"
            + MYSQL.getHost()
            + ":"
            + MYSQL.getMappedPort(MYSQL_PORT)
            + "/"
            + MYSQL_DATABASE
            + "?sslMode=trust",
        user,
        password);
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
