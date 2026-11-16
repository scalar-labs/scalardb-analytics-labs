/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.construction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.scalar.db.analytics.labs.application.ApplicationRuntime;
import com.scalar.db.analytics.labs.physical.DataSourceConnection;
import com.scalar.db.analytics.labs.physical.PhysicalCatalogSettings;
import com.scalar.db.analytics.labs.physical.Secret;
import com.scalar.db.analytics.labs.semantic.ConstructionSql;
import com.scalar.db.analytics.labs.semantic.ConstructionSql.ConstructionSqlException;
import com.scalar.db.analytics.labs.semantic.ConstructionSql.Extent;
import com.scalar.db.analytics.labs.semantic.ConstructionSql.Input;
import com.scalar.db.analytics.labs.semantic.ConstructionSql.Outcome;
import com.scalar.db.analytics.labs.semantic.ConstructionSql.Reading;
import com.scalar.db.analytics.labs.semantic.ConstructionSql.Request;
import com.scalar.db.analytics.labs.semantic.model.PhysicalCatalogReference;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Construction-run SQL as a build task uses it, over a PostgreSQL and a MySQL source, both read
 * through their Connectors.
 */
@Testcontainers
class EngineConstructionSqlIntegrationTest {
  private static final String POSTGRES_PASSWORD = "pg-source-" + UUID.randomUUID();
  private static final String MYSQL_PASSWORD = "mysql-source-" + UUID.randomUUID();
  private static final String MYSQL_DATABASE = "sales";
  private static final String MYSQL_USER = "analyst";
  private static final int MYSQL_PORT = 3306;
  private static final long SAMPLE_SIZE = 100;
  private static final int EVENTS = 500;
  private static final int MEMBERS = 10;

  private static final PhysicalCatalogReference.Table EVENTS_TABLE =
      new PhysicalCatalogReference.Table("warehouse", "public", "events");
  private static final PhysicalCatalogReference.Table MEMBERS_TABLE =
      new PhysicalCatalogReference.Table("warehouse", "public", "members");
  private static final PhysicalCatalogReference.Table CUSTOMERS_TABLE =
      new PhysicalCatalogReference.Table("crm", MYSQL_DATABASE, "customers");

  @Container
  private static final PostgreSQLContainer<?> STORE = new PostgreSQLContainer<>("postgres:16.4");

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16.4").withPassword(POSTGRES_PASSWORD);

  @Container
  private static final GenericContainer<?> MYSQL =
      new GenericContainer<>("mysql:8.4")
          .withEnv("MYSQL_ROOT_PASSWORD", "root-" + UUID.randomUUID())
          .withEnv("MYSQL_DATABASE", MYSQL_DATABASE)
          .withEnv("MYSQL_USER", MYSQL_USER)
          .withEnv("MYSQL_PASSWORD", MYSQL_PASSWORD)
          .withExposedPorts(MYSQL_PORT)
          .waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306 .*", 1));

  private ApplicationRuntime runtime;
  private String catalogName;

  @BeforeAll
  static void prepareSources() throws SQLException {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES_PASSWORD);
        var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE members (id INT PRIMARY KEY)");
      statement.execute("INSERT INTO members SELECT g FROM generate_series(1, " + MEMBERS + ") g");
      // Every event's member exists, and each member has many events.
      statement.execute("CREATE TABLE events (id INT PRIMARY KEY, member_id INT)");
      statement.execute(
          "INSERT INTO events SELECT g, 1 + g % "
              + MEMBERS
              + " FROM generate_series(1, "
              + EVENTS
              + ") g");
    }
    try (var connection =
            DriverManager.getConnection(
                "jdbc:mariadb://"
                    + MYSQL.getHost()
                    + ":"
                    + MYSQL.getMappedPort(MYSQL_PORT)
                    + "/"
                    + MYSQL_DATABASE
                    + "?sslMode=trust",
                MYSQL_USER,
                MYSQL_PASSWORD);
        var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE customers (id INT PRIMARY KEY, name VARCHAR(50))");
      statement.execute("INSERT INTO customers VALUES (1, 'alice'), (2, 'bob')");
    }
  }

  @BeforeEach
  void registerSources() {
    catalogName = "catalog_" + UUID.randomUUID().toString().replace("-", "");
    runtime =
        ApplicationRuntime.start(
            new PhysicalCatalogSettings(
                STORE.getJdbcUrl(),
                Optional.of(STORE.getUsername()),
                Secret.of(STORE.getPassword())));
    var catalog = runtime.physicalCatalog();
    catalog.createCatalog(catalogName);
    catalog.registerDataSource(
        catalogName,
        "warehouse",
        new DataSourceConnection.PostgreSql(
            POSTGRES.getHost(),
            POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
            POSTGRES.getDatabaseName(),
            POSTGRES.getUsername(),
            Secret.of(POSTGRES_PASSWORD)));
    catalog.registerDataSource(
        catalogName,
        "crm",
        new DataSourceConnection.MySql(
            MYSQL.getHost(),
            MYSQL.getMappedPort(MYSQL_PORT),
            Optional.of(MYSQL_DATABASE),
            MYSQL_USER,
            Secret.of(MYSQL_PASSWORD)));
  }

  @AfterEach
  void stopRuntime() {
    runtime.close();
  }

  private ConstructionSql constructionSql(Duration timeLimit) {
    return new EngineConstructionSql(
        runtime, catalogName, new ConstructionSqlSettings(SAMPLE_SIZE, timeLimit));
  }

  private ConstructionSql constructionSql() {
    return constructionSql(Duration.ofSeconds(60));
  }

  @Test
  void samplesTheCheckedSideAndLooksUpTheOtherSideWhole() {
    var outcome =
        constructionSql()
            .execute(
                new Request(
                    """
                    SELECT count(*) AS read, count(*) FILTER (WHERE m.id IS NULL) AS unmatched
                    FROM warehouse.public.events AS e
                    LEFT JOIN warehouse.public.members AS m ON e.member_id = m.id
                    """,
                    List.of(
                        new Input(EVENTS_TABLE, Reading.SAMPLED),
                        new Input(MEMBERS_TABLE, Reading.WHOLE))));

    var read = (Outcome.Read) outcome;
    assertThat(read.columns()).containsExactly("read", "unmatched");
    assertThat(read.rows()).hasSize(1);
    assertThat(read.rows().get(0).get(0).asLong()).isEqualTo(SAMPLE_SIZE);
    assertThat(read.rows().get(0).get(1).asLong()).isZero();
    assertThat(read.ranges())
        .extracting(ConstructionSql.Range::table, ConstructionSql.Range::extent, r -> r.rowsRead())
        .containsExactly(
            tuple(EVENTS_TABLE, Extent.SAMPLE, SAMPLE_SIZE),
            tuple(MEMBERS_TABLE, Extent.WHOLE_TABLE, (long) MEMBERS));
    assertThat(read.ranges().get(0).readAt()).isEqualTo(read.ranges().get(1).readAt());
    assertThat(read.sources()).hasSize(1);
    var source = read.sources().get(0);
    assertThat(source.registration()).isEqualTo("warehouse");
    assertThat(source.extension()).isEqualTo("scalardb_analytics_connectors");
  }

  @Test
  void readsASampledTableWithinTheSampleSizeWhole() {
    var read =
        (Outcome.Read)
            constructionSql()
                .execute(
                    new Request(
                        "SELECT count(*) FROM warehouse.public.members",
                        List.of(new Input(MEMBERS_TABLE, Reading.SAMPLED))));

    assertThat(read.rows().get(0).get(0).asLong()).isEqualTo(MEMBERS);
    assertThat(read.ranges().get(0).extent()).isEqualTo(Extent.WHOLE_TABLE);
    assertThat(read.ranges().get(0).rowsRead()).isEqualTo(MEMBERS);
  }

  @Test
  void samplesEveryReferenceToASampledTableAlike() {
    var read =
        (Outcome.Read)
            constructionSql()
                .execute(
                    new Request(
                        """
                        WITH sampled AS (SELECT id FROM warehouse.public.events)
                        SELECT count(*) FROM sampled
                        WHERE id IN (SELECT id FROM warehouse.public.events)
                        """,
                        List.of(new Input(EVENTS_TABLE, Reading.SAMPLED))));

    // Both references read the same rows, so every sampled id is found in the other one.
    assertThat(read.rows().get(0).get(0).asLong()).isEqualTo(SAMPLE_SIZE);
  }

  @Test
  void readsAMySqlSourceThroughItsConnector() {
    var read =
        (Outcome.Read)
            constructionSql()
                .execute(
                    new Request(
                        "SELECT count(*) FROM crm.sales.customers",
                        List.of(new Input(CUSTOMERS_TABLE, Reading.WHOLE))));

    assertThat(read.rows().get(0).get(0).asLong()).isEqualTo(2);
    var source = read.sources().get(0);
    assertThat(source.registration()).isEqualTo("crm");
    assertThat(source.extension()).isEqualTo("scalardb_analytics_connectors");
  }

  @Test
  void rejectsAStatementThatReadsAnUndeclaredTable() {
    var sql = constructionSql();

    assertThatThrownBy(
            () ->
                sql.execute(
                    new Request(
                        """
                        SELECT count(*) FROM warehouse.public.events
                        WHERE member_id IN (SELECT id FROM warehouse.public.members)
                        """,
                        List.of(new Input(EVENTS_TABLE, Reading.SAMPLED)))))
        .isInstanceOf(ConstructionSqlException.class)
        .hasMessageContaining("warehouse.public.members");
    assertThatThrownBy(
            () ->
                sql.execute(
                    new Request(
                        "SELECT count(*) FROM warehouse.public.events, range(3)",
                        List.of(new Input(EVENTS_TABLE, Reading.WHOLE)))))
        .isInstanceOf(ConstructionSqlException.class)
        .hasMessageContaining("table functions");
    // A common table expression defined in a subquery does not cover the same name outside it,
    // where DuckDB binds the name to its system view instead.
    assertThatThrownBy(
            () ->
                sql.execute(
                    new Request(
                        """
                        SELECT table_name FROM warehouse.public.events, duckdb_tables
                        WHERE id = (WITH duckdb_tables AS (SELECT 1) SELECT 1)
                        """,
                        List.of(new Input(EVENTS_TABLE, Reading.WHOLE)))))
        .isInstanceOf(ConstructionSqlException.class)
        .hasMessageContaining("duckdb_tables");
    assertThatThrownBy(
            () ->
                sql.execute(
                    new Request(
                        "SELECT count(*) FROM warehouse.public.events",
                        List.of(
                            new Input(EVENTS_TABLE, Reading.WHOLE),
                            new Input(MEMBERS_TABLE, Reading.WHOLE)))))
        .isInstanceOf(ConstructionSqlException.class)
        .hasMessageContaining("does not read the declared table warehouse.public.members");
  }

  @Test
  void rejectsADeclaredTableThatIsNotRegistered() {
    assertThatThrownBy(
            () ->
                constructionSql()
                    .execute(
                        new Request(
                            "SELECT count(*) FROM warehouse.public.missing",
                            List.of(
                                new Input(
                                    new PhysicalCatalogReference.Table(
                                        "warehouse", "public", "missing"),
                                    Reading.WHOLE)))))
        .isInstanceOf(ConstructionSqlException.class)
        .hasMessageContaining("is not registered");
  }

  @Test
  void leavesAStatementOverTheRowLimitUndecidable() {
    var rows =
        constructionSql()
            .execute(
                new Request(
                    "SELECT a.id, b.id FROM warehouse.public.events AS a, warehouse.public.events"
                        + " AS b",
                    List.of(new Input(EVENTS_TABLE, Reading.WHOLE))));
    assertThat(rows).isInstanceOf(Outcome.LimitExceeded.class);
  }

  @Test
  void leavesAStatementOverTheByteLimitUndecidable() {
    // 500 rows stay under the row limit, but a megabyte each exceeds the byte limit.
    var outcome =
        constructionSql()
            .execute(
                new Request(
                    "SELECT repeat('x', 1000000) FROM warehouse.public.events",
                    List.of(new Input(EVENTS_TABLE, Reading.WHOLE))));

    assertThat(outcome).isInstanceOf(Outcome.LimitExceeded.class);
  }

  @Test
  void leavesAStatementOverTheTimeLimitUndecidable() {
    // The counts finish at once; the statement's cross join runs in DuckDB well past the limit.
    var outcome =
        constructionSql(Duration.ofSeconds(3))
            .execute(
                new Request(
                    """
                    SELECT count(*)
                    FROM warehouse.public.events AS a, warehouse.public.events AS b,
                      warehouse.public.events AS c, warehouse.public.events AS d
                    WHERE a.id + b.id + c.id + d.id < 0
                    """,
                    List.of(new Input(EVENTS_TABLE, Reading.WHOLE))));

    assertThat(outcome).isInstanceOf(Outcome.LimitExceeded.class);
  }
}
