/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.analytics.labs.physical.DataSourceConnection;
import com.scalar.db.analytics.labs.physical.PhysicalCatalogSettings;
import com.scalar.db.analytics.labs.physical.Secret;
import com.scalar.db.analytics.labs.query.QueryEngineException;
import com.scalar.db.analytics.labs.query.QueryError;
import com.scalar.db.analytics.labs.query.QueryResult;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.arrow.vector.util.Text;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ApplicationRuntimeTest {
  private static final String POSTGRES_PASSWORD = "pg-source-" + UUID.randomUUID();
  private static final String MYSQL_PASSWORD = "mysql-source-" + UUID.randomUUID();
  private static final String MYSQL_DATABASE = "sales";
  private static final String MYSQL_USER = "analyst";
  private static final int MYSQL_PORT = 3306;

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

  @BeforeAll
  static void prepareSources() throws SQLException {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES_PASSWORD);
        var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE orders (id INT PRIMARY KEY, customer_id INT, amount INT)");
      statement.execute("INSERT INTO orders VALUES (1, 1, 10), (2, 1, 20), (3, 2, 5)");
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

  @Test
  void queriesSourcesResolvedThroughThePhysicalCatalog() throws Exception {
    var catalogName = newCatalogName();
    try (var runtime = ApplicationRuntime.start(storeSettings())) {
      var catalog = runtime.physicalCatalog();
      catalog.createCatalog(catalogName);
      catalog.registerDataSource(catalogName, "warehouse", postgresConnection(POSTGRES_PASSWORD));
      catalog.registerDataSource(catalogName, "crm", mysqlConnection());
    }

    // A new process sees the sources through the shared catalog store.
    try (var runtime = ApplicationRuntime.start(storeSettings())) {
      var queryEngine = runtime.startQueryEngine(catalogName);

      assertThat(
              rows(
                  queryEngine.execute(
                      """
                      SELECT c.name, sum(o.amount)::BIGINT AS total
                      FROM warehouse.public.orders AS o
                      JOIN crm.sales.customers AS c ON o.customer_id = c.id
                      GROUP BY c.name
                      ORDER BY c.name
                      """)))
          .containsExactly(List.of("alice", 30L), List.of("bob", 5L));
      assertThat(rows(queryEngine.execute("SELECT scalardb_analytics_catalog_status()")))
          .containsExactly(List.of("SERVING"));
      assertThatThrownBy(() -> runtime.startQueryEngine(catalogName))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void queriesThroughTheConnectorAfterAnOperationUsedTheRuntimesConnectorLibrary()
      throws Exception {
    var catalogName = newCatalogName();
    for (int run = 0; run < 2; run++) {
      try (var runtime = ApplicationRuntime.start(storeSettings())) {
        var catalog = runtime.physicalCatalog();
        if (run == 0) {
          catalog.createCatalog(catalogName);
          catalog.registerDataSource(
              catalogName, "warehouse", postgresConnection(POSTGRES_PASSWORD));
        }
        // An operation such as table metadata retrieval initializes the library first; the
        // engine's connectors extension then loads into the process that owns it.
        runtime.connectorLibrary();
        var queryEngine = runtime.startQueryEngine(catalogName);

        // The second run sees a library shut down by the first runtime and initializes it again.
        assertThat(
                rows(
                    queryEngine.execute("SELECT sum(amount)::BIGINT FROM warehouse.public.orders")))
            .containsExactly(List.of(35L));
      }
    }
  }

  @Test
  void reportsAnUnattachableSourceWithoutItsCredentials() {
    var catalogName = newCatalogName();
    try (var runtime = ApplicationRuntime.start(storeSettings())) {
      var catalog = runtime.physicalCatalog();
      catalog.createCatalog(catalogName);
      catalog.registerDataSource(catalogName, "warehouse", postgresConnection(POSTGRES_PASSWORD));
    }
    var changedPassword = "changed-" + UUID.randomUUID();
    alterPostgresPassword(changedPassword, POSTGRES_PASSWORD);
    try (var runtime = ApplicationRuntime.start(storeSettings())) {
      assertThatThrownBy(() -> runtime.startQueryEngine(catalogName))
          .isInstanceOfSatisfying(
              QueryEngineException.class,
              exception -> {
                assertThat(exception.error()).isInstanceOf(QueryError.InitializationFailed.class);
                assertThat(exception.getMessage())
                    .isEqualTo("Could not attach the data source warehouse.");
                assertThat(stackTrace(exception))
                    .doesNotContain(POSTGRES_PASSWORD, changedPassword);
              });
    } finally {
      alterPostgresPassword(POSTGRES_PASSWORD, changedPassword);
    }
  }

  private static void alterPostgresPassword(String newPassword, String currentPassword) {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), currentPassword);
        var statement = connection.createStatement()) {
      statement.execute(
          "ALTER USER " + POSTGRES.getUsername() + " WITH PASSWORD '" + newPassword + "'");
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static PhysicalCatalogSettings storeSettings() {
    return new PhysicalCatalogSettings(
        STORE.getJdbcUrl(), Optional.of(STORE.getUsername()), Secret.of(STORE.getPassword()));
  }

  private static String newCatalogName() {
    return "catalog_" + UUID.randomUUID().toString().replace("-", "");
  }

  private static DataSourceConnection postgresConnection(String password) {
    return new DataSourceConnection.PostgreSql(
        POSTGRES.getHost(),
        POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
        POSTGRES.getDatabaseName(),
        POSTGRES.getUsername(),
        Secret.of(password));
  }

  private static DataSourceConnection mysqlConnection() {
    return new DataSourceConnection.MySql(
        MYSQL.getHost(),
        MYSQL.getMappedPort(MYSQL_PORT),
        Optional.of(MYSQL_DATABASE),
        MYSQL_USER,
        Secret.of(MYSQL_PASSWORD));
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
            values.add(value instanceof Text text ? text.toString() : value);
          }
          rows.add(values);
        }
      }
    }
    return rows;
  }

  private static String stackTrace(Throwable throwable) {
    var output = new StringWriter();
    throwable.printStackTrace(new PrintWriter(output));
    return output.toString();
  }
}
