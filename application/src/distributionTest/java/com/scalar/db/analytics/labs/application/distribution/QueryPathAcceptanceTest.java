/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scalar.db.analytics.labs.application.distribution.RestrictedEnvironment.Result;
import com.scalar.db.analytics.labs.application.distribution.RestrictedEnvironment.ServerType;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves the query path end to end through the packaged executable: a user of the distribution
 * registers real PostgreSQL and MySQL sources, queries them alone and together with pushdown, and
 * can rely on the CLI's safety boundaries.
 *
 * <p>The launcher runs unpacked, with an isolated home and temporary directory and no network
 * access except to the databases, and is driven with the commands Agent Skill v0 documents. Every
 * claim is judged from evidence outside the process: the CLI's stdout, stderr, and exit code, the
 * statements and sessions each source server records, and the files the process leaves behind.
 *
 * <p>The steps share one workspace and run in order.
 */
@TestInstance(Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class QueryPathAcceptanceTest {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final String WAREHOUSE = "warehouse";
  private static final String CRM = "crm";
  private static final String READER = "reader";
  private static final int ORDER_COUNT = 2000;
  private static final List<String> STATUSES =
      List.of("placed", "shipped", "delivered", "returned");
  private static final Map<Integer, String> REGIONS =
      Map.of(1, "east", 2, "west", 3, "east", 4, "north", 5, "west");
  // Spends its time in DuckDB after the source has answered, so DuckDB can interrupt it. The
  // marker reaches the source as a pushed-down filter, which shows that the query is running.
  private static final String LONG_QUERY =
      "SELECT count(*) FROM warehouse.sales.orders AS a, warehouse.sales.orders AS b, "
          + "range(1000000) AS r WHERE a.status <> '%s'";

  @TempDir static Path scratch;

  private @Nullable RestrictedEnvironment environment;
  private String workspace = "";
  private String crmPassword = "";
  private Map<String, String> credentials = Map.of();
  private final List<Result> results = new ArrayList<>();
  private final List<String> secrets = new ArrayList<>();
  private final Map<String, String> decoys = new TreeMap<>();

  @BeforeAll
  void startEnvironment() throws Exception {
    var distribution = DistributionArchive.unpack(scratch.resolve("unpacked"));
    var sources = new LinkedHashMap<String, ServerType>();
    sources.put(WAREHOUSE, ServerType.POSTGRESQL);
    sources.put(CRM, ServerType.MYSQL);
    environment =
        RestrictedEnvironment.start(
            DistributionSmokeTest.requiredProperty("distribution.platform"),
            distribution,
            Files.createDirectories(scratch.resolve("environment")),
            sources);
    var warehousePassword = DatabaseServer.password("warehouse-reader");
    var crmPassword = DatabaseServer.password("crm-reader");
    loadWarehouse(warehousePassword);
    loadCrm(crmPassword);
    workspace = env().workspace("acceptance");
    credentials =
        Map.of("STORE_PASSWORD", env().store().password(), "WAREHOUSE_PASSWORD", warehousePassword);
    secrets.addAll(
        List.of(
            env().store().password(),
            env().server(WAREHOUSE).password(),
            env().server(CRM).password(),
            warehousePassword,
            crmPassword));
    placeDecoyExtensions();
  }

  private void loadWarehouse(String readerPassword) throws Exception {
    var server = env().server(WAREHOUSE);
    server.execute(null, "CREATE ROLE reader LOGIN PASSWORD '" + readerPassword + "'");
    server.execute(null, "CREATE DATABASE warehouse");
    server.execute(WAREHOUSE, fixture("warehouse-postgresql.sql"));
  }

  private void loadCrm(String readerPassword) throws Exception {
    var server = env().server(CRM);
    server.execute(
        null,
        "CREATE DATABASE crm; CREATE USER 'reader'@'%' IDENTIFIED BY '"
            + readerPassword
            + "'; GRANT SELECT ON crm.* TO 'reader'@'%'");
    server.execute(CRM, fixture("crm-mysql.sql"));
    crmPassword = readerPassword;
  }

  /**
   * Places an invalid file where DuckDB looks for globally installed extensions. Loading any of
   * them would fail, so every successful query shows that only the packaged extensions load.
   */
  private void placeDecoyExtensions() throws Exception {
    var directory =
        env().home()
            + "/.duckdb/extensions/v"
            + DistributionSmokeTest.requiredProperty("distribution.duckdbVersion")
            + "/"
            + DistributionSmokeTest.requiredProperty("distribution.platform");
    for (var extension : List.of("postgres_scanner", "mysql_scanner", "httpfs")) {
      var path = directory + "/" + extension + ".duckdb_extension";
      var content = "not an extension: " + UUID.randomUUID();
      env().writeFile(path, content);
      decoys.put(path, content);
    }
  }

  private static String fixture(String name) throws IOException {
    try (var input = QueryPathAcceptanceTest.class.getResourceAsStream("/query-path/" + name)) {
      return new String(Objects.requireNonNull(input, name).readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @AfterAll
  void stop() {
    if (environment != null) {
      environment.close();
    }
  }

  /** Every invocation ends without leaving a source session or a temporary file behind. */
  @AfterEach
  void leavesNoResourcesBehind() throws Exception {
    for (var name : List.of(WAREHOUSE, CRM)) {
      var server = env().server(name);
      var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (server.sessions(READER) > 0 && System.nanoTime() < deadline) {
        Thread.sleep(100);
      }
      assertThat(server.sessions(READER)).as("%s sessions", name).isZero();
    }
    assertThat(env().files(env().temporary())).as("temporary files").isEmpty();
  }

  @Test
  @Order(1)
  void initializesAnEmptyWorkspace() throws Exception {
    assertThat(env().files(workspace)).isEmpty();
    var initialized =
        run(
            "workspace",
            "init",
            "--workspace",
            workspace,
            "--catalog",
            "acceptance",
            "--store-jdbc-url",
            env().store().jdbcUrl(),
            "--store-user",
            "postgres",
            "--store-password-env",
            "STORE_PASSWORD");
    assertSuccessful(initialized, "scalardb-analytics/workspace");
    assertThat(env().files(workspace)).containsOnlyKeys("workspace.json");
  }

  @Test
  @Order(2)
  void registersBothSourcesAsTheSkillDocuments() throws Exception {
    var warehouse = env().server(WAREHOUSE);
    var registeredWarehouse =
        run(
            "source",
            "register",
            "--workspace",
            workspace,
            "--name",
            WAREHOUSE,
            "--type",
            "postgresql",
            "--host",
            warehouse.host(),
            "--port",
            Integer.toString(warehouse.port()),
            "--database",
            WAREHOUSE,
            "--user",
            READER,
            "--password-env",
            "WAREHOUSE_PASSWORD");
    assertSuccessful(registeredWarehouse, "scalardb-analytics/source");

    var crm = env().server(CRM);
    var registeredCrm =
        record(
            env()
                .runWithInput(
                    credentials,
                    crmPassword + "\n",
                    "source",
                    "register",
                    "--workspace",
                    workspace,
                    "--name",
                    CRM,
                    "--type",
                    "mysql",
                    "--host",
                    crm.host(),
                    "--port",
                    Integer.toString(crm.port()),
                    "--database",
                    CRM,
                    "--user",
                    READER,
                    "--password-stdin"));
    assertSuccessful(registeredCrm, "scalardb-analytics/source");

    var listed = run("source", "list", "--workspace", workspace);
    assertSuccessful(listed, "scalardb-analytics/sources");
    assertThat(json(listed).get("sources").findValuesAsText("name"))
        .containsExactly(CRM, WAREHOUSE);
    for (var name : List.of(WAREHOUSE, CRM)) {
      var shown = run("source", "show", "--workspace", workspace, name);
      assertSuccessful(shown, "scalardb-analytics/source");
    }

    // The interim registration resolves schemas through the upstream schema resolver, which
    // omits columns of types it does not support, such as DECIMAL; queries still read them.
    var warehouseTables = metadata(WAREHOUSE);
    assertThat(warehouseTables).containsKey("sales.orders");
    assertThat(warehouseTables.get("sales.orders"))
        .containsExactly(
            "order_id",
            "customer_id",
            "ordered_at",
            "ordered_on",
            "delivered_at",
            "pickup_time",
            "status",
            "express",
            "note",
            "receipt");
    assertThat(metadata(CRM))
        .containsEntry(
            "crm.customers",
            List.of("customer_id", "name", "region", "signed_up", "loyalty_points"));
  }

  /** The columns of each table a source's metadata lists, by {@code namespace.table}. */
  private Map<String, List<String>> metadata(String source) throws Exception {
    var listed = run("metadata", "list", "--workspace", workspace, "--source", source);
    assertSuccessful(listed, "scalardb-analytics/physical-metadata");
    var tables = new TreeMap<String, List<String>>();
    for (var table : json(listed).get("tables")) {
      var namespace = new ArrayList<String>();
      table.get("namespaceNames").forEach(name -> namespace.add(name.textValue()));
      tables.put(
          String.join(".", namespace) + "." + table.get("name").textValue(),
          table.get("columns").findValuesAsText("name"));
    }
    return tables;
  }

  @Test
  @Order(3)
  void preservesPostgreSqlTypesAndValues() throws Exception {
    var output =
        query(
            "SELECT order_id, customer_id, ordered_at, ordered_on, delivered_at, pickup_time, "
                + "amount, status, express, note, receipt FROM warehouse.sales.orders "
                + "WHERE order_id IN (3, 6, 7, 21) "
                + "ORDER BY order_id");
    var zone =
        output.get("fields").get(4).get("type").textValue().replaceFirst(".*, (.+)\\)$", "$1");
    assertThat(zone).isNotEqualTo("null");
    assertThat(fields(output))
        .containsExactly(
            "order_id Int(64, true)",
            "customer_id Int(32, true)",
            "ordered_at Timestamp(MICROSECOND, null)",
            "ordered_on Date(DAY)",
            // The zone is DuckDB's session time zone, which follows the host; the values are
            // instants and do not depend on it.
            "delivered_at Timestamp(MICROSECOND, " + zone + ")",
            "pickup_time Time(MICROSECOND, 64)",
            "amount Decimal(12, 2, 128)",
            "status Utf8",
            "express Bool",
            "note Utf8",
            "receipt Binary");
    assertThat(output.get("rows").toString())
        .isEqualTo(
            OBJECT_MAPPER.writeValueAsString(List.of(order(3), order(6), order(7), order(21))));
  }

  @Test
  @Order(4)
  void preservesMySqlTypesAndValues() throws Exception {
    var output =
        query(
            "SELECT customer_id, name, region, signed_up, credit_limit, loyalty_points "
                + "FROM crm.crm.customers ORDER BY customer_id");
    assertThat(fields(output))
        .containsExactly(
            "customer_id Int(32, false)",
            "name Utf8",
            "region Utf8",
            "signed_up Date(DAY)",
            "credit_limit Decimal(10, 2, 128)",
            "loyalty_points Int(64, true)");
    assertThat(output.get("rows").toString())
        .isEqualTo(
            "[[1,\"Aoki Trading\",\"east\",\"2024-04-01\",\"50000.00\",1200],"
                + "[2,\"Brightline\",\"west\",\"2024-07-15\",\"25000.50\",null],"
                + "[3,\"Chiba Foods\",\"east\",\"2025-01-10\",\"10000.00\",300],"
                + "[4,\"Delta Retail\",\"north\",\"2025-03-03\",\"75000.25\",8800],"
                + "[5,\"Emi Studio\",\"west\",\"2025-11-30\",\"5000.00\",0]]");
  }

  @Test
  @Order(5)
  void joinsAcrossSources() throws Exception {
    var output =
        query(
            "SELECT c.region, count(*) AS orders, sum(o.amount) AS revenue "
                + "FROM warehouse.sales.orders AS o "
                + "JOIN crm.crm.customers AS c ON o.customer_id = c.customer_id "
                + "WHERE o.express GROUP BY c.region ORDER BY c.region");

    var expected = new TreeMap<String, List<Object>>();
    for (int id = 2; id <= ORDER_COUNT; id += 2) {
      var region = Objects.requireNonNull(REGIONS.get(id % 5 + 1));
      var totals =
          expected.computeIfAbsent(region, key -> new ArrayList<>(List.of(0L, BigDecimal.ZERO)));
      totals.set(0, (Long) totals.get(0) + 1);
      totals.set(1, ((BigDecimal) totals.get(1)).add(amount(id)));
    }
    var rows = new ArrayList<List<Object>>();
    expected.forEach(
        (region, totals) ->
            rows.add(List.of(region, totals.get(0), ((BigDecimal) totals.get(1)).toPlainString())));
    assertThat(fields(output))
        .containsExactly("region Utf8", "orders Int(64, true)", "revenue Decimal(38, 2, 128)");
    assertThat(output.get("rows").toString()).isEqualTo(OBJECT_MAPPER.writeValueAsString(rows));
  }

  @Test
  @Order(6)
  void answersDeterministicallyFromEverySqlInput() throws Exception {
    var sql =
        "SELECT o.order_id, c.name FROM warehouse.sales.orders AS o "
            + "JOIN crm.crm.customers AS c ON o.customer_id = c.customer_id "
            + "WHERE o.order_id <= 20 ORDER BY o.order_id";
    var file = workspace + "-input/query.sql";
    env().writeFile(file, sql);

    var outputs =
        List.of(
            run("query", "--workspace", workspace, "--sql", sql),
            run("query", "--workspace", workspace, "--sql", sql),
            run("query", "--workspace", workspace, "--file", file),
            record(
                env()
                    .runWithInput(credentials, sql, "query", "--workspace", workspace, "--stdin")));
    var executionIds = new ArrayList<String>();
    var withoutIds = new ArrayList<JsonNode>();
    for (var result : outputs) {
      assertSuccessful(result, "scalardb-analytics/query-result");
      var output = (ObjectNode) json(result);
      executionIds.add(UUID.fromString(output.remove("executionId").textValue()).toString());
      withoutIds.add(output);
    }
    assertThat(executionIds).doesNotHaveDuplicates();
    assertThat(withoutIds).allMatch(output -> output.equals(withoutIds.getFirst()));
    assertThat(withoutIds.getFirst().get("rows")).hasSize(20);
  }

  @Test
  @Order(7)
  void pushesProjectionFilterAndLimitIntoPostgreSql() throws Exception {
    var marker = "pushdown-" + UUID.randomUUID();
    var output =
        query(
            "SELECT order_id FROM warehouse.sales.orders WHERE status <> '"
                + marker
                + "' AND order_id > 10 LIMIT 3");
    assertThat(output.get("rows")).hasSize(3);

    var received = env().server(WAREHOUSE).statementsContaining(marker);
    assertThat(received).hasSize(1);
    assertThat(received.getFirst())
        .contains("SELECT \"status\", \"order_id\" FROM \"sales\".\"orders\"")
        .contains("WHERE \"status\" <> '" + marker + "'")
        .contains("AND \"order_id\" > '10'")
        .contains("LIMIT 3")
        .doesNotContain("amount", "note", "receipt");
  }

  @Test
  @Order(8)
  void pushesProjectionFilterAndLimitIntoMySql() throws Exception {
    var marker = "pushdown-" + UUID.randomUUID();
    var output =
        query(
            "SELECT name FROM crm.crm.customers WHERE region <> '"
                + marker
                + "' AND customer_id > 1 LIMIT 2");
    assertThat(output.get("rows")).hasSize(2);

    var received = env().server(CRM).statementsContaining(marker);
    assertThat(received).hasSize(1);
    assertThat(received.getFirst())
        .contains("SELECT `region`, `customer_id`, `name` FROM `crm`.`customers`")
        .contains("WHERE `region` != '" + marker + "'")
        .contains("AND `customer_id` > 1")
        .contains("LIMIT 2")
        .doesNotContain("credit_limit", "signed_up");
  }

  @Test
  @Order(9)
  void answersFromTheLiveSourcesRatherThanACopy() throws Exception {
    var marker = "live-" + UUID.randomUUID();
    var warehouseCount =
        "SELECT count(*) FROM warehouse.sales.orders WHERE note = '" + marker + "'";
    var crmCount = "SELECT count(*) FROM crm.crm.customers WHERE name = '" + marker + "'";
    assertThat(query(warehouseCount).get("rows").toString()).isEqualTo("[[0]]");
    assertThat(query(crmCount).get("rows").toString()).isEqualTo("[[0]]");

    var warehouse = env().server(WAREHOUSE);
    var crm = env().server(CRM);
    warehouse.execute(
        WAREHOUSE,
        "INSERT INTO sales.orders VALUES (100001, 1, now(), current_date, NULL, current_time, 1, "
            + "'placed', false, '"
            + marker
            + "', NULL)");
    crm.execute(
        CRM, "INSERT INTO customers VALUES (100001, '" + marker + "', 'east', '2026-01-01', 1, 1)");
    try {
      assertThat(query(warehouseCount).get("rows").toString()).isEqualTo("[[1]]");
      assertThat(query(crmCount).get("rows").toString()).isEqualTo("[[1]]");
    } finally {
      warehouse.execute(WAREHOUSE, "DELETE FROM sales.orders WHERE order_id = 100001");
      crm.execute(CRM, "DELETE FROM customers WHERE customer_id = 100001");
    }
    // Each query reached its source; none was answered from data held elsewhere.
    assertThat(warehouse.statementsContaining(marker)).hasSizeGreaterThanOrEqualTo(3);
    assertThat(crm.statementsContaining(marker)).hasSizeGreaterThanOrEqualTo(3);
  }

  @Test
  @Order(10)
  void truncatesJsonOutputAtOneThousandRows() throws Exception {
    var output = query("SELECT order_id FROM warehouse.sales.orders ORDER BY order_id");
    var rows = output.get("rows");
    assertThat(rows).hasSize(1000);
    assertThat(rows.get(0).toString()).isEqualTo("[1]");
    assertThat(rows.get(999).toString()).isEqualTo("[1000]");
    assertThat(output.get("truncated").booleanValue()).isTrue();

    var exact = query("SELECT order_id FROM warehouse.sales.orders WHERE order_id <= 1000");
    assertThat(exact.get("rows")).hasSize(1000);
    assertThat(exact.get("truncated").booleanValue()).isFalse();
  }

  @Test
  @Order(11)
  void stopsAQueryAtItsTimeLimit() throws Exception {
    var started = System.nanoTime();
    var result =
        run(
            "query",
            "--workspace",
            workspace,
            "--time-limit-seconds",
            "1",
            "--sql",
            LONG_QUERY.formatted("time-limit"));
    assertError(result, "RESOURCE_LIMIT_EXCEEDED");
    UUID.fromString(json(result).get("executionId").textValue());
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMinutes(1));
  }

  @Test
  @Order(12)
  void cancelsARunningQueryOnSigint() throws Exception {
    var marker = "cancel-" + UUID.randomUUID();
    var running =
        env()
            .start(
                credentials,
                null,
                "query",
                "--workspace",
                workspace,
                "--sql",
                LONG_QUERY.formatted(marker));
    var warehouse = env().server(WAREHOUSE);
    var deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);
    while (warehouse.statementsContaining(marker).isEmpty()) {
      assertThat(System.nanoTime()).as("the query reached the source").isLessThan(deadline);
      Thread.sleep(100);
    }
    running.interrupt();
    var result = record(running.await(Duration.ofMinutes(1)));
    assertError(result, "CANCELED");
    UUID.fromString(json(result).get("executionId").textValue());
  }

  @Test
  @Order(13)
  void rejectsUnsafeSql() throws Exception {
    var store = env().store();
    var storeConnection =
        "host=%s port=%d dbname=postgres user=postgres password=%s"
            .formatted(store.host(), store.port(), store.password());
    var rejected =
        List.of(
            "INSERT INTO warehouse.sales.orders (order_id) VALUES (999999)",
            "DELETE FROM crm.crm.customers",
            "CREATE TABLE warehouse.sales.copied AS SELECT * FROM warehouse.sales.orders",
            "SELECT 1; SELECT 2",
            "COPY (SELECT * FROM warehouse.sales.orders) TO '" + workspace + "-export.csv'",
            "INSTALL httpfs",
            "LOAD httpfs",
            "ATTACH '" + storeConnection + "' AS store (TYPE postgres)",
            "SELECT * FROM postgres_scan('" + storeConnection + "', 'public', 'pg_class')",
            "SELECT * FROM postgres_query('warehouse', 'SELECT 1')",
            "SELECT * FROM mysql_query('crm', 'SELECT 1')",
            "SELECT (SELECT pg_clear_cache())",
            "SELECT * FROM query('SELECT 1')",
            "SELECT * FROM query_table('warehouse.sales.orders')",
            "SELECT json_execute_serialized_sql('{}')");
    var codes = new LinkedHashMap<String, String>();
    for (var sql : rejected) {
      var result = run("query", "--workspace", workspace, "--sql", sql);
      assertThat(result.exitCode()).as(sql).isEqualTo(1);
      assertThat(result.standardError()).as(sql).isEmpty();
      codes.put(sql, json(result).get("code").textValue());
    }
    assertThat(codes)
        .allSatisfy((sql, code) -> assertThat(code).as(sql).isEqualTo("INVALID_QUERY"));
    var workspacePath = Path.of(workspace);
    assertThat(env().files(Objects.requireNonNull(workspacePath.getParent()).toString()))
        .doesNotContainKey(workspacePath.getFileName() + "-export.csv");
    assertThat(query("SELECT count(*) FROM warehouse.sales.orders").get("rows").toString())
        .isEqualTo("[[" + ORDER_COUNT + "]]");
    assertThat(query("SELECT count(*) FROM crm.crm.customers").get("rows").toString())
        .isEqualTo("[[5]]");
  }

  @Test
  @Order(14)
  void exposesOnlyTheRegisteredSources() throws Exception {
    var databases =
        query("SELECT database_name FROM duckdb_databases() WHERE NOT internal ORDER BY 1");
    assertThat(databases.get("rows").toString())
        .isEqualTo("[[\"crm\"],[\"memory\"],[\"warehouse\"]]");
  }

  @Test
  @Order(15)
  void neverReachesTheNetworkOrAGlobalExtension() throws Exception {
    var remote =
        run(
            "query",
            "--workspace",
            workspace,
            "--sql",
            "SELECT * FROM read_csv('https://example.com/data.csv')");
    assertError(remote, "EXECUTION_FAILED");

    // DuckDB would fail to load any of the decoys, which are still in place, so every query above
    // ran with only the packaged extensions.
    var placed = new TreeMap<String, String>();
    for (var entry : env().files(env().home() + "/.duckdb").entrySet()) {
      placed.put(env().home() + "/.duckdb/" + entry.getKey(), entry.getValue());
    }
    assertThat(placed).containsAllEntriesOf(decoys);
  }

  @Test
  @Order(16)
  void failsWithoutExposingConnectionSettingsWhenASourceRejectsTheCredential() throws Exception {
    // Attach diagnostics can quote connection settings, so a rejected credential is the path on
    // which a leak would show; the next step scans this output too.
    var warehouse = env().server(WAREHOUSE);
    var rotated = DatabaseServer.password("warehouse-rotated");
    secrets.add(rotated);
    warehouse.execute(null, "ALTER ROLE reader PASSWORD '" + rotated + "'");
    try {
      var result = run("query", "--workspace", workspace, "--sql", "SELECT 1");
      assertError(result, "INITIALIZATION_FAILED");
      assertThat(json(result).get("message").textValue()).doesNotContain(warehouse.host());
    } finally {
      warehouse.execute(
          null, "ALTER ROLE reader PASSWORD '" + credentials.get("WAREHOUSE_PASSWORD") + "'");
    }
    assertThat(query("SELECT count(*) FROM crm.crm.customers").get("rows").toString())
        .isEqualTo("[[5]]");
  }

  @Test
  @Order(17)
  void keepsSecretsOutOfOutputAndFiles() throws Exception {
    assertThat(results).hasSizeGreaterThan(30);
    for (var result : results) {
      assertThat(result.allOutput()).doesNotContain(secrets);
    }
    var files = new HashMap<String, String>();
    files.putAll(env().files(workspace));
    files.putAll(env().files(env().home()));
    files.putAll(env().files(env().temporary()));
    assertThat(files).isNotEmpty();
    files.forEach((path, content) -> assertThat(content).as(path).doesNotContain(secrets));
  }

  private static List<@Nullable Object> order(int id) {
    var row = new ArrayList<@Nullable Object>();
    row.add(id);
    row.add(id % 5 + 1);
    row.add(LocalDateTime.of(2026, 1, 1, 9, 0).plusHours(id).toString());
    row.add(LocalDate.of(2026, 1, 1).plusDays(id / 24).toString());
    row.add(
        id % 4 == 2
            ? Instant.parse("2026-01-03T00:00:00Z").plusSeconds(id * 90L * 60).toString()
            : null);
    row.add(LocalTime.of(8, 0).plusSeconds((id % 20) * 930L).toString());
    row.add(amount(id).toPlainString());
    row.add(STATUSES.get(id % 4));
    row.add(id % 2 == 0);
    row.add(id % 7 == 0 ? null : "order " + id);
    row.add(
        id % 3 == 0
            ? Base64.getEncoder().encodeToString(HexFormat.of().parseHex("%04x".formatted(id)))
            : null);
    return row;
  }

  private static BigDecimal amount(int id) {
    return BigDecimal.valueOf(id).multiply(new BigDecimal("1.25")).setScale(2);
  }

  private JsonNode query(String sql) throws Exception {
    var result = run("query", "--workspace", workspace, "--sql", sql);
    assertSuccessful(result, "scalardb-analytics/query-result");
    var output = json(result);
    UUID.fromString(output.get("executionId").textValue());
    return output;
  }

  private static List<String> fields(JsonNode output) {
    var fields = new ArrayList<String>();
    for (var field : output.get("fields")) {
      fields.add(field.get("name").textValue() + " " + field.get("type").textValue());
    }
    return fields;
  }

  private Result run(String... arguments) throws Exception {
    return record(env().run(credentials, arguments));
  }

  private Result record(Result result) {
    results.add(result);
    return result;
  }

  private RestrictedEnvironment env() {
    if (environment == null) {
      throw new IllegalStateException("The restricted environment did not start.");
    }
    return environment;
  }

  private static JsonNode json(Result result) throws Exception {
    return OBJECT_MAPPER.readTree(result.standardOutput().strip());
  }

  private static void assertSuccessful(Result result, String schema) throws Exception {
    assertThat(result.exitCode()).as(result.allOutput()).isZero();
    assertThat(result.standardError()).isEmpty();
    assertThat(json(result).get("schema").textValue()).isEqualTo(schema);
  }

  private static void assertError(Result result, String code) throws Exception {
    assertThat(result.exitCode()).as(result.allOutput()).isEqualTo(1);
    assertThat(result.standardError()).isEmpty();
    var output = json(result);
    assertThat(output.get("schema").textValue()).isEqualTo("scalardb-analytics/error");
    assertThat(output.get("code").textValue()).as(result.allOutput()).isEqualTo(code);
  }
}
