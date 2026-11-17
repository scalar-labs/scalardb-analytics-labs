/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scalar.db.analytics.labs.application.workspace.Workspace;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The Semantic Catalog reads and operations through the {@code semantic} commands, the way a Query
 * Agent and a semantics owner use them: build, review, present, answer, read again at the new
 * Catalog Snapshot, and cite what was read.
 */
@Testcontainers
class SemanticCatalogCliIntegrationTest {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final String POSTGRES_PASSWORD = "pg-semantic-" + UUID.randomUUID();
  private static final String STORE_PASSWORD = "store-" + UUID.randomUUID();

  /**
   * A model that infers that the orders table's rows are events, and, as the policy evaluation
   * model, asks the deciding party about it. As the translator, it says each text in Japanese by
   * prefixing it with {@code JA}. Every other Generator gets no targets.
   */
  private static final String INFERRING_ORDER_EVENTS =
      """
      input=$(cat)
      case "$input" in
        *'"targetLanguage":"ja"'*)
          printf '%s' "$input" | tail -n 1 | sed -e 's/.*"texts":/{"texts":/' -e 's/":"/":"JA /g' ;;
        *"row meaning inference generator"*)
          printf '%s' '{"targets": [{"ref": "warehouse.public.orders", "values": {"row-kind": \
      {"value": "event", "basis": "the table is named orders"}}}]}' ;;
        *'"decision": "request-consent"'*)
          printf '%s' '{"decision": "request-consent", "reason": "only the name suggests it", \
      "reviewMaterial": {"question": "Is each row of orders one order placed?", "uncertain": \
      "whether rows are events", "evidenceFor": ["the table name"], "evidenceAgainst": [], \
      "suggestedAnswer": "consent", "suggestionReasons": ["the name"]}}' ;;
        *) printf '%s' '{"targets": []}' ;;
      esac
      """;

  @Container
  private static final PostgreSQLContainer<?> STORE =
      new PostgreSQLContainer<>("postgres:16.4").withPassword(STORE_PASSWORD);

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16.4").withPassword(POSTGRES_PASSWORD);

  @TempDir Path temporaryDirectory;

  private Path workspace;
  private Map<String, String> environment;
  private String domain;

  @BeforeAll
  static void prepareSource() throws SQLException {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES_PASSWORD);
        var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE orders (id INT PRIMARY KEY, customer_id INT, amount INT)");
      statement.execute("INSERT INTO orders VALUES (1, 10, 1000), (2, 11, 2500)");
    }
  }

  @BeforeEach
  void initializeWorkspace() throws Exception {
    workspace = temporaryDirectory.resolve("workspace");
    var catalogName = "catalog_" + UUID.randomUUID().toString().replace("-", "");
    domain = catalogName.replace('_', '-');
    environment = new HashMap<>();
    environment.put("STORE_PASSWORD", STORE_PASSWORD);
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
    var script = temporaryDirectory.resolve("model.sh");
    Files.writeString(script, INFERRING_ORDER_EVENTS);
    var path = workspace.resolve(Workspace.CONFIG_FILE_NAME);
    var configuration = (ObjectNode) OBJECT_MAPPER.readTree(path.toFile());
    configuration
        .putObject("semanticCatalog")
        .putObject("languageModel")
        .put("adapter", "command")
        .put("command", "sh '" + script + "'")
        .put("model", "stub-model")
        .put("timeoutSeconds", 10);
    Files.writeString(path, OBJECT_MAPPER.writeValueAsString(configuration));
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

  @Test
  void anOwnersAnswerConfirmsWhatTheBuildLeftForReviewAndTheReadAtTheNewSnapshotCitesIt()
      throws Exception {
    assertSuccessful(semantic("build"));

    // The review list holds the inferred row kind, with the material the policy model built.
    var review = single(semantic("review"));
    assertThat(review.get("schema").textValue()).isEqualTo("scalardb-analytics/semantic-review");
    var item = onlyItem(review, "row-kind");
    assertThat(item.get("item").textValue()).isEqualTo("candidate");
    assertThat(item.toString()).contains("Is each row of orders one order placed?");
    var fact = item.get("fact").get("revision").get("fact").textValue();

    // Confirmed-only reads do not show it yet, but say how much awaits review.
    var before = single(semantic("read", "--source", "warehouse", "--recursive"));
    assertThat(before.get("level").get("counts").get("awaitingReview").intValue()).isPositive();
    assertThat(before.toString()).doesNotContain(fact);

    var presented =
        single(
            semantic(
                "present",
                "--kind",
                "confirmation",
                "--fact",
                fact,
                "--to",
                "owner",
                "--context",
                "Asked whether each row of orders is one order placed"));
    var presentation = presented.get("presentation").longValue();
    assertThat(presentation).isPositive();

    var answers = temporaryDirectory.resolve("answers.json");
    Files.writeString(
        answers,
        OBJECT_MAPPER.writeValueAsString(
            Map.of(
                "answers",
                List.of(
                    Map.of(
                        "fact",
                        fact,
                        "type",
                        "consent",
                        "wording",
                        "Yes, one row per order",
                        "presentation",
                        presentation)))));
    var answered = single(semantic("answer", "--file", answers.toString()));
    assertThat(answered.get("schema").textValue())
        .isEqualTo("scalardb-analytics/answer-submission");
    assertThat(answered.get("outcomes").get(0).get("result").textValue()).isEqualTo("CONFIRMED");
    var snapshot = answered.get("catalogSnapshot").textValue();

    // At the snapshot the answer wrote, the Fact is selected, and its revision is retrievable.
    var dataset = datasetOf(item);
    var knowledge =
        single(
            semantic(
                "knowledge",
                "--snapshot",
                snapshot,
                "--element",
                dataset,
                "--predicate",
                "row-kind"));
    assertThat(knowledge.get("catalogSnapshot").textValue()).isEqualTo(snapshot);
    var selected = knowledge.get("answers").get(0).get("addresses").get(0).get("knowledge");
    assertThat(selected.get("status").textValue()).isEqualTo("selected");
    var revision = selected.get("fact").get("revision").get("urn").textValue();
    var cited = single(semantic("fact", "--snapshot", snapshot, revision));
    assertThat(cited.get("fact").get("revision").get("state").textValue()).isEqualTo("CONFIRMED");
    assertThat(single(semantic("review")).get("groups")).isEmpty();
    assertThat(single(semantic("read", "--snapshot", snapshot, "--recursive")).toString())
        .contains(revision);
  }

  @Test
  void elementsAreFoundByKindAndParent() throws Exception {
    assertSuccessful(semantic("build"));

    var datasets = single(semantic("elements", "--kind", "dataset"));
    assertThat(datasets.get("elements")).hasSize(1);
    var dataset = datasets.get("elements").get(0).get("element").textValue();
    var fields = single(semantic("elements", "--kind", "field", "--parent", dataset));

    assertThat(fields.get("elements")).hasSize(3);
  }

  @Test
  void missingKnowledgeIsRegisteredAsUsageFeedback() throws Exception {
    var registered =
        single(
            semantic(
                "feedback",
                "--content",
                "Which orders count as sales is not known",
                "--context",
                "What were September's sales?"));

    assertThat(registered.get("schema").textValue()).isEqualTo("scalardb-analytics/usage-feedback");
    assertThat(registered.get("inputResource").textValue()).startsWith("urn:scalardb:analytics:");
    assertThat(registered.get("catalogSnapshot").textValue()).contains(":catalog-snapshot:");
  }

  @Test
  void aFailedTaskIsListedAndResumed() throws Exception {
    // A model that answers nothing readable fails the model-backed tasks.
    var path = workspace.resolve(Workspace.CONFIG_FILE_NAME);
    var configuration = (ObjectNode) OBJECT_MAPPER.readTree(path.toFile());
    ((ObjectNode) configuration.get("semanticCatalog").get("languageModel"))
        .put("command", "cat > /dev/null; echo not-json");
    Files.writeString(path, OBJECT_MAPPER.writeValueAsString(configuration));
    assertSuccessful(semantic("build"));

    var failed = single(semantic("tasks", "--state", "failed")).get("tasks");
    assertThat(failed).isNotEmpty();
    var number = failed.get(0).get("number").longValue();

    var resumed = single(semantic("resume", Long.toString(number)));

    assertThat(resumed.get("task").get("status").get("state").textValue()).isEqualTo("created");
    assertError(semantic("resume", Long.toString(number)), "SEMANTIC_CATALOG_REJECTED");
  }

  @Test
  void aPolicyVersionIsCheckedBeforeItIsSet() throws Exception {
    var check = single(semantic("policy", "check", "manual"));
    assertThat(check.get("check").get("current").get("preset").textValue()).isEqualTo("AUTO");

    var set = single(semantic("policy", "set", "manual", "--reason", "Review everything first"));

    assertThat(set.get("logRecord").textValue()).contains(":log-record:");
    assertThat(
            single(semantic("policy", "check", "auto"))
                .get("check")
                .get("current")
                .get("preset")
                .textValue())
        .isEqualTo("MANUAL");
  }

  @Test
  void invalidInputIsClassifiedAndNothingLeaksTheCredentials() throws Exception {
    assertError(semantic("read", "--snapshot", "not-a-snapshot"), "INVALID_REQUEST");
    assertError(semantic("elements", "--kind", "spreadsheet"), "INVALID_REQUEST");
    assertError(semantic("knowledge"), "INVALID_REQUEST");
    var empty = temporaryDirectory.resolve("empty.json");
    Files.writeString(empty, "{\"answers\": []}");
    assertError(semantic("answer", "--file", empty.toString()), "INVALID_REQUEST");
    assertError(
        semantic("fact", "urn:scalardb:analytics:" + domain() + ":fact:measure:sales/existence/1"),
        "NOT_FOUND");
    var future = "urn:scalardb:analytics:" + domain() + ":catalog-snapshot:999999";
    assertError(semantic("read", "--snapshot", future), "SEMANTIC_CATALOG_REJECTED");

    // Every command's output is checked for the passwords; these read the most of the catalog.
    assertSuccessful(semantic("build"));
    for (var output :
        List.of(
            semantic("read", "--recursive", "--unconfirmed"),
            semantic("review"),
            semantic("tasks"),
            semantic("languages"))) {
      assertSuccessful(output);
    }
  }

  @Test
  void everyOtherCommandClassifiesItsFailures() throws Exception {
    var unknownFact = "urn:scalardb:analytics:" + domain() + ":fact:measure:sales/existence/1";
    var unknownAddress =
        "urn:scalardb:analytics:" + domain() + ":fact-address:measure:sales/existence";

    assertError(semantic("review", "--kind", "approval"), "INVALID_REQUEST");
    assertError(
        semantic("present", "--kind", "confirmation", "--fact", unknownFact, "--context", "shown"),
        "NOT_FOUND");
    assertError(
        semantic(
            "feedback",
            "--content",
            "wrong",
            "--context",
            "a question",
            "--related-fact",
            unknownFact),
        "SEMANTIC_CATALOG_REJECTED");
    assertError(semantic("tasks", "--task", "999999"), "NOT_FOUND");
    assertError(semantic("policy", "check", "lenient"), "INVALID_REQUEST");
    assertError(semantic("policy", "set", "lenient", "--reason", "try"), "INVALID_REQUEST");
    assertError(
        semantic(
            "select",
            "--fact-address",
            unknownAddress,
            "--fact",
            unknownFact,
            "--passed-over",
            unknownFact.replace("/1", "/2"),
            "--wording",
            "the first one"),
        "SEMANTIC_CATALOG_REJECTED");
    assertError(
        semanticWithInput(
            "{\"requests\": [{\"edge\": {\"kind\": \"identifier-correspondence\", \"from\": [],"
                + " \"to\": [\""
                + unknownFact
                + "\"]}}]}",
            "knowledge",
            "--file",
            "-"),
        "INVALID_REQUEST");
  }

  @Test
  void aBatchOfRequestsIsReadFromStandardInput() throws Exception {
    assertSuccessful(semantic("build"));
    var dataset = single(semantic("elements", "--kind", "dataset")).get("elements").get(0);
    var fields =
        single(
                semantic(
                    "elements", "--kind", "field", "--parent", dataset.get("element").textValue()))
            .get("elements");
    var batch =
        OBJECT_MAPPER.writeValueAsString(
            Map.of(
                "requests",
                List.of(
                    Map.of("element", dataset.get("element").textValue(), "predicate", "existence"),
                    Map.of(
                        "edge",
                        Map.of(
                            "kind",
                            "identifier-correspondence",
                            "from",
                            List.of(fields.get(0).get("element").textValue()),
                            "to",
                            List.of(fields.get(1).get("element").textValue()))))));

    var answers = single(semanticWithInput(batch, "knowledge", "--file", "-")).get("answers");

    assertThat(answers).hasSize(2);
    assertThat(answers.get(0).get("addresses").get(0).get("knowledge").get("status").textValue())
        .isEqualTo("selected");
    // No correspondence exists between the two Fields, so the edge has no Fact Address.
    assertThat(answers.get(1).get("addresses")).isEmpty();
  }

  @Test
  void anUnreachableStoreIsReportedWithoutItsDetails() throws Exception {
    var path = workspace.resolve(Workspace.CONFIG_FILE_NAME);
    var configuration = (ObjectNode) OBJECT_MAPPER.readTree(path.toFile());
    configuration.put("storeJdbcUrl", "jdbc:postgresql://127.0.0.1:1/catalog");
    Files.writeString(path, OBJECT_MAPPER.writeValueAsString(configuration));

    var result = semantic("read");

    assertError(result, "SEMANTIC_CATALOG_UNAVAILABLE");
    assertThat(result.standardOutput()).doesNotContain("127.0.0.1:1");
  }

  @Test
  void theDomainsLanguagesAreShown() throws Exception {
    var languages = single(semantic("languages"));

    assertThat(languages.get("declared").get(0).textValue()).isEqualTo("en");
    assertThat(languages.get("primary").textValue()).isEqualTo("en");
  }

  @Test
  void aChangedPrimaryLanguageIsReportedByTheLanguagesRead() throws Exception {
    assertSuccessful(semantic("languages"));
    var path = workspace.resolve(Workspace.CONFIG_FILE_NAME);
    var configuration = (ObjectNode) OBJECT_MAPPER.readTree(path.toFile());
    var semanticCatalog = (ObjectNode) configuration.get("semanticCatalog");
    semanticCatalog.putArray("languages").add("en").add("ja");
    semanticCatalog.put("primaryLanguage", "ja");
    Files.writeString(path, OBJECT_MAPPER.writeValueAsString(configuration));

    assertError(semantic("languages"), "RECORDING_LANGUAGE_CHANGED");
  }

  @Test
  void aReaderOfAnotherDeclaredLanguageGetsTranslationsBesideTheUnchangedReview() throws Exception {
    assertSuccessful(semantic("build"));
    // The domain records in English; Japanese is declared for its other readers.
    var path = workspace.resolve(Workspace.CONFIG_FILE_NAME);
    var configuration = (ObjectNode) OBJECT_MAPPER.readTree(path.toFile());
    ((ObjectNode) configuration.get("semanticCatalog")).putArray("languages").add("en").add("ja");
    Files.writeString(path, OBJECT_MAPPER.writeValueAsString(configuration));
    var originals = single(semantic("review"));

    // Before the translation task runs, the texts come pending and the read requests them.
    var pending = single(semantic("review", "--language", "ja"));

    assertThat(pending.get("groups")).isEqualTo(originals.get("groups"));
    assertThat(pending.get("language").textValue()).isEqualTo("ja");
    assertThat(pending.get("recordingLanguage").textValue()).isEqualTo("en");
    var evaluation = onlyItem(pending, "row-kind").get("evaluation").get("logRecord").textValue();
    var text = pending.get("texts").get(evaluation);
    assertThat(text.get("status").textValue()).isEqualTo("pending");
    assertThat(text.get("originals").get("reviewMaterial.question").textValue())
        .isEqualTo("Is each row of orders one order placed?");
    assertThat(pending.get("translationTask").longValue()).isPositive();

    // After a build run, the same read returns each original beside its translation.
    assertSuccessful(semantic("build"));
    var translated = single(semantic("review", "--language", "ja"));

    assertThat(translated.get("groups")).isEqualTo(originals.get("groups"));
    assertThat(translated.has("translationTask")).isFalse();
    var said = translated.get("texts").get(evaluation);
    assertThat(said.get("status").textValue()).isEqualTo("translated");
    assertThat(said.get("originals").get("reviewMaterial.question").textValue())
        .isEqualTo("Is each row of orders one order placed?");
    assertThat(said.get("translation").get("language").textValue()).isEqualTo("ja");
    assertThat(said.get("translation").get("texts").get("reviewMaterial.question").textValue())
        .isEqualTo("JA Is each row of orders one order placed?");

    // The recording language, or no language, returns originals only and requests nothing.
    var tasks = single(semantic("tasks")).get("tasks").size();
    var recording = single(semantic("review", "--language", "en"));
    assertThat(recording.get("texts")).isEmpty();
    assertThat(recording.has("translationTask")).isFalse();
    assertThat(originals.has("texts")).isFalse();
    assertThat(single(semantic("tasks")).get("tasks")).hasSize(tasks);

    // The other reads take the language too, and an undeclared one is invalid input.
    var dataset = datasetOf(onlyItem(originals, "row-kind"));
    var read = single(semantic("read", "--recursive", "--unconfirmed", "--language", "ja"));
    assertThat(read.get("language").textValue()).isEqualTo("ja");
    var knowledge = single(semantic("knowledge", "--element", dataset, "--language", "ja"));
    assertThat(knowledge.get("language").textValue()).isEqualTo("ja");
    assertThat(single(semantic("elements", "--term", "orders", "--language", "ja")).get("elements"))
        .isEmpty();
    assertError(semantic("review", "--language", "fr"), "INVALID_REQUEST");
    assertError(semantic("elements", "--term", "orders", "--language", "fr"), "INVALID_REQUEST");
    assertError(semantic("elements", "--language", "ja"), "INVALID_REQUEST");
  }

  @Test
  void aSessionThatNamesTheWorkspaceInTheEnvironmentOmitsTheOption() throws Exception {
    environment.put(WorkspaceOption.ENVIRONMENT_VARIABLE, workspace.toString());

    var languages = single(execute("semantic", "languages"));

    assertThat(languages.get("primary").textValue()).isEqualTo("en");
  }

  private String domain() {
    return domain;
  }

  private static JsonNode onlyItem(JsonNode review, String predicate) {
    var items = new ArrayList<JsonNode>();
    for (var group : review.get("groups")) {
      for (var item : group.get("items")) {
        if (item.get("target").get("predicate").textValue().equals(predicate)) {
          items.add(item);
        }
      }
    }
    assertThat(items).hasSize(1);
    return items.getFirst();
  }

  private static String datasetOf(JsonNode item) {
    return item.get("target").get("element").textValue();
  }

  private Result semantic(String... arguments) {
    return semanticWithInput("", arguments);
  }

  private Result semanticWithInput(String standardInput, String... arguments) {
    var command = new ArrayList<String>();
    command.add("semantic");
    command.add(arguments[0]);
    if (arguments[0].equals("policy")) {
      command.add(arguments[1]);
      command.add("--workspace");
      command.add(workspace.toString());
      command.addAll(List.of(arguments).subList(2, arguments.length));
    } else {
      command.add("--workspace");
      command.add(workspace.toString());
      command.addAll(List.of(arguments).subList(1, arguments.length));
    }
    return executeWithInput(standardInput, command.toArray(String[]::new));
  }

  private Result execute(String... arguments) {
    return executeWithInput("", arguments);
  }

  /** Runs the CLI; no output of any command, success or failure, holds a password. */
  private Result executeWithInput(String standardInput, String... arguments) {
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
    var result =
        new Result(
            exitCode,
            standardOutput.toString(StandardCharsets.UTF_8),
            standardError.toString(StandardCharsets.UTF_8));
    for (var output : List.of(result.standardOutput(), result.standardError())) {
      assertThat(output).doesNotContain(POSTGRES_PASSWORD).doesNotContain(STORE_PASSWORD);
    }
    return result;
  }

  private static JsonNode single(Result result) throws Exception {
    assertSuccessful(result);
    var lines = result.standardOutput().strip().split("\\R");
    assertThat(lines).hasSize(1);
    var output = OBJECT_MAPPER.readTree(lines[0]);
    assertThat(output.get("schemaVersion").textValue()).isEqualTo("1");
    return output;
  }

  private static void assertSuccessful(Result result) {
    assertThat(result.exitCode()).as(result.standardOutput()).isZero();
    assertThat(result.standardError()).isEmpty();
  }

  private static void assertError(Result result, String code) throws Exception {
    assertThat(result.exitCode()).as(result.standardOutput()).isEqualTo(1);
    assertThat(result.standardError()).isEmpty();
    var output = OBJECT_MAPPER.readTree(result.standardOutput().strip());
    assertThat(output.get("schema").textValue()).isEqualTo("scalardb-analytics/error");
    assertThat(output.get("schemaVersion").textValue()).isEqualTo("1");
    assertThat(output.get("code").textValue()).isEqualTo(code);
  }

  private record Result(int exitCode, String standardOutput, String standardError) {}
}
