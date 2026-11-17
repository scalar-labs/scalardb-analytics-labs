/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scalar.db.analytics.labs.application.construction.QueryEngineSqlParser;
import com.scalar.db.analytics.labs.application.workspace.Workspace;
import com.scalar.db.analytics.labs.semantic.Point;
import com.scalar.db.analytics.labs.semantic.SemanticCatalogStore;
import com.scalar.db.analytics.labs.semantic.StoreSettings;
import com.scalar.db.analytics.labs.semantic.model.BuildTask;
import com.scalar.db.analytics.labs.semantic.model.Hierarchy;
import com.scalar.db.analytics.labs.semantic.model.Review;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Running the Semantic Catalog build tasks that registering a data source creates, through {@code
 * semantic build}, and registering the documents those tasks read, through {@code semantic document
 * register}.
 */
@Testcontainers
class SemanticBuildCliIntegrationTest {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final String POSTGRES_PASSWORD = "pg-build-" + UUID.randomUUID();

  /**
   * A model that infers nothing: meaning inference asks it about every confirmed Dataset and Field
   * and gets no value, so no candidate reaches the policy evaluation model, which would fail on
   * this answer.
   */
  private static final String INFERRING_NOTHING = "cat > /dev/null; echo '{\"targets\": []}'";

  @Container
  private static final PostgreSQLContainer<?> STORE =
      new PostgreSQLContainer<>("postgres:16.4").withPassword("store-" + UUID.randomUUID());

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16.4").withPassword(POSTGRES_PASSWORD);

  @TempDir Path temporaryDirectory;

  private Path workspace;
  private String domain;
  private Map<String, String> environment;

  @BeforeAll
  static void prepareSource() throws SQLException {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES_PASSWORD);
        var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE orders (id INT PRIMARY KEY, customer_id INT, amount INT)");
      statement.execute(
          "CREATE TABLE order_lines (order_id INT REFERENCES orders (id), line INT,"
              + " PRIMARY KEY (order_id, line))");
    }
  }

  @BeforeEach
  void initializeWorkspace() throws Exception {
    workspace = temporaryDirectory.resolve("workspace");
    var catalogName = "catalog_" + UUID.randomUUID().toString().replace("-", "");
    domain = catalogName.replace('_', '-');
    environment = new HashMap<>();
    environment.put("STORE_PASSWORD", STORE.getPassword());
    environment.put("POSTGRES_PASSWORD", POSTGRES_PASSWORD);
    assertSuccessful(
        execute(
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
            "STORE_PASSWORD"));
  }

  @Test
  void buildConfirmsWhatTheRegisteredTablesDeclare() throws Exception {
    // Enforced declarations settle everything without the policy evaluation model.
    configureLanguageModel(INFERRING_NOTHING);
    registerSource();
    assertThat(buildTasks()).isNotEmpty().allMatch(task -> isState(task, BuildTask.State.CREATED));

    var built = execute("semantic", "build", "--workspace", workspace.toString());

    assertThat(built.exitCode()).isZero();
    assertThat(built.standardError()).isEmpty();
    var lines = lines(built);
    var events = lines.subList(0, lines.size() - 1);
    assertThat(events)
        .isNotEmpty()
        .allSatisfy(
            event -> {
              assertThat(event.get("schema").textValue())
                  .isEqualTo("scalardb-analytics/build-task-event");
              assertThat(event.get("task").get("status").get("state").textValue())
                  .isEqualTo("succeeded");
              assertThat(event.get("awaitingReview").intValue()).isZero();
            });
    var summary = lines.getLast();
    assertThat(summary.get("schema").textValue()).isEqualTo("scalardb-analytics/build-run");
    assertThat(summary.get("succeeded").intValue()).isEqualTo(events.size());
    assertThat(summary.get("failed").intValue()).isZero();
    assertThat(buildTasks()).allMatch(task -> isState(task, BuildTask.State.SUCCEEDED));
    assertThat(rootCounts().confirmed()).isPositive();
  }

  @Test
  void theWorkspacesModelInputSettingsDecideWhatReachesTheModel() throws Exception {
    var prompts = temporaryDirectory.resolve("prompts.txt");
    configureLanguageModel("cat >> '" + prompts + "'; echo '{\"targets\": []}'");
    var path = workspace.resolve(Workspace.CONFIG_FILE_NAME);
    var configuration = (ObjectNode) OBJECT_MAPPER.readTree(path.toFile());
    ((ObjectNode) configuration.get("semanticCatalog"))
        .putObject("modelInput")
        .put("countStatistics", false);
    Files.writeString(path, OBJECT_MAPPER.writeValueAsString(configuration));
    registerSource();

    assertSuccessful(execute("semantic", "build", "--workspace", workspace.toString()));

    var asked = Files.readString(prompts);
    assertThat(asked).contains("\"tableMetadata\" :").doesNotContain("nullRatio");
    assertThat(buildTasks())
        .anySatisfy(
            task ->
                assertThat(task.status())
                    .isInstanceOfSatisfying(
                        BuildTask.Status.Succeeded.class,
                        succeeded ->
                            assertThat(succeeded.result())
                                .isInstanceOfSatisfying(
                                    BuildTask.Result.Generated.class,
                                    generated ->
                                        assertThat(generated.missingInputs())
                                            .anyMatch(
                                                missing ->
                                                    missing.startsWith("the count statistics of")
                                                        && missing.endsWith(
                                                            "which the domain's settings"
                                                                + " withhold")))));
  }

  @Test
  void buildWithoutALanguageModelFailsBeforeRunning() throws Exception {
    registerSource();

    var built = execute("semantic", "build", "--workspace", workspace.toString());

    assertError(built, "LANGUAGE_MODEL_NOT_CONFIGURED");
    assertThat(buildTasks()).allMatch(task -> isState(task, BuildTask.State.CREATED));
  }

  @Test
  void watchingRunHoldsTheBuildersPlaceUntilAskedToStop() throws Exception {
    configureLanguageModel(INFERRING_NOTHING);
    var standardOutput = new ByteArrayOutputStream();
    try (var cli = cli(standardOutput);
        var executor = Executors.newSingleThreadExecutor()) {
      var watching =
          executor.submit(
              () ->
                  cli.execute("semantic", "build", "--watch", "--workspace", workspace.toString()));
      // Tasks created while the run waits are picked up.
      registerSource();
      awaitNoCreatedTask();

      var second = execute("semantic", "build", "--workspace", workspace.toString());
      assertError(second, "BUILD_RUN_IN_PROGRESS");

      awaitStopRequested(cli);
      // A second interrupt is not taken, so the process would stop at once.
      assertThat(cli.cancel()).isFalse();
      assertThat(watching.get(30, TimeUnit.SECONDS)).isZero();
    }
    var lines = lines(new Result(0, standardOutput.toString(StandardCharsets.UTF_8), ""));
    assertThat(lines.getLast().get("schema").textValue()).isEqualTo("scalardb-analytics/build-run");
    assertThat(lines.getLast().get("succeeded").intValue())
        .isEqualTo(lines.size() - 1)
        .isPositive();
  }

  @Test
  void aRegisteredDocumentIsObservedOnceForEachChangeAndReachesTheModel() throws Exception {
    var glossary = temporaryDirectory.resolve("glossary.md");
    Files.writeString(glossary, "# Glossary\n\n## orders\n\nAn order is one purchase.\n");

    var first =
        execute(
            "semantic",
            "document",
            "register",
            "--workspace",
            workspace.toString(),
            glossary.toString());

    assertSuccessful(first);
    var registered = lines(first).getFirst();
    assertThat(registered.get("schema").textValue())
        .isEqualTo("scalardb-analytics/document-registration");
    assertThat(registered.get("recorded").booleanValue()).isTrue();
    var resource = registered.get("inputResource").textValue();

    var unchanged = lines(registerAgain(glossary, resource)).getFirst();
    assertThat(unchanged.get("recorded").booleanValue()).isFalse();
    assertThat(unchanged.get("inputObservation").textValue())
        .isEqualTo(registered.get("inputObservation").textValue());

    Files.writeString(
        glossary, "# Glossary\n\n## orders\n\nAn order is one purchase; amounts are in JPY.\n");
    var changed = lines(registerAgain(glossary, resource)).getFirst();
    assertThat(changed.get("recorded").booleanValue()).isTrue();
    assertThat(changed.get("inputResource").textValue()).isEqualTo(resource);
    assertThat(changed.get("inputObservation").textValue())
        .isNotEqualTo(registered.get("inputObservation").textValue());
    // Each new observation triggers the one run not started yet of each Generator that reads
    // documents.
    assertThat(buildTasks())
        .extracting(BuildTask::work)
        .containsExactly(
            new BuildTask.Work.GeneratorRun("concept-inference"),
            new BuildTask.Work.GeneratorRun("analytical-semantics-inference"),
            new BuildTask.Work.GeneratorRun("identifier-correspondence-inference"));
    assertThat(buildTasks())
        .allSatisfy(
            task -> {
              assertThat(isState(task, BuildTask.State.CREATED)).isTrue();
              assertThat(task.triggers()).hasSize(2);
            });

    var prompts = temporaryDirectory.resolve("prompts.txt");
    configureLanguageModel("cat >> '" + prompts + "'; echo '{\"targets\": []}'");
    registerSource();
    assertSuccessful(execute("semantic", "build", "--workspace", workspace.toString()));

    assertThat(Files.readString(prompts)).contains("amounts are in JPY");
  }

  @Test
  void aDocumentResourceThatDoesNotExistIsAnError() throws Exception {
    var glossary = temporaryDirectory.resolve("glossary.md");
    Files.writeString(glossary, "# Glossary\n");

    assertError(registerAgain(glossary, "not-a-resource"), "DOCUMENT_REGISTRATION_FAILED");
  }

  private Result registerAgain(Path document, String resource) {
    return execute(
        "semantic",
        "document",
        "register",
        "--workspace",
        workspace.toString(),
        "--resource",
        resource,
        document.toString());
  }

  private void awaitNoCreatedTask() throws InterruptedException {
    var deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (System.nanoTime() < deadline) {
      var tasks = buildTasks();
      if (!tasks.isEmpty()
          && tasks.stream().noneMatch(task -> isState(task, BuildTask.State.CREATED))
          && tasks.stream().noneMatch(task -> isState(task, BuildTask.State.RUNNING))) {
        return;
      }
      Thread.sleep(200);
    }
    throw new AssertionError("The watching run did not run the created tasks");
  }

  private static void awaitStopRequested(ScalarDbAnalyticsCli cli) throws InterruptedException {
    var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      if (cli.cancel()) {
        return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("The watching run did not take the interrupt");
  }

  private void configureLanguageModel(String command) throws Exception {
    var path = workspace.resolve(Workspace.CONFIG_FILE_NAME);
    var configuration = (ObjectNode) OBJECT_MAPPER.readTree(path.toFile());
    configuration
        .putObject("semanticCatalog")
        .putObject("languageModel")
        .put("adapter", "command")
        .put("command", command)
        .put("model", "stub-model")
        .put("timeoutSeconds", 10);
    Files.writeString(path, OBJECT_MAPPER.writeValueAsString(configuration));
  }

  private void registerSource() throws Exception {
    assertSuccessful(
        execute(
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
            "POSTGRES_PASSWORD"));
  }

  private static boolean isState(BuildTask task, BuildTask.State state) {
    return task.status().state() == state;
  }

  private List<BuildTask> buildTasks() {
    try (var store = store()) {
      return store.buildTasks(domain);
    }
  }

  private Hierarchy.Counts rootCounts() {
    try (var store = store()) {
      return store.read(
          domain,
          Point.LATEST,
          snapshot -> snapshot.read(Review.Position.ROOT, Hierarchy.Depth.CHILDREN).counts());
    }
  }

  private static SemanticCatalogStore store() {
    return SemanticCatalogStore.open(
        new StoreSettings(STORE.getJdbcUrl(), STORE.getUsername(), STORE.getPassword()),
        QueryEngineSqlParser.INSTANCE);
  }

  private ScalarDbAnalyticsCli cli(ByteArrayOutputStream standardOutput) {
    return new ScalarDbAnalyticsCli(
        new ByteArrayInputStream(new byte[0]),
        new PrintStream(standardOutput, true, StandardCharsets.UTF_8),
        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
        environment);
  }

  private Result execute(String... arguments) {
    var standardOutput = new ByteArrayOutputStream();
    var standardError = new ByteArrayOutputStream();
    int exitCode;
    try (var cli =
        new ScalarDbAnalyticsCli(
            new ByteArrayInputStream(new byte[0]),
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

  private static List<JsonNode> lines(Result result) throws Exception {
    var lines = new ArrayList<JsonNode>();
    for (var line : result.standardOutput().strip().split("\\R")) {
      lines.add(OBJECT_MAPPER.readTree(line));
    }
    return lines;
  }

  private static void assertSuccessful(Result result) {
    assertThat(result.exitCode()).isZero();
    assertThat(result.standardError()).isEmpty();
  }

  private static void assertError(Result result, String code) throws Exception {
    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.standardError()).isEmpty();
    var output = OBJECT_MAPPER.readTree(result.standardOutput().strip());
    assertThat(output.get("schema").textValue()).isEqualTo("scalardb-analytics/error");
    assertThat(output.get("code").textValue()).isEqualTo(code);
  }

  private record Result(int exitCode, String standardOutput, String standardError) {}
}
