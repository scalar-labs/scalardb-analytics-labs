/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.duckdb.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.analytics.labs.duckdb.DuckDbQueryEngine;
import com.scalar.db.analytics.labs.duckdb.DuckDbQueryExecutionOptions;
import com.scalar.db.analytics.labs.duckdb.SourceAttachment;
import com.scalar.db.analytics.labs.physical.DataSourceConnection;
import com.scalar.db.analytics.labs.physical.Secret;
import com.scalar.db.analytics.labs.query.QueryEngineException;
import com.scalar.db.analytics.labs.query.QueryError;
import com.scalar.db.analytics.labs.query.QueryResult;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
 * Exercises the scanner query path against real PostgreSQL and MySQL servers.
 *
 * <p>Pushdown and live reads are asserted on the SQL that each server logs, so the tests fail if a
 * query is answered from anything other than the source itself.
 */
@Testcontainers
class ScannerQueryPathTest {
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
  static void prepareSourcesAndStartEngine() throws SQLException {
    try (var connection = postgresConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE orders (id INT PRIMARY KEY, customer_id INT, amount NUMERIC(10, 2), "
              + "note TEXT)");
      statement.execute(
          "INSERT INTO orders SELECT g, g % 3 + 1, g * 1.5, 'order ' || g "
              + "FROM generate_series(1, 1000) AS g");
      statement.execute(
          "CREATE VIEW slow_orders AS SELECT o.* FROM orders AS o, pg_sleep(2) AS pause");
    }
    try (var connection = mysqlConnection(MYSQL_USER, MYSQL_PASSWORD);
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE customers (id INT PRIMARY KEY, name VARCHAR(50), region VARCHAR(10))");
      statement.execute(
          "INSERT INTO customers VALUES (1, 'alice', 'east'), (2, 'bob', 'west'), "
              + "(3, 'carol', 'east')");
    }
    sharedEngine = new DuckDbQueryEngine(null, List.of(postgresSource(), mysqlSource()));
  }

  @AfterAll
  static void closeEngine() {
    engine().close();
  }

  @Test
  void listsTheSchemasAndTablesOfEachAttachedSource() throws Exception {
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
  void queriesEachSourceAndJoinsAcrossSources() throws Exception {
    assertThat(rows(engine().execute("SELECT count(*) FROM warehouse.public.orders")))
        .containsExactly(List.of(1000L));
    assertThat(rows(engine().execute("SELECT name FROM crm.sales.customers ORDER BY id")))
        .containsExactly(List.of("alice"), List.of("bob"), List.of("carol"));

    var totals =
        rows(
            engine()
                .execute(
                    """
                SELECT c.name, count(*) AS orders, sum(o.amount) AS total
                FROM warehouse.public.orders AS o
                JOIN crm.sales.customers AS c ON o.customer_id = c.id
                WHERE c.region = 'east'
                GROUP BY c.name
                ORDER BY c.name
                """));

    // customer_id = id % 3 + 1: alice (1) has the multiples of 3, carol (3) the ids 2 mod 3.
    assertThat(totals)
        .containsExactly(
            List.of("alice", 333L, sumOfAmounts(0)), List.of("carol", 333L, sumOfAmounts(2)));
  }

  @Test
  void readsLiveSourceDataRatherThanACopy() throws Exception {
    var marker = "live-" + UUID.randomUUID();
    var countQuery = "SELECT count(*) FROM warehouse.public.orders WHERE note = '" + marker + "'";
    assertThat(rows(engine().execute(countQuery))).containsExactly(List.of(0L));

    try (var connection = postgresConnection();
        var statement = connection.createStatement()) {
      statement.execute("INSERT INTO orders VALUES (100001, 1, 1.00, '" + marker + "')");
    }
    try {
      assertThat(rows(engine().execute(countQuery))).containsExactly(List.of(1L));
    } finally {
      try (var connection = postgresConnection();
          var statement = connection.createStatement()) {
        statement.execute("DELETE FROM orders WHERE id = 100001");
      }
    }
  }

  @Test
  void pushesProjectionFilterAndLimitIntoPostgreSql() throws Exception {
    var marker = "pushdown-" + UUID.randomUUID();
    var sql =
        "SELECT id FROM warehouse.public.orders WHERE note <> '" + marker + "' AND id > 10 LIMIT 3";

    assertThat(rows(engine().execute(sql))).hasSize(3);

    var received = postgresStatementsContaining(marker);
    assertThat(received).hasSize(1);
    assertThat(received.getFirst())
        .contains("SELECT \"note\", \"id\" FROM \"public\".\"orders\"")
        .contains("WHERE \"note\" <> '" + marker + "'")
        .contains("AND \"id\" > '10'")
        .contains("LIMIT 3")
        .doesNotContain("customer_id", "amount");
  }

  @Test
  void pushesProjectionFilterAndLimitIntoMySql() throws Exception {
    var marker = "pushdown-" + UUID.randomUUID();
    var sql =
        "SELECT name FROM crm.sales.customers WHERE region <> '" + marker + "' AND id > 1 LIMIT 1";

    assertThat(rows(engine().execute(sql))).hasSize(1);

    var received = mysqlStatementsContaining(marker);
    assertThat(received).hasSize(1);
    assertThat(received.getFirst())
        .contains("SELECT `region`, `id`, `name` FROM `sales`.`customers`")
        .contains("WHERE `region` != '" + marker + "'")
        .contains("AND `id` > 1")
        .contains("LIMIT 1");
  }

  @Test
  void enforcesLimitsAndCancellationOnSourceQueries() throws Exception {
    var crossJoin =
        "SELECT count(*) FROM warehouse.public.orders AS a, warehouse.public.orders AS b, "
            + "crm.sales.customers AS c, warehouse.public.orders AS d";

    assertThatThrownBy(
            () ->
                consume(
                    engine()
                        .execute(
                            "SELECT * FROM warehouse.public.orders",
                            new DuckDbQueryExecutionOptions(
                                OptionalLong.of(10),
                                OptionalLong.empty(),
                                Optional.empty(),
                                OptionalLong.empty()))))
        .isInstanceOfSatisfying(
            QueryEngineException.class,
            exception ->
                assertThat(exception.error()).isInstanceOf(QueryError.ResourceLimitExceeded.class));

    try (var execution =
        engine()
            .start(
                crossJoin,
                new DuckDbQueryExecutionOptions(
                    OptionalLong.empty(),
                    OptionalLong.empty(),
                    Optional.of(Duration.ofMillis(200)),
                    OptionalLong.empty()))) {
      assertThatThrownBy(execution::awaitResult)
          .isInstanceOfSatisfying(
              QueryEngineException.class,
              exception -> {
                assertThat(exception.executionId()).isEqualTo(execution.executionId());
                assertThat(exception.error()).isInstanceOf(QueryError.ResourceLimitExceeded.class);
              });
    }

    try (var execution = engine().start(crossJoin, DuckDbQueryExecutionOptions.unlimited());
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var failure =
          executor.submit(
              () -> {
                try {
                  consume(execution.awaitResult());
                  return (Throwable) null;
                } catch (Throwable t) {
                  return t;
                }
              });
      Thread.sleep(200);
      execution.cancel();

      assertThat(failure.get(10, TimeUnit.SECONDS))
          .isInstanceOfSatisfying(
              QueryEngineException.class,
              exception -> assertThat(exception.error()).isInstanceOf(QueryError.Canceled.class));
    }

    assertThat(rows(engine().execute("SELECT count(*) FROM crm.sales.customers")))
        .containsExactly(List.of(3L));
  }

  @Test
  void timeLimitTakesEffectOnceASlowSourceAnswers() throws Exception {
    // DuckDB cannot interrupt a scanner that waits for the source, so the limit applies when the
    // source returns its rows.
    try (var execution =
        engine()
            .start(
                "SELECT * FROM warehouse.public.slow_orders",
                new DuckDbQueryExecutionOptions(
                    OptionalLong.empty(),
                    OptionalLong.empty(),
                    Optional.of(Duration.ofMillis(500)),
                    OptionalLong.empty()))) {
      assertThatThrownBy(() -> consume(execution.awaitResult()))
          .isInstanceOfSatisfying(
              QueryEngineException.class,
              exception ->
                  assertThat(exception.error())
                      .isInstanceOf(QueryError.ResourceLimitExceeded.class));
    }
    assertThat(rows(engine().execute("SELECT count(*) FROM crm.sales.customers")))
        .containsExactly(List.of(3L));
  }

  @Test
  void rejectsQueriesThatBypassTheAttachedSources() throws Exception {
    var connectionString =
        "host=%s port=%d dbname=%s user=%s password=%s"
            .formatted(
                POSTGRES.getHost(),
                POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                POSTGRES.getDatabaseName(),
                POSTGRES.getUsername(),
                POSTGRES_PASSWORD);
    for (var sql :
        List.of(
            "SELECT * FROM postgres_scan('" + connectionString + "', 'public', 'orders')",
            "SELECT * FROM POSTGRES_ATTACH('" + connectionString + "')",
            "SELECT * FROM postgres_query('warehouse', 'SELECT 1')",
            "SELECT * FROM crm.sales.customers WHERE id IN "
                + "(SELECT * FROM mysql_query('crm', 'SELECT 1'))",
            "SELECT (SELECT pg_clear_cache())",
            "SELECT * FROM query('SELECT 1')",
            "SELECT * FROM system.main.query_table('warehouse.public.orders')")) {
      assertThatThrownBy(() -> engine().execute(sql))
          .as(sql)
          .isInstanceOfSatisfying(
              QueryEngineException.class,
              exception -> {
                assertThat(exception.error()).isInstanceOf(QueryError.InvalidQuery.class);
                assertThat(exception.getMessage())
                    .isEqualTo("Sources must be queried through their attached databases.");
              });
    }
    assertThatThrownBy(
            () -> engine().execute("ATTACH '" + connectionString + "' AS other (TYPE postgres)"))
        .isInstanceOfSatisfying(
            QueryEngineException.class,
            exception -> assertThat(exception.error()).isInstanceOf(QueryError.InvalidQuery.class));
  }

  @Test
  void keepsSourcePasswordsOutOfQueryableState() throws Exception {
    var state =
        rows(engine()
                .execute(
                    """
                    SELECT secret_string FROM duckdb_secrets()
                    UNION ALL SELECT coalesce(path, '') FROM duckdb_databases()
                    UNION ALL SELECT coalesce(getvariable('scalardb_analytics_source_password'), '')
                    """))
            .toString();

    assertThat(state).contains("password=redacted").doesNotContain(POSTGRES_PASSWORD);
    assertThat(state).doesNotContain(MYSQL_PASSWORD);
  }

  @Test
  void failsInitializationWithoutExposingConnectionSettings() {
    var wrongPassword = "wrong-" + UUID.randomUUID();
    var rejected = ScannerPath.attachment("warehouse", postgresConnection(wrongPassword));

    assertThatThrownBy(() -> new DuckDbQueryEngine(null, List.of(mysqlSource(), rejected)))
        .isInstanceOfSatisfying(
            QueryEngineException.class,
            exception -> {
              assertThat(exception.error()).isInstanceOf(QueryError.InitializationFailed.class);
              assertThat(exception.getMessage())
                  .isEqualTo("Could not attach the data source warehouse.");
              assertThat(stackTrace(exception))
                  .doesNotContain(
                      wrongPassword,
                      MYSQL_PASSWORD,
                      String.valueOf(POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)));
            });
    assertThat(rejected.toString()).doesNotContain(wrongPassword);
  }

  @Test
  void rejectsSourcesThatCannotBeDistinctDatabases() {
    for (var sources :
        List.of(
            List.of(postgresSource(), mysqlSource("WAREHOUSE")),
            List.of(mysqlSource("memory")),
            List.of(mysqlSource("Information_Schema")))) {
      assertThatThrownBy(() -> new DuckDbQueryEngine(null, sources))
          .isInstanceOfSatisfying(
              QueryEngineException.class,
              exception ->
                  assertThat(exception.error())
                      .isInstanceOf(QueryError.InitializationFailed.class));
    }
  }

  @Test
  void acceptsQueriesThatOnlyNameTheDeniedFunctions() throws Exception {
    var rows =
        rows(
            engine()
                .execute(
                    """
                    WITH postgres_scan AS (SELECT 1 AS query)
                    SELECT query, 'postgres_query()' AS mysql_query,
                           {'function_name': 'query'} AS struct_value
                    FROM postgres_scan
                    """));

    assertThat(rows).hasSize(1);
  }

  private static DuckDbQueryEngine engine() {
    return Objects.requireNonNull(sharedEngine);
  }

  private static BigDecimal sumOfAmounts(int remainder) {
    var total = BigDecimal.ZERO;
    for (int id = 1; id <= 1000; id++) {
      if (id % 3 == remainder) {
        total = total.add(BigDecimal.valueOf(id).multiply(new BigDecimal("1.5")));
      }
    }
    return total.setScale(2);
  }

  private static List<String> postgresStatementsContaining(String marker) {
    return POSTGRES
        .getLogs()
        .lines()
        .filter(line -> line.contains("statement: ") && line.contains(marker))
        .toList();
  }

  private static List<String> mysqlStatementsContaining(String marker) throws SQLException {
    var statements = new ArrayList<String>();
    try (var connection = mysqlConnection("root", MYSQL_ROOT_PASSWORD);
        var statement =
            connection.prepareStatement(
                "SELECT CONVERT(argument USING utf8mb4) FROM mysql.general_log "
                    + "WHERE command_type = 'Execute' AND CONVERT(argument USING utf8mb4) LIKE ?")) {
      statement.setString(1, "%" + marker + "%");
      try (var resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          statements.add(resultSet.getString(1));
        }
      }
    }
    return statements;
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

  private static void consume(QueryResult queryResult) throws Exception {
    try (var result = queryResult) {
      while (result.reader().loadNextBatch()) {}
    }
  }

  private static String stackTrace(Throwable throwable) {
    var output = new StringWriter();
    throwable.printStackTrace(new PrintWriter(output));
    return output.toString();
  }

  private static SourceAttachment postgresSource() {
    return ScannerPath.attachment("warehouse", postgresConnection(POSTGRES_PASSWORD));
  }

  private static SourceAttachment mysqlSource() {
    return mysqlSource("crm");
  }

  private static SourceAttachment mysqlSource(String name) {
    return ScannerPath.attachment(
        name,
        new DataSourceConnection.MySql(
            MYSQL.getHost(),
            MYSQL.getMappedPort(MYSQL_PORT),
            Optional.of(MYSQL_DATABASE),
            MYSQL_USER,
            Secret.of(MYSQL_PASSWORD)));
  }

  private static DataSourceConnection postgresConnection(String password) {
    return new DataSourceConnection.PostgreSql(
        POSTGRES.getHost(),
        POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
        POSTGRES.getDatabaseName(),
        POSTGRES.getUsername(),
        Secret.of(password));
  }

  private static Connection postgresConnection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES_PASSWORD);
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
}
