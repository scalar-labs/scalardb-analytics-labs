/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalar.db.analytics.labs.application.workspace.Workspace;
import com.scalar.db.analytics.labs.semantic.SemanticCatalogStore;
import com.scalar.db.analytics.labs.semantic.StoreSettings;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ScalarDbAnalyticsCliIntegrationTest {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final String POSTGRES_PASSWORD = "pg-cli-" + UUID.randomUUID();
  private static final String MYSQL_PASSWORD = "mysql-cli-" + UUID.randomUUID();
  private static final String MYSQL_DATABASE = "sales";
  private static final String MYSQL_USER = "analyst";
  private static final int MYSQL_PORT = 3306;

  @Container
  private static final PostgreSQLContainer<?> STORE =
      new PostgreSQLContainer<>("postgres:16.4").withPassword("store-" + UUID.randomUUID());

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

  @TempDir Path temporaryDirectory;

  private Path workspace;
  private String catalogName;
  private Map<String, String> environment;
  private Result postgresRegistration;
  private Result mysqlRegistration;

  @BeforeAll
  static void prepareSources() throws SQLException {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES_PASSWORD);
        var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE orders (id INT PRIMARY KEY, customer_id INT, amount INT)");
      statement.execute("INSERT INTO orders VALUES (1, 1, 10), (2, 1, 20), (3, 2, 5)");
      statement.execute(
          "CREATE TABLE order_lines (order_id INT REFERENCES orders (id), line INT,"
              + " PRIMARY KEY (order_id, line))");
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
  void initializeWorkspaceAndSources() throws Exception {
    workspace = temporaryDirectory.resolve("workspace");
    catalogName = "catalog_" + UUID.randomUUID().toString().replace("-", "");
    environment = new HashMap<>();
    environment.put("STORE_PASSWORD", STORE.getPassword());
    environment.put("POSTGRES_PASSWORD", POSTGRES_PASSWORD);

    var initialized =
        execute(
            "",
            "workspace",
            "init",
            "--workspace",
            workspace.toString(),
            "--catalog",
            catalogName,
            "--store-jdbc-url",
            STORE.getJdbcUrl(),
            "--store-user",
            STORE.getUsername(),
            "--store-password-env",
            "STORE_PASSWORD");
    assertSuccessful(initialized, "scalardb-analytics/workspace");

    var postgres =
        execute(
            "",
            "source",
            "register",
            "--workspace",
            workspace.toString(),
            "--name",
            "warehouse",
            "--type",
            "postgresql",
            "--host",
            POSTGRES.getHost(),
            "--port",
            Integer.toString(POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)),
            "--database",
            POSTGRES.getDatabaseName(),
            "--user",
            POSTGRES.getUsername(),
            "--password-env",
            "POSTGRES_PASSWORD");
    assertSuccessful(postgres, "scalardb-analytics/source");
    postgresRegistration = postgres;

    var mysql =
        execute(
            MYSQL_PASSWORD + System.lineSeparator(),
            "source",
            "register",
            "--workspace",
            workspace.toString(),
            "--name",
            "crm",
            "--type",
            "mysql",
            "--host",
            MYSQL.getHost(),
            "--port",
            Integer.toString(MYSQL.getMappedPort(MYSQL_PORT)),
            "--database",
            MYSQL_DATABASE,
            "--user",
            MYSQL_USER,
            "--password-stdin");
    assertSuccessful(mysql, "scalardb-analytics/source");
    mysqlRegistration = mysql;
  }

  @Test
  void registrationPassesPostgreSqlTableMetadataToTheSemanticCatalog() throws Exception {
    var output = OBJECT_MAPPER.readTree(postgresRegistration.standardOutput().strip());
    var tableMetadata = output.get("tableMetadata");
    assertThat(tableMetadata.get("state").asText()).isEqualTo("retrieved");
    var tables = new HashMap<String, JsonNode>();
    tableMetadata
        .get("tables")
        .forEach(table -> tables.put(table.get("tableIdentifier").asText(), table));
    assertThat(tables).containsOnlyKeys("warehouse.public.orders", "warehouse.public.order_lines");
    var lines = Objects.requireNonNull(tables.get("warehouse.public.order_lines"));
    assertThat(lines.get("recorded").asText()).isEqualTo("observation");

    var domain = catalogName.replace('_', '-');
    try (var store =
        SemanticCatalogStore.open(
            new StoreSettings(STORE.getJdbcUrl(), STORE.getUsername(), STORE.getPassword()))) {
      var observation =
          store.observationContent(domain, lines.get("inputObservation").asText()).orElseThrow();
      // The source metadata is the observation; the table metadata derived from it is not.
      assertThat(observation.get("sourceMetadata").get("connector").asText())
          .isEqualTo("postgresql");
      assertThat(observation.has("tableMetadata")).isFalse();
      assertThat(observation.get("sourceMetadata").get("document").toString())
          .contains("order_lines", "orders");
      assertThat(
              store.derivedRecord(
                  domain, "warehouse.public.order_lines", lines.get("version").asLong()))
          .hasValueSatisfying(
              derived ->
                  assertThat(derived.get("tableMetadata").toString())
                      .contains("primary-key", "foreign-key", "analyticsType"));
    }
    assertThat(postgresRegistration.allOutput()).doesNotContain(POSTGRES_PASSWORD);
  }

  @Test
  void registrationSkipsMySqlSourcesWithoutAConnector() throws Exception {
    var output = OBJECT_MAPPER.readTree(mysqlRegistration.standardOutput().strip());
    assertThat(output.get("tableMetadata").get("state").asText()).isEqualTo("skipped");
    assertThat(output.get("tableMetadata").get("reason").asText()).contains("MySQL");
  }

  @Test
  void reRetrievalOfAnUnchangedSourceRecordsOnlyDerivedRecords() throws Exception {
    var retrieved =
        execute(
            "",
            "metadata",
            "retrieve",
            "--workspace",
            workspace.toString(),
            "--source",
            "warehouse");

    assertSuccessful(retrieved, "scalardb-analytics/table-metadata-retrieval");
    var tables =
        OBJECT_MAPPER
            .readTree(retrieved.standardOutput().strip())
            .get("tableMetadata")
            .get("tables");
    assertThat(tables).hasSize(2);
    tables.forEach(table -> assertThat(table.get("recorded").asText()).isEqualTo("derived-record"));

    var skipped =
        execute("", "metadata", "retrieve", "--workspace", workspace.toString(), "--source", "crm");
    assertSuccessful(skipped, "scalardb-analytics/table-metadata-retrieval");

    var missing =
        execute(
            "", "metadata", "retrieve", "--workspace", workspace.toString(), "--source", "absent");
    assertError(missing, "SOURCE_NOT_FOUND");
  }

  @Test
  void managesWorkspaceSourcesAndPhysicalMetadataWithoutExposingSecrets() throws Exception {
    var configuration = Files.readString(workspace.resolve(Workspace.CONFIG_FILE_NAME));
    assertThat(configuration)
        .contains(STORE.getJdbcUrl(), STORE.getUsername(), "STORE_PASSWORD")
        .doesNotContain(STORE.getPassword(), POSTGRES_PASSWORD, MYSQL_PASSWORD);
    if (Files.getFileStore(workspace).supportsFileAttributeView("posix")) {
      assertThat(Files.getPosixFilePermissions(workspace.resolve(Workspace.CONFIG_FILE_NAME)))
          .doesNotContain(
              PosixFilePermission.GROUP_WRITE,
              PosixFilePermission.OTHERS_WRITE,
              PosixFilePermission.GROUP_READ,
              PosixFilePermission.OTHERS_READ);
    }

    var sources = execute("", "source", "list", "--workspace", workspace.toString());
    assertSuccessful(sources, "scalardb-analytics/sources");
    var sourceJson = OBJECT_MAPPER.readTree(sources.standardOutput().strip());
    assertThat(sourceJson.get("sources").toString()).contains("warehouse", "crm");

    var shown = execute("", "source", "show", "--workspace", workspace.toString(), "warehouse");
    assertSuccessful(shown, "scalardb-analytics/source");
    assertThat(shown.allOutput())
        .doesNotContain(STORE.getPassword(), POSTGRES_PASSWORD, MYSQL_PASSWORD);

    var metadata =
        execute(
            "", "metadata", "list", "--workspace", workspace.toString(), "--source", "warehouse");
    assertSuccessful(metadata, "scalardb-analytics/physical-metadata");
    var metadataJson = OBJECT_MAPPER.readTree(metadata.standardOutput().strip());
    assertThat(metadataJson.get("namespaces").toString()).contains("public");
    assertThat(metadataJson.get("tables").toString()).contains("orders", "id");
    var table = metadataJson.get("tables").get(0);
    assertThat(table.has("dataSourceName")).isTrue();
    assertThat(table.has("namespaceNames")).isTrue();
    assertThat(table.has("name")).isTrue();
    assertThat(table.get("columns").get(0).has("ordinalPosition")).isTrue();
    assertThat(table.get("columns").get(0).has("nullable")).isTrue();
    assertThat(metadata.allOutput())
        .doesNotContain(STORE.getPassword(), POSTGRES_PASSWORD, MYSQL_PASSWORD);
  }

  @Test
  void workspaceInitRollsBackConfigurationWhenCatalogCreationFails() throws Exception {
    var failedWorkspace = temporaryDirectory.resolve("failed-workspace");

    var result =
        execute(
            "",
            "workspace",
            "init",
            "--workspace",
            failedWorkspace.toString(),
            "--catalog",
            catalogName,
            "--store-jdbc-url",
            STORE.getJdbcUrl(),
            "--store-user",
            STORE.getUsername(),
            "--store-password-env",
            "STORE_PASSWORD");

    assertError(result, "ALREADY_EXISTS");
    assertThat(failedWorkspace.resolve(Workspace.CONFIG_FILE_NAME)).doesNotExist();
  }

  @Test
  void executesEverySqlInputModeAndACrossSourceJoin() throws Exception {
    var sql =
        """
        SELECT c.name, sum(o.amount)::BIGINT AS total
        FROM warehouse.public.orders AS o
        JOIN crm.sales.customers AS c ON o.customer_id = c.id
        GROUP BY c.name
        ORDER BY c.name
        """;

    var argument =
        execute("", "query", "--workspace", workspace.toString(), "--sql", "SELECT 1 AS answer");
    assertQueryValue(argument, "1");

    var sqlFile = temporaryDirectory.resolve("query.sql");
    Files.writeString(sqlFile, "SELECT 'file' AS mode", StandardCharsets.UTF_8);
    var file =
        execute("", "query", "--workspace", workspace.toString(), "--file", sqlFile.toString());
    assertQueryValue(file, "file");

    var standardInput = execute(sql, "query", "--workspace", workspace.toString(), "--stdin");
    assertSuccessful(standardInput, "scalardb-analytics/query-result");
    var output = OBJECT_MAPPER.readTree(standardInput.standardOutput().strip());
    assertThat(output.get("rows").toString()).isEqualTo("[[\"alice\",30],[\"bob\",5]]");
  }

  @Test
  void rendersTemporalValuesAsIsoStringsAtAnyDepth() throws Exception {
    var result =
        execute(
            "",
            "query",
            "--workspace",
            workspace.toString(),
            "--sql",
            """
            SELECT DATE '2026-01-02' AS d, DATE '1969-12-31' AS before_epoch,
                   TIME '08:15:30.5' AS t, TIME '24:00:00' AS end_of_day,
                   TIMESTAMP '2026-01-03 04:05:06' AS ts,
                   TIMESTAMPTZ '2026-01-03 04:05:06+00' AS tz,
                   [DATE '2026-01-02', NULL] AS dates,
                   [TIME '08:00:00', TIME '09:30:00']::TIME[2] AS times,
                   {'d': DATE '2026-01-02', 't': TIME '08:00:00'} AS fields,
                   MAP {DATE '2026-01-02': TIMESTAMPTZ '1969-12-31 23:59:59.5+00'} AS entries
            """);

    assertSuccessful(result, "scalardb-analytics/query-result");
    var output = OBJECT_MAPPER.readTree(result.standardOutput().strip());
    assertThat(output.get("schemaVersion").textValue()).isEqualTo("1");
    assertThat(output.get("rows").toString())
        .isEqualTo(
            "[[\"2026-01-02\",\"1969-12-31\",\"08:15:30.500\",\"24:00:00\","
                + "\"2026-01-03T04:05:06\",\"2026-01-03T04:05:06Z\",[\"2026-01-02\",null],"
                + "[\"08:00\",\"09:30\"],"
                + "{\"d\":\"2026-01-02\",\"t\":\"08:00\"},"
                + "[{\"key\":\"2026-01-02\",\"value\":\"1969-12-31T23:59:59.500Z\"}]]]");
  }

  @Test
  void classifiesTimeLimitsCancellationAndFailuresWithoutLeakingInput() throws Exception {
    var sensitiveSql =
        "SELECT sum(i * random()) FROM range(100000000000) t(i) /* " + POSTGRES_PASSWORD + " */";
    var timedOut =
        execute(
            "",
            "query",
            "--workspace",
            workspace.toString(),
            "--time-limit-seconds",
            "1",
            "--sql",
            sensitiveSql);
    assertError(timedOut, "RESOURCE_LIMIT_EXCEEDED");
    assertThat(timedOut.allOutput()).doesNotContain(POSTGRES_PASSWORD, sensitiveSql);

    var standardOutput = new ByteArrayOutputStream();
    var standardError = new ByteArrayOutputStream();
    try (var cli =
            new ScalarDbAnalyticsCli(
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(standardOutput, true, StandardCharsets.UTF_8),
                new PrintStream(standardError, true, StandardCharsets.UTF_8),
                environment);
        var executor = Executors.newSingleThreadExecutor()) {
      var future =
          executor.submit(
              () ->
                  cli.execute(
                      "query",
                      "--workspace",
                      workspace.toString(),
                      "--sql",
                      "SELECT sum(i * random()) FROM range(100000000000) t(i)"));
      Thread.sleep(500);
      assertThat(cli.cancel()).isTrue();
      assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo(1);
    }
    var canceled =
        new Result(
            1,
            standardOutput.toString(StandardCharsets.UTF_8),
            standardError.toString(StandardCharsets.UTF_8));
    assertError(canceled, "CANCELED");

    var wrongEnvironment = new HashMap<>(environment);
    wrongEnvironment.put("BAD_PASSWORD", POSTGRES_PASSWORD + "-wrong");
    var unreachable =
        execute(
            "",
            wrongEnvironment,
            "source",
            "register",
            "--workspace",
            workspace.toString(),
            "--name",
            "unreachable",
            "--type",
            "postgresql",
            "--host",
            POSTGRES.getHost(),
            "--port",
            Integer.toString(POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)),
            "--database",
            POSTGRES.getDatabaseName(),
            "--user",
            POSTGRES.getUsername(),
            "--password-env",
            "BAD_PASSWORD");
    assertError(unreachable, "DATA_SOURCE_UNREACHABLE");
    assertThat(unreachable.allOutput()).doesNotContain(wrongEnvironment.get("BAD_PASSWORD"));
  }

  private Result execute(String standardInput, String... arguments) {
    return execute(standardInput, environment, arguments);
  }

  private static Result execute(
      String standardInput, Map<String, String> environment, String... arguments) {
    var standardOutput = new ByteArrayOutputStream();
    var standardError = new ByteArrayOutputStream();
    int exitCode;
    try (var cli =
        new ScalarDbAnalyticsCli(
            new ByteArrayInputStream(standardInput.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(standardOutput, true, StandardCharsets.UTF_8),
            new PrintStream(standardError, true, StandardCharsets.UTF_8),
            environment)) {
      exitCode = cli.execute(arguments);
    }
    return new Result(
        exitCode,
        standardOutput.toString(StandardCharsets.UTF_8),
        standardError.toString(StandardCharsets.UTF_8));
  }

  private static void assertSuccessful(Result result, String schema) throws Exception {
    assertThat(result.exitCode()).isZero();
    assertThat(result.standardError()).isEmpty();
    assertThat(OBJECT_MAPPER.readTree(result.standardOutput().strip()).get("schema").textValue())
        .isEqualTo(schema);
  }

  private static void assertQueryValue(Result result, String expected) throws Exception {
    assertSuccessful(result, "scalardb-analytics/query-result");
    assertThat(
            OBJECT_MAPPER
                .readTree(result.standardOutput().strip())
                .get("rows")
                .get(0)
                .get(0)
                .asText())
        .isEqualTo(expected);
  }

  private static void assertError(Result result, String code) throws Exception {
    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.standardError()).isEmpty();
    var output = OBJECT_MAPPER.readTree(result.standardOutput().strip());
    assertThat(output.get("schema").textValue()).isEqualTo("scalardb-analytics/error");
    assertThat(output.get("code").textValue()).isEqualTo(code);
  }

  private record Result(int exitCode, String standardOutput, String standardError) {
    String allOutput() {
      return standardOutput + standardError;
    }
  }
}
