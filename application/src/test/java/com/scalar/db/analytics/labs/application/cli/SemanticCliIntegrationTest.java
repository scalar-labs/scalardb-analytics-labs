/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Drives the agent path through the CLI: build, review and answer until nothing awaits a judgement,
 * find a Measure by its business term, request its knowledge at a pinned snapshot, and run SQL that
 * cites what it used. A scripted command stands in for the language model.
 */
@Testcontainers
class SemanticCliIntegrationTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String MYSQL_PASSWORD = "mysql-" + UUID.randomUUID();

  @Container
  private static final PostgreSQLContainer<?> STORE =
      new PostgreSQLContainer<>("postgres:16.4").withPassword("store-" + UUID.randomUUID());

  @Container
  private static final PostgreSQLContainer<?> SHOP =
      new PostgreSQLContainer<>("postgres:16.4").withPassword("shop-" + UUID.randomUUID());

  @Container
  private static final GenericContainer<?> MYSQL =
      new GenericContainer<>("mysql:8.4")
          .withEnv("MYSQL_ROOT_PASSWORD", "root-" + UUID.randomUUID())
          .withEnv("MYSQL_DATABASE", "crm")
          .withEnv("MYSQL_USER", "reader")
          .withEnv("MYSQL_PASSWORD", MYSQL_PASSWORD)
          .withExposedPorts(3306)
          .waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306 .*", 1));

  @TempDir Path directory;

  @BeforeAll
  static void prepareSources() throws SQLException {
    try (var connection =
            DriverManager.getConnection(SHOP.getJdbcUrl(), SHOP.getUsername(), SHOP.getPassword());
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE customers (id INT PRIMARY KEY, email VARCHAR(100) NOT NULL UNIQUE)");
      statement.execute(
          "CREATE TABLE orders (id INT PRIMARY KEY, customer_id INT NOT NULL REFERENCES customers(id), status VARCHAR(20) NOT NULL, amount INT NOT NULL, ordered_on DATE NOT NULL)");
      statement.execute(
          "INSERT INTO customers VALUES (1, 'a@example.com'), (2, 'b@example.com'), (3, 'c@example.com')");
      statement.execute(
          "INSERT INTO orders VALUES (10, 1, 'shipped', 100, DATE '2026-09-01'), (11, 1, 'cancelled', 50, DATE '2026-09-02'), (12, 2, 'shipped', 70, DATE '2026-09-03')");
    }
    try (var connection =
            DriverManager.getConnection(
                "jdbc:mariadb://"
                    + MYSQL.getHost()
                    + ":"
                    + MYSQL.getMappedPort(3306)
                    + "/crm?sslMode=trust",
                "reader",
                MYSQL_PASSWORD);
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE members (member_id INT PRIMARY KEY, email VARCHAR(100) NOT NULL, `rank` VARCHAR(10) NOT NULL)");
      statement.execute(
          "INSERT INTO members VALUES (100, 'A@example.com', 'gold'), (101, 'z@example.com', 'silver')");
    }
  }

  /** A stand-in language model: answers by which Generator the prompt comes from. */
  private Path scriptedModel() throws Exception {
    var answers = directory.resolve("answers");
    Files.createDirectories(answers);
    Files.writeString(
        answers.resolve("field.json"),
        """
        {"fields": [{"field": "shop.public.orders.status", "meaning": "Order status", "valueKind": "classification",
          "codes": {"shipped": "Shipped", "cancelled": "Cancelled"}, "documents": ["D1"]},
         {"field": "shop.public.orders.amount", "meaning": "Order amount excluding tax", "valueKind": "amount", "unit": "JPY", "documents": ["D1"]}]}""");
    Files.writeString(
        answers.resolve("row.json"),
        """
        {"datasets": [{"dataset": "shop.public.orders", "concept": {"name": "order", "label": "Order", "meaning": "A purchase"}, "rowKind": "event"}]}""");
    Files.writeString(
        answers.resolve("analytical.json"),
        """
        {"measures": [{"name": "sales", "label": "Sales", "meaning": "Revenue of orders that are not cancelled", "dataset": "shop.public.orders",
          "function": "sum", "expression": "amount", "filter": "status <> 'cancelled'", "timeField": "ordered_on", "documents": ["D1"]}],
         "terms": [{"text": "売上", "refersTo": "measure:sales", "documents": ["D1"]}]}""");
    Files.writeString(
        answers.resolve("identifier.json"),
        """
        {"joins": [], "identifiers": [{"from": "shop.public.customers.email", "to": "crm.crm.members.email", "normalization": "lower-trim",
          "definition": "A customer and a member with the same email address are the same person.", "documents": ["D1"]}]}""");
    var script = directory.resolve("model.sh");
    Files.writeString(
        script,
        """
        #!/bin/sh
        prompt=$(cat)
        dir='%s'
        case "$prompt" in
          *"Field meaning inference Generator"*) cat "$dir/field.json" ;;
          *"row meaning inference Generator"*) cat "$dir/row.json" ;;
          *"analytical semantics inference Generator"*) cat "$dir/analytical.json" ;;
          *"join and identifier correspondence inference Generator"*) cat "$dir/identifier.json" ;;
          *) echo '{}' ;;
        esac
        """
            .formatted(answers));
    script.toFile().setExecutable(true);
    return script;
  }

  @Test
  void anAgentBuildsReviewsFindsKnowledgeAndRunsCitedSql() throws Exception {
    var environment = new HashMap<String, String>();
    environment.put("STORE_PASSWORD", STORE.getPassword());
    environment.put("SHOP_PASSWORD", SHOP.getPassword());
    environment.put("SCALARDB_ANALYTICS_LLM", "command");
    environment.put("SCALARDB_ANALYTICS_LLM_COMMAND", scriptedModel().toString());
    var workspace = directory.resolve("workspace").toString();
    var catalog = "c" + UUID.randomUUID().toString().replace("-", "");

    ok(
        environment,
        "",
        "scalardb-analytics/workspace",
        "workspace",
        "init",
        "--workspace",
        workspace,
        "--catalog",
        catalog,
        "--store-jdbc-url",
        STORE.getJdbcUrl(),
        "--store-user",
        STORE.getUsername(),
        "--store-password-env",
        "STORE_PASSWORD",
        "--semantics-owner",
        "owner");
    ok(
        environment,
        "",
        "scalardb-analytics/source",
        "source",
        "register",
        "--workspace",
        workspace,
        "--name",
        "shop",
        "--type",
        "postgresql",
        "--host",
        SHOP.getHost(),
        "--port",
        Integer.toString(SHOP.getMappedPort(5432)),
        "--database",
        SHOP.getDatabaseName(),
        "--user",
        SHOP.getUsername(),
        "--password-env",
        "SHOP_PASSWORD");
    ok(
        environment,
        MYSQL_PASSWORD + "\n",
        "scalardb-analytics/source",
        "source",
        "register",
        "--workspace",
        workspace,
        "--name",
        "crm",
        "--type",
        "mysql",
        "--host",
        MYSQL.getHost(),
        "--port",
        Integer.toString(MYSQL.getMappedPort(3306)),
        "--database",
        "crm",
        "--user",
        "reader",
        "--password-stdin");
    ok(
        environment,
        "",
        "scalardb-analytics/semantic-observation",
        "semantic",
        "observe",
        "--workspace",
        workspace,
        "--kind",
        "document",
        "--title",
        "Glossary",
        "--text",
        "売上 is the sum of amount over orders that are not cancelled.");

    // The agent starts a watching build in the background, then presents and answers whatever has
    // become reviewable while the build keeps running the tasks the answers create.
    var watcher =
        java.util.concurrent.CompletableFuture.supplyAsync(
            () ->
                run(
                    environment,
                    "",
                    "semantic",
                    "build",
                    "--workspace",
                    workspace,
                    "--watch",
                    "--idle-seconds",
                    "8",
                    "--poll-seconds",
                    "1"));
    var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(3);
    var round = 0;
    var answeredFacts = 0;
    while (System.nanoTime() < deadline) {
      var queue =
          ok(
              environment,
              "",
              "scalardb-analytics/semantic-review-queue",
              "semantic",
              "review",
              "--workspace",
              workspace,
              "--unpresented");
      if (queue.path("items").isEmpty()) {
        var status =
            ok(
                environment,
                "",
                "scalardb-analytics/semantic-status",
                "semantic",
                "status",
                "--workspace",
                workspace);
        var summary = status.path("summary");
        if (answeredFacts > 0
            && summary.path("createdTasks").asInt() == 0
            && summary.path("runningTasks").asInt() == 0
            && summary.path("awaitingReview").asInt() == 0) {
          break;
        }
        Thread.sleep(500);
        continue;
      }
      round++;
      var facts = new ArrayList<String>();
      queue
          .path("items")
          .forEach(
              item -> item.path("facts").forEach(fact -> facts.add(fact.path("fact").asText())));
      var present =
          new ArrayList<>(
              List.of(
                  "semantic",
                  "present",
                  "--workspace",
                  workspace,
                  "--kind",
                  "confirmation",
                  "--to",
                  "owner",
                  "--context",
                  "chat round " + round));
      facts.forEach(fact -> present.addAll(List.of("--fact", fact)));
      var presentation =
          ok(
                  environment,
                  "",
                  "scalardb-analytics/semantic-presentation",
                  present.toArray(String[]::new))
              .path("presentation")
              .asText();
      var answers = JSON.createObjectNode();
      var list = answers.putArray("answers");
      facts.forEach(
          fact ->
              list.addObject()
                  .put("fact", fact)
                  .put("type", "consent")
                  .put("wording", "Yes")
                  .put("presentation", presentation));
      var answered =
          ok(
              environment,
              answers.toString(),
              "scalardb-analytics/semantic-answers",
              "semantic",
              "answer",
              "--workspace",
              workspace,
              "--principal",
              "owner",
              "--stdin");
      assertThat(answered.path("submitted").path("answers"))
          .allSatisfy(
              answer -> assertThat(answer.path("transition").asText()).isEqualTo("confirmed"));
      answeredFacts += facts.size();
    }
    var watched = watcher.get(2, java.util.concurrent.TimeUnit.MINUTES);
    var lines = watched.strip().split("\n");
    var events =
        java.util.Arrays.stream(lines)
            .limit(lines.length - 1L)
            .map(SemanticCliIntegrationTest::json)
            .toList();
    assertThat(events)
        .isNotEmpty()
        .allSatisfy(
            event -> {
              assertThat(event.path("schema").asText())
                  .isEqualTo("scalardb-analytics/semantic-build-event");
              assertThat(event.path("state").asText()).isEqualTo("succeeded");
            });
    var finished = json(lines[lines.length - 1]);
    assertThat(finished.path("schema").asText()).isEqualTo("scalardb-analytics/semantic-build");
    assertThat(finished.path("failedTasks")).isEmpty();
    assertThat(round).isGreaterThan(1);

    var question =
        ok(
            environment,
            "",
            "scalardb-analytics/semantic-question",
            "semantic",
            "question",
            "--workspace",
            workspace,
            "--text",
            "9月の売上は?");
    var snapshot = question.path("snapshot").asText();
    var found =
        ok(
            environment,
            "",
            "scalardb-analytics/semantic-elements",
            "semantic",
            "elements",
            "--workspace",
            workspace,
            "--snapshot",
            snapshot,
            "--term",
            "売上");
    assertThat(found.path("elements")).hasSize(1);
    var measure = found.path("elements").get(0).path("element").asText();
    var knowledge =
        ok(
            environment,
            "",
            "scalardb-analytics/semantic-knowledge",
            "semantic",
            "knowledge",
            "--workspace",
            workspace,
            "--snapshot",
            snapshot,
            "--request",
            measure + "=aggregation-rule",
            "--request",
            measure + "=filter");
    var rule = knowledge.path("responses").get(0).path("addresses").get(0).path("selected");
    var filter = knowledge.path("responses").get(1).path("addresses").get(0).path("selected");
    assertThat(rule.path("value").path("expression").asText()).isEqualTo("amount");
    assertThat(filter.path("value").path("condition").asText()).isEqualTo("status <> 'cancelled'");

    var overview =
        ok(
            environment,
            "",
            "scalardb-analytics/semantic-overview",
            "semantic",
            "overview",
            "--workspace",
            workspace,
            "--kind",
            "identifier-correspondence");
    var correspondence = overview.path("elements").get(0).path("knowledge");
    assertThat(
            correspondence
                .path("set-relationship")
                .get(0)
                .path("value")
                .path("relationship")
                .asText())
        .isEqualTo("overlap");

    var query =
        ok(
            environment,
            "",
            "scalardb-analytics/query-result",
            "query",
            "--workspace",
            workspace,
            "--sql",
            "SELECT sum(amount) FROM shop.public.orders WHERE status <> 'cancelled'",
            "--snapshot",
            snapshot,
            "--cite",
            rule.path("factRevision").asText(),
            "--cite",
            filter.path("factRevision").asText(),
            "--question",
            question.path("question").asText());
    assertThat(query.path("rows").get(0).get(0).asText()).isEqualTo("170");
    assertThat(query.path("citation").path("factRevisions")).hasSize(2);
    assertThat(query.path("citation").path("snapshot").asText()).isEqualTo(snapshot);

    var verification =
        ok(
            environment,
            "",
            "scalardb-analytics/semantic-verification",
            "semantic",
            "verify",
            "--workspace",
            workspace);
    assertThat(verification.path("consistent").asBoolean()).isTrue();
  }

  private static JsonNode json(String line) {
    try {
      return JSON.readTree(line);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(line, e);
    }
  }

  /** Runs the CLI and returns its standard output, failing on a non-zero exit. */
  private static String run(Map<String, String> environment, String input, String... arguments) {
    var standardOutput = new ByteArrayOutputStream();
    var standardError = new ByteArrayOutputStream();
    int exitCode;
    try (var cli =
        new ScalarDbAnalyticsCli(
            new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(standardOutput, true, StandardCharsets.UTF_8),
            new PrintStream(standardError, true, StandardCharsets.UTF_8),
            environment)) {
      exitCode = cli.execute(arguments);
    }
    var output = standardOutput.toString(StandardCharsets.UTF_8);
    assertThat(exitCode).as(output + standardError.toString(StandardCharsets.UTF_8)).isZero();
    return output;
  }

  private static JsonNode ok(
      Map<String, String> environment, String input, String schema, String... arguments)
      throws Exception {
    var standardOutput = new ByteArrayOutputStream();
    var standardError = new ByteArrayOutputStream();
    int exitCode;
    try (var cli =
        new ScalarDbAnalyticsCli(
            new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(standardOutput, true, StandardCharsets.UTF_8),
            new PrintStream(standardError, true, StandardCharsets.UTF_8),
            environment)) {
      exitCode = cli.execute(arguments);
    }
    var output = standardOutput.toString(StandardCharsets.UTF_8).strip();
    assertThat(exitCode).as(output + standardError.toString(StandardCharsets.UTF_8)).isZero();
    var json = JSON.readTree(output);
    assertThat(json.path("schema").asText()).isEqualTo(schema);
    return json;
  }
}
