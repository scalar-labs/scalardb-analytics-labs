/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.physical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.rpc.Status;
import com.scalar.db.analytics.grpc.generated.datasource.v1.DataSourceServiceGrpc;
import com.scalar.db.analytics.grpc.generated.datasource.v1.ListDataSourcesRequest;
import com.scalar.db.analytics.grpc.generated.datasource.v1.ListDataSourcesResponse;
import com.scalar.db.analytics.grpc.generated.datasource.v1.RegisterRequest;
import com.scalar.db.analytics.grpc.generated.table.v1.ListTablesRequest;
import com.scalar.db.analytics.grpc.generated.table.v1.ListTablesResponse;
import com.scalar.db.analytics.grpc.generated.table.v1.TableServiceGrpc;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
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

@Testcontainers
class PhysicalCatalogTest {
  private static final String POSTGRES_IMAGE = "postgres:16.4";
  private static final String MYSQL_IMAGE = "mysql:8.4";
  private static final String POSTGRES_SOURCE_PASSWORD = "pg-source-" + UUID.randomUUID();
  private static final String MYSQL_SOURCE_PASSWORD = "mysql-source-" + UUID.randomUUID();
  private static final String MYSQL_DATABASE = "sales";
  private static final String MYSQL_USER = "analyst";
  private static final int MYSQL_PORT = 3306;

  @Container
  private static final PostgreSQLContainer<?> STORE = new PostgreSQLContainer<>(POSTGRES_IMAGE);

  @Container
  private static final PostgreSQLContainer<?> POSTGRES_SOURCE =
      new PostgreSQLContainer<>(POSTGRES_IMAGE).withPassword(POSTGRES_SOURCE_PASSWORD);

  @Container
  private static final GenericContainer<?> MYSQL_SOURCE =
      new GenericContainer<>(MYSQL_IMAGE)
          .withEnv("MYSQL_ROOT_PASSWORD", "root-" + UUID.randomUUID())
          .withEnv("MYSQL_DATABASE", MYSQL_DATABASE)
          .withEnv("MYSQL_USER", MYSQL_USER)
          .withEnv("MYSQL_PASSWORD", MYSQL_SOURCE_PASSWORD)
          .withExposedPorts(MYSQL_PORT)
          .waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306 .*", 1));

  private String catalogName = "";
  private PhysicalCatalog catalog;

  @BeforeAll
  static void createSourceTables() throws SQLException {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES_SOURCE.getJdbcUrl(),
                POSTGRES_SOURCE.getUsername(),
                POSTGRES_SOURCE_PASSWORD);
        var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE IF NOT EXISTS orders (id INT PRIMARY KEY, amount NUMERIC)");
    }
    try (var connection =
            DriverManager.getConnection(
                "jdbc:mariadb://"
                    + MYSQL_SOURCE.getHost()
                    + ":"
                    + MYSQL_SOURCE.getMappedPort(MYSQL_PORT)
                    + "/"
                    + MYSQL_DATABASE
                    + "?sslMode=trust",
                MYSQL_USER,
                MYSQL_SOURCE_PASSWORD);
        var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE IF NOT EXISTS customers (id INT PRIMARY KEY, name TEXT)");
    }
  }

  @BeforeEach
  void startCatalog() {
    catalog = PhysicalCatalog.start(storeSettings());
    catalogName = "catalog_" + UUID.randomUUID().toString().replace("-", "");
    catalog.createCatalog(catalogName);
  }

  @AfterEach
  void closeCatalog() {
    catalog.close();
  }

  @Test
  void registersAndResolvesPostgreSqlAndMySqlSources() {
    var postgres = catalog.registerDataSource(catalogName, "warehouse", postgresConnection());
    var mysql = catalog.registerDataSource(catalogName, "crm", mysqlConnection());

    assertThat(postgres)
        .isEqualTo(
            new DataSourceDescriptor(
                catalogName,
                "warehouse",
                DataSourceDescriptor.Type.POSTGRESQL,
                POSTGRES_SOURCE.getHost(),
                POSTGRES_SOURCE.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                Optional.of(POSTGRES_SOURCE.getDatabaseName()),
                POSTGRES_SOURCE.getUsername()));
    assertThat(mysql.type()).isEqualTo(DataSourceDescriptor.Type.MYSQL);
    assertThat(mysql.database()).contains(MYSQL_DATABASE);
    assertThat(catalog.findDataSource(catalogName, "warehouse")).contains(postgres);
    assertThat(catalog.findDataSource(catalogName, "crm")).contains(mysql);
    assertThat(catalog.findDataSource(catalogName, "missing")).isEmpty();
    assertThat(catalog.listDataSources(catalogName)).containsExactlyInAnyOrder(postgres, mysql);
    assertNoSecret(postgres.toString() + mysql.toString());
  }

  @Test
  void resolvesConnectionsWithCredentialsForApplicationCode() {
    catalog.registerDataSource(catalogName, "warehouse", postgresConnection());
    catalog.registerDataSource(catalogName, "crm", mysqlConnection());

    var postgres = catalog.resolveConnection(catalogName, "warehouse").orElseThrow();
    var mysql = catalog.resolveConnection(catalogName, "crm").orElseThrow();

    assertThat(postgres)
        .isInstanceOfSatisfying(
            DataSourceConnection.PostgreSql.class,
            connection -> {
              assertThat(connection.host()).isEqualTo(POSTGRES_SOURCE.getHost());
              assertThat(connection.password().reveal()).isEqualTo(POSTGRES_SOURCE_PASSWORD);
            });
    assertThat(mysql)
        .isInstanceOfSatisfying(
            DataSourceConnection.MySql.class,
            connection -> {
              assertThat(connection.database()).contains(MYSQL_DATABASE);
              assertThat(connection.password().reveal()).isEqualTo(MYSQL_SOURCE_PASSWORD);
            });
    assertThat(catalog.resolveConnection(catalogName, "missing")).isEmpty();
    assertNoSecret(postgres.toString() + mysql.toString());
  }

  @Test
  void boundsSerializedCallsWhenTheStoreStopsAnswering() throws Exception {
    var settings =
        new PhysicalCatalogSettings(
            STORE.getJdbcUrl(),
            Optional.of(STORE.getUsername()),
            Secret.of(STORE.getPassword()),
            Duration.ofSeconds(1));
    try (var bounded = PhysicalCatalog.start(settings)) {
      var docker = STORE.getDockerClient();
      docker.pauseContainerCmd(STORE.getContainerId()).exec();
      try {
        var started = System.nanoTime();

        var result =
            bounded.callSerialized(
                DataSourceServiceGrpc.getListDataSourcesMethod().getFullMethodName(),
                ListDataSourcesRequest.newBuilder()
                    .setCatalogName(catalogName)
                    .build()
                    .toByteArray());

        assertThat(failureStatus(result).getCode())
            .isEqualTo(io.grpc.Status.Code.DEADLINE_EXCEEDED.value());
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
      } finally {
        docker.unpauseContainerCmd(STORE.getContainerId()).exec();
      }
    }
  }

  @Test
  void keepsStateInTheStoreAcrossCatalogInstances() {
    var registered = catalog.registerDataSource(catalogName, "warehouse", postgresConnection());
    catalog.close();

    catalog = PhysicalCatalog.start(storeSettings());

    assertThat(catalog.findDataSource(catalogName, "warehouse")).contains(registered);
  }

  @Test
  void serializedCallsReturnResolvedMetadataWithoutCredentials() throws Exception {
    catalog.registerDataSource(catalogName, "warehouse", postgresConnection());
    catalog.registerDataSource(catalogName, "crm", mysqlConnection());

    var dataSources =
        catalog.callSerialized(
            DataSourceServiceGrpc.getListDataSourcesMethod().getFullMethodName(),
            ListDataSourcesRequest.newBuilder().setCatalogName(catalogName).build().toByteArray());
    var tables =
        catalog.callSerialized(
            TableServiceGrpc.getListTablesMethod().getFullMethodName(),
            ListTablesRequest.newBuilder().setCatalogName(catalogName).build().toByteArray());

    var dataSourcesBytes = responseBytes(dataSources);
    var tablesBytes = responseBytes(tables);
    assertThat(ListDataSourcesResponse.parseFrom(dataSourcesBytes).getDataSourcesList())
        .extracting(dataSource -> dataSource.getName())
        .containsExactlyInAnyOrder("warehouse", "crm");
    // Schema resolution connected to both sources and recorded their tables.
    assertThat(ListTablesResponse.parseFrom(tablesBytes).getTablesList())
        .extracting(table -> table.getTable().getInfo().getName())
        .contains("orders", "customers");
    assertNoSecret(new String(dataSourcesBytes, StandardCharsets.ISO_8859_1));
    assertNoSecret(new String(tablesBytes, StandardCharsets.ISO_8859_1));
  }

  @Test
  void exposesTypedNamespacesTablesAndColumnsWithoutCredentials() {
    catalog.registerDataSource(catalogName, "warehouse", postgresConnection());
    catalog.registerDataSource(catalogName, "crm", mysqlConnection());

    assertThat(catalog.listNamespaces(catalogName, "warehouse"))
        .extracting(PhysicalNamespace::names)
        .contains(List.of("public"));
    assertThat(catalog.listNamespaces(catalogName, "crm"))
        .extracting(PhysicalNamespace::names)
        .contains(List.of(MYSQL_DATABASE));
    assertThat(catalog.listTables(catalogName, "warehouse"))
        .anySatisfy(
            table -> {
              assertThat(table.name()).isEqualTo("orders");
              assertThat(table.namespaceNames()).containsExactly("public");
              assertThat(table.columns()).extracting(PhysicalTable.Column::name).contains("id");
            });
    assertThat(catalog.listTables(catalogName, "crm"))
        .anySatisfy(
            table -> {
              assertThat(table.name()).isEqualTo("customers");
              assertThat(table.namespaceNames()).containsExactly(MYSQL_DATABASE);
              assertThat(table.columns()).extracting(PhysicalTable.Column::name).contains("id");
            });
  }

  @Test
  void serializedCallsRefuseMethodsThatModifyState() throws Exception {
    var request =
        RegisterRequest.newBuilder()
            .setCatalogName(catalogName)
            .setDataSourceName("warehouse")
            .setProviderPayloadJson(ProviderPayloads.toPayload(postgresConnection()))
            .build();

    var result =
        catalog.callSerialized(
            DataSourceServiceGrpc.getRegisterMethod().getFullMethodName(), request.toByteArray());

    assertThat(failureStatus(result).getCode())
        .isEqualTo(io.grpc.Status.Code.PERMISSION_DENIED.value());
    assertThat(catalog.findDataSource(catalogName, "warehouse")).isEmpty();
  }

  @Test
  void reportsUnreachableSourcesWithoutCredentials() {
    var connection =
        new DataSourceConnection.PostgreSql(
            POSTGRES_SOURCE.getHost(),
            POSTGRES_SOURCE.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
            POSTGRES_SOURCE.getDatabaseName(),
            POSTGRES_SOURCE.getUsername(),
            Secret.of(POSTGRES_SOURCE_PASSWORD + "-wrong"));

    assertThatThrownBy(() -> catalog.registerDataSource(catalogName, "warehouse", connection))
        .isInstanceOfSatisfying(
            PhysicalCatalogException.class,
            e -> {
              assertThat(e.error()).isInstanceOf(PhysicalCatalogError.DataSourceUnreachable.class);
              assertThat(e.getCause()).isNull();
              assertNoSecret(stackTrace(e));
            });
    assertNoSecret(connection.toString());
  }

  @Test
  void reportsDuplicateRegistration() {
    catalog.registerDataSource(catalogName, "warehouse", postgresConnection());

    assertThatThrownBy(
            () -> catalog.registerDataSource(catalogName, "warehouse", postgresConnection()))
        .isInstanceOfSatisfying(
            PhysicalCatalogException.class,
            e -> assertThat(e.error()).isInstanceOf(PhysicalCatalogError.AlreadyExists.class));
  }

  @Test
  void failsCallsAfterClosing() throws Exception {
    catalog.close();

    var result =
        catalog.callSerialized(
            HealthGrpc.getCheckMethod().getFullMethodName(),
            HealthCheckRequest.getDefaultInstance().toByteArray());

    assertThat(failureStatus(result).getCode()).isEqualTo(io.grpc.Status.Code.UNAVAILABLE.value());
    assertThatThrownBy(() -> catalog.listDataSources(catalogName))
        .isInstanceOfSatisfying(
            PhysicalCatalogException.class,
            e -> assertThat(e.error()).isInstanceOf(PhysicalCatalogError.Unavailable.class));
    catalog = PhysicalCatalog.start(storeSettings());
  }

  @Test
  void servesHealthChecks() throws Exception {
    var result =
        catalog.callSerialized(
            HealthGrpc.getCheckMethod().getFullMethodName(),
            HealthCheckRequest.getDefaultInstance().toByteArray());

    assertThat(HealthCheckResponse.parseFrom(responseBytes(result)).getStatus())
        .isEqualTo(HealthCheckResponse.ServingStatus.SERVING);
  }

  @Test
  void failsToStartWithAnUnreachableStore() {
    var settings =
        new PhysicalCatalogSettings(
            "jdbc:postgresql://127.0.0.1:1/missing",
            Optional.of("nobody"),
            Secret.of(POSTGRES_SOURCE_PASSWORD));

    assertThatThrownBy(() -> PhysicalCatalog.start(settings))
        .isInstanceOfSatisfying(
            PhysicalCatalogException.class,
            e -> {
              assertThat(e.error()).isInstanceOf(PhysicalCatalogError.InitializationFailed.class);
              assertNoSecret(stackTrace(e));
            });
  }

  private static PhysicalCatalogSettings storeSettings() {
    return new PhysicalCatalogSettings(
        STORE.getJdbcUrl(), Optional.of(STORE.getUsername()), Secret.of(STORE.getPassword()));
  }

  private static DataSourceConnection postgresConnection() {
    return new DataSourceConnection.PostgreSql(
        POSTGRES_SOURCE.getHost(),
        POSTGRES_SOURCE.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
        POSTGRES_SOURCE.getDatabaseName(),
        POSTGRES_SOURCE.getUsername(),
        Secret.of(POSTGRES_SOURCE_PASSWORD));
  }

  private static DataSourceConnection mysqlConnection() {
    return new DataSourceConnection.MySql(
        MYSQL_SOURCE.getHost(),
        MYSQL_SOURCE.getMappedPort(MYSQL_PORT),
        Optional.of(MYSQL_DATABASE),
        MYSQL_USER,
        Secret.of(MYSQL_SOURCE_PASSWORD));
  }

  private static byte[] responseBytes(SerializedCallResult result) throws Exception {
    if (result instanceof SerializedCallResult.Response response) {
      return response.message();
    }
    throw new AssertionError("Expected a response but got " + failureStatus(result).getMessage());
  }

  private static Status failureStatus(SerializedCallResult result) throws Exception {
    if (result instanceof SerializedCallResult.Failure failure) {
      var status = Status.parseFrom(failure.status());
      assertNoSecret(status.toString());
      return status;
    }
    throw new AssertionError("Expected a failure");
  }

  private static void assertNoSecret(String text) {
    assertThat(text).doesNotContain(POSTGRES_SOURCE_PASSWORD).doesNotContain(MYSQL_SOURCE_PASSWORD);
  }

  private static String stackTrace(Throwable throwable) {
    var writer = new StringWriter();
    throwable.printStackTrace(new PrintWriter(writer));
    return writer.toString();
  }
}
