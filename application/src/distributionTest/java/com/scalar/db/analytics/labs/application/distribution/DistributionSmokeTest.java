/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalar.db.analytics.labs.application.distribution.RestrictedEnvironment.Result;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves that the distribution archive works as users receive it: unpacked into a clean directory,
 * launched through its own launcher with only a Java runtime, an isolated home and temporary
 * directory, and no network access except to the test databases.
 *
 * <p>The steps share one workspace and run in order.
 */
@TestInstance(Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class DistributionSmokeTest {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final String SOURCE = "source";
  private static final String PLUGIN = "plugins/scalardb-analytics";
  private static final Pattern INVENTORY_ENTRY =
      Pattern.compile("\\*\\*Name:\\*\\* `([^`]+)` \\*\\*Version:\\*\\* `([^`]+)`");

  // Shared by the ordered steps, so it is created once for the class.
  @TempDir static Path scratch;

  private Path distribution;
  private @Nullable RestrictedEnvironment environment;
  private String workspace = "";
  private Map<String, String> credentials = Map.of();

  static String requiredProperty(String name) {
    var value = System.getProperty(name);
    if (value == null || value.isEmpty()) {
      throw new IllegalStateException(
          name + " is not set; run the smoke test with ./gradlew distSmokeTest");
    }
    return value;
  }

  @BeforeAll
  void unpackAndStart() throws Exception {
    distribution = DistributionArchive.unpack(scratch.resolve("unpacked"));
    environment =
        RestrictedEnvironment.start(
            requiredProperty("distribution.platform"),
            distribution,
            Files.createDirectories(scratch.resolve("environment")),
            Map.of(SOURCE, RestrictedEnvironment.ServerType.POSTGRESQL));
    var source = environment.server(SOURCE);
    source.execute(null, "CREATE TABLE orders (id INT PRIMARY KEY, amount BIGINT, note TEXT)");
    source.execute(null, "INSERT INTO orders VALUES (1, 10, 'first'), (2, 20, NULL)");
    workspace = environment.workspace("workspace");
    credentials =
        Map.of(
            "STORE_PASSWORD", environment.store().password(),
            "SOURCE_PASSWORD", environment.server(SOURCE).password());
  }

  @AfterAll
  void stop() throws Exception {
    if (environment != null) {
      environment.close();
    }
  }

  @Test
  @Order(1)
  void containsTheLauncherSkillProvenanceAndLicenses() throws Exception {
    var platform = requiredProperty("distribution.platform");
    assertThat(distribution.getFileName().toString())
        .isEqualTo(
            "scalardb-analytics-" + requiredProperty("distribution.version") + "-" + platform);
    assertThat(distribution.resolve("bin/scalardb-analytics")).isExecutable();
    assertThat(distribution.resolve("skills/query-agent-v1/SKILL.md")).isRegularFile();
    // The Claude Code plugin delivers the same Agent Skill with its commands.
    var plugin = distribution.resolve(PLUGIN);
    assertThat(
            OBJECT_MAPPER
                .readTree(plugin.resolve(".claude-plugin/plugin.json").toFile())
                .get("version")
                .textValue())
        .isEqualTo(requiredProperty("distribution.version"));
    for (var command : List.of("setup", "build", "ask", "status")) {
      assertThat(plugin.resolve("commands/" + command + ".md")).isRegularFile();
    }
    assertThat(plugin.resolve("bin/scalardb-analytics")).isExecutable();
    assertThat(plugin.resolve("bin/review-notifications")).isExecutable();
    assertThat(plugin.resolve("skills/scalardb-analytics-query-agent/SKILL.md"))
        .hasSameTextualContentAs(distribution.resolve("skills/query-agent-v1/SKILL.md"));
    assertThat(distribution.resolve("LICENSE")).isRegularFile();
    assertThat(distribution.resolve("licenses/third-party/THIRD-PARTY.md")).isRegularFile();
    assertThat(distribution.resolve("provenance/physical-catalog.properties")).isRegularFile();
    assertThat(Files.readString(distribution.resolve("provenance/distribution.properties")))
        .contains("platform=" + platform, "javaRuntime=25 or later");
    assertThat(
            Files.readString(
                distribution.resolve("provenance/duckdb-extensions/scalardb_analytics.properties")))
        .contains("platform=" + platform);
    // Every bundled jar is inventoried with its license.
    var inventory = Files.readString(distribution.resolve("licenses/third-party/THIRD-PARTY.md"));
    assertThat(inventory).doesNotContain("## Unknown");
    var inventoried =
        INVENTORY_ENTRY
            .matcher(inventory)
            .results()
            .map(entry -> entry.group(1) + "-" + entry.group(2))
            .toList();
    try (var jars = Files.list(distribution.resolve("lib"))) {
      var names = jars.map(jar -> jar.getFileName().toString()).toList();
      assertThat(names)
          .contains("application-" + requiredProperty("distribution.version") + ".jar");
      for (var name : names) {
        if (!name.startsWith("application-")) {
          // A jar is named after its module and version, optionally followed by a classifier.
          assertThat(inventoried)
              .as(name)
              .anyMatch(module -> name.equals(module + ".jar") || name.startsWith(module + "-"));
        }
      }
    }
  }

  @Test
  @Order(2)
  void printsVersionAndHelp() throws Exception {
    var version = run("version");
    assertSuccessful(version, "scalardb-analytics/version");
    assertThat(json(version).get("productVersion").textValue())
        .isEqualTo(requiredProperty("distribution.version"));

    var help = run("--help");
    assertThat(help.exitCode()).isZero();
    assertThat(help.standardOutput())
        .contains("Usage: scalardb-analytics", "workspace", "source", "metadata", "query");
  }

  @Test
  @Order(3)
  void rejectsMissingAndOlderJavaWithActionableMessages() throws Exception {
    var launcher = distribution.resolve("bin/scalardb-analytics").toString();

    var older =
        runOnHost(
            List.of(launcher, "version"),
            Map.of(
                "PATH",
                "/usr/bin:/bin",
                "JAVA_HOME",
                requiredProperty("distribution.olderJavaHome")));
    assertThat(older.exitCode()).isEqualTo(1);
    assertThat(older.standardError())
        .contains("requires Java 25 or later", "is Java 21", "Set JAVA_HOME");

    // A PATH with the tools the launcher needs, but no java command.
    var tools = Files.createDirectories(scratch.resolve("tools"));
    for (var tool : List.of("sh", "uname", "sed", "xargs", "tr", "head", "ls")) {
      Files.createSymbolicLink(tools.resolve(tool), locate(tool));
    }
    var missing = runOnHost(List.of(launcher, "version"), Map.of("PATH", tools.toString()));
    assertThat(missing.exitCode()).isEqualTo(1);
    assertThat(missing.standardError())
        .contains("no 'java' command", "requires Java 25 or later", "Set JAVA_HOME");
  }

  @Test
  @Order(4)
  void initializesAWorkspaceAgainstAPostgreSqlStore() throws Exception {
    var store = requireEnvironment().store();
    var initialized =
        run(
            "workspace",
            "init",
            "--workspace",
            workspace,
            "--catalog",
            "smoke",
            "--store-jdbc-url",
            store.jdbcUrl(),
            "--store-user",
            "postgres",
            "--store-password-env",
            "STORE_PASSWORD");
    assertSuccessful(initialized, "scalardb-analytics/workspace");
    assertThat(initialized.allOutput()).doesNotContain(store.password());
  }

  @Test
  @Order(5)
  void registersASource() throws Exception {
    var source = requireEnvironment().server(SOURCE);
    var registered = registerSource("warehouse", "SOURCE_PASSWORD", credentials);
    assertSuccessful(registered, "scalardb-analytics/source");
    assertThat(registered.allOutput()).doesNotContain(source.password());

    var metadata = run("metadata", "list", "--workspace", workspace, "--source", "warehouse");
    assertSuccessful(metadata, "scalardb-analytics/physical-metadata");
    assertThat(json(metadata).get("tables").toString()).contains("orders", "amount");
  }

  @Test
  @Order(6)
  void runsATypedQuery() throws Exception {
    var result =
        run(
            "query",
            "--workspace",
            workspace,
            "--sql",
            "SELECT id, amount, note FROM warehouse.public.orders ORDER BY id");
    assertSuccessful(result, "scalardb-analytics/query-result");
    var output = json(result);
    assertThat(output.get("fields").findValuesAsText("name"))
        .containsExactly("id", "amount", "note");
    assertThat(output.get("fields").findValuesAsText("type"))
        .containsExactly("Int(32, true)", "Int(64, true)", "Utf8");
    assertThat(output.get("rows").toString()).isEqualTo("[[1,10,\"first\"],[2,20,null]]");
    assertThat(output.get("truncated").booleanValue()).isFalse();
  }

  @Test
  @Order(7)
  void deniesUnsafeSql() throws Exception {
    // Query validation rejects these before execution. A remote read is not rejected by
    // validation; it fails when it executes, which here also proves no network download.
    var exported = scratch.resolve("exported.csv");
    var expectedCodes = new LinkedHashMap<String, String>();
    expectedCodes.put("INSTALL httpfs", "INVALID_QUERY");
    expectedCodes.put("COPY (SELECT 1) TO '" + exported + "'", "INVALID_QUERY");
    expectedCodes.put("ATTACH ':memory:' AS other", "INVALID_QUERY");
    expectedCodes.put("DELETE FROM warehouse.public.orders", "INVALID_QUERY");
    expectedCodes.put("SELECT * FROM scalardb_analytics_scan('warehouse')", "INVALID_QUERY");
    expectedCodes.put("SELECT * FROM read_csv('https://example.com/data.csv')", "EXECUTION_FAILED");
    var codes = new LinkedHashMap<String, String>();
    for (var sql : expectedCodes.keySet()) {
      var result = run("query", "--workspace", workspace, "--sql", sql);
      assertThat(result.exitCode()).as(sql).isEqualTo(1);
      assertThat(result.standardError()).as(sql).isEmpty();
      codes.put(sql, json(result).get("code").textValue());
    }
    assertThat(codes).isEqualTo(expectedCodes);
    assertThat(exported).doesNotExist();
    var unchanged =
        run(
            "query",
            "--workspace",
            workspace,
            "--sql",
            "SELECT count(*) FROM warehouse.public.orders");
    assertSuccessful(unchanged, "scalardb-analytics/query-result");
    assertThat(json(unchanged).get("rows").toString()).isEqualTo("[[2]]");
  }

  @Test
  @Order(8)
  void classifiesAndRedactsFailures() throws Exception {
    var secret = "secret-" + UUID.randomUUID();

    var wrongPassword =
        registerSource("unreachable", "WRONG_PASSWORD", Map.of("WRONG_PASSWORD", secret));
    assertError(wrongPassword, "DATA_SOURCE_UNREACHABLE");
    assertThat(wrongPassword.allOutput()).doesNotContain(secret);

    var missingCredential = run(Map.of(), "query", "--workspace", workspace, "--sql", "SELECT 1");
    assertError(missingCredential, "MISSING_CREDENTIAL");

    var sensitiveSql = "SELECT * FROM warehouse.public.missing /* " + secret + " */";
    var invalid = run("query", "--workspace", workspace, "--sql", sensitiveSql);
    assertError(invalid, "EXECUTION_FAILED");
    assertThat(invalid.allOutput()).doesNotContain(secret);

    var noWorkspace =
        run("query", "--workspace", requireEnvironment().workspace("missing"), "--sql", "SELECT 1");
    assertError(noWorkspace, "INVALID_WORKSPACE");

    var environment = requireEnvironment();
    for (var result : List.of(wrongPassword, missingCredential, invalid, noWorkspace)) {
      assertThat(result.allOutput())
          .doesNotContain(environment.store().password(), environment.server(SOURCE).password());
    }
  }

  @Test
  @Order(9)
  void thePluginRunsTheCliOnTheWorkspaceItsSessionNames() throws Exception {
    var variables = new HashMap<>(credentials);
    variables.put("SCALARDB_ANALYTICS_WORKSPACE", workspace);

    var result =
        requireEnvironment()
            .startExecutable(
                PLUGIN + "/bin/scalardb-analytics",
                variables,
                null,
                "query",
                "--sql",
                "SELECT count(*) FROM warehouse.public.orders")
            .await(RestrictedEnvironment.COMMAND_TIMEOUT);

    assertSuccessful(result, "scalardb-analytics/query-result");
    assertThat(json(result).get("rows").toString()).isEqualTo("[[2]]");
  }

  @Test
  @Order(10)
  void reviewNotificationsPassOnlyWhatTheAgentMustActOn() throws Exception {
    var rising = taskEvent("succeeded", 2);
    var failed = taskEvent("failed", 2);
    var error = "{\"schema\":\"scalardb-analytics/error\",\"code\":\"BUILD_RUN_IN_PROGRESS\"}";
    var buildOutput =
        String.join(
            "\n",
            "WARNING: a runtime warning",
            taskEvent("succeeded", 0),
            rising,
            taskEvent("succeeded", 2),
            failed,
            error,
            "");

    var result =
        requireEnvironment()
            .startExecutable(PLUGIN + "/bin/review-notifications", Map.of(), buildOutput)
            .await(RestrictedEnvironment.COMMAND_TIMEOUT);

    assertThat(result.exitCode()).isZero();
    assertThat(result.standardOutput().lines()).containsExactly(rising, failed, error);

    // Answers lower the count without an event, so the agent re-arms the filter after answering;
    // a re-armed filter passes the first event with items awaiting review, at any count.
    var afterAnswers = taskEvent("succeeded", 1);
    var rearmed =
        requireEnvironment()
            .startExecutable(PLUGIN + "/bin/review-notifications", Map.of(), afterAnswers + "\n")
            .await(RestrictedEnvironment.COMMAND_TIMEOUT);
    assertThat(rearmed.standardOutput().lines()).containsExactly(afterAnswers);
  }

  private static String taskEvent(String state, int awaitingReview) {
    return "{\"schema\":\"scalardb-analytics/build-task-event\",\"schemaVersion\":\"1\","
        + "\"task\":{\"status\":{\"state\":\""
        + state
        + "\"}},\"awaitingReview\":"
        + awaitingReview
        + "}";
  }

  private Result registerSource(String name, String passwordVariable, Map<String, String> variables)
      throws Exception {
    var source = requireEnvironment().server(SOURCE);
    var environmentVariables = new HashMap<>(credentials);
    environmentVariables.putAll(variables);
    return run(
        environmentVariables,
        "source",
        "register",
        "--workspace",
        workspace,
        "--name",
        name,
        "--type",
        "postgresql",
        "--host",
        source.host(),
        "--port",
        Integer.toString(source.port()),
        "--database",
        "postgres",
        "--user",
        "postgres",
        "--password-env",
        passwordVariable);
  }

  private Result run(String... arguments) throws Exception {
    return run(credentials, arguments);
  }

  private Result run(Map<String, String> variables, String... arguments) throws Exception {
    return requireEnvironment().run(variables, arguments);
  }

  private RestrictedEnvironment requireEnvironment() {
    if (environment == null) {
      throw new IllegalStateException("The restricted environment did not start.");
    }
    return environment;
  }

  /** Runs a command on the host without network restriction, for launcher checks only. */
  private Result runOnHost(List<String> command, Map<String, String> variables) throws Exception {
    var output = scratch.resolve(UUID.randomUUID() + ".out");
    var error = scratch.resolve(UUID.randomUUID() + ".err");
    var builder =
        new ProcessBuilder(command)
            .redirectOutput(output.toFile())
            .redirectError(error.toFile())
            .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
    builder.environment().clear();
    builder.environment().putAll(variables);
    var process = builder.start();
    assertThat(process.waitFor(1, TimeUnit.MINUTES)).isTrue();
    return new Result(
        process.exitValue(),
        Files.readString(output, StandardCharsets.UTF_8),
        Files.readString(error, StandardCharsets.UTF_8));
  }

  private static Path locate(String tool) {
    for (var directory : List.of("/bin", "/usr/bin")) {
      var candidate = Path.of(directory, tool);
      if (Files.isExecutable(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException(tool + " is not installed in /bin or /usr/bin");
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
