/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.BuildTask;
import com.scalar.db.analytics.labs.semantic.Outputs;
import com.scalar.db.analytics.labs.semantic.SemanticCatalog;
import com.scalar.db.analytics.labs.semantic.SemanticCatalog.AnswerInput;
import com.scalar.db.analytics.labs.semantic.SemanticCatalog.Configuration;
import com.scalar.db.analytics.labs.semantic.SnapshotReads.Request;
import com.scalar.db.analytics.labs.semantic.SqlExecutor;
import com.scalar.db.analytics.labs.semantic.generator.DataAccess.DataAccessException;
import com.scalar.db.analytics.labs.semantic.model.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Json;
import com.scalar.db.analytics.labs.semantic.model.Predicates;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.AnswerType;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ObservationKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Target;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the standard Generators over two tables served by an in-memory DuckDB, with a scripted
 * language model, from table metadata registration to knowledge found by a business term.
 */
class GeneratorsTest {
  private static final String DOMAIN = "sales";
  private static final String OWNER = "owner";
  private static final String ORDERS = Urns.dataset(DOMAIN, "shop.public.orders");
  private static final String CUSTOMERS = Urns.dataset(DOMAIN, "shop.public.customers");

  @TempDir Path directory;
  private Connection data;
  private final List<String> prompts = new ArrayList<>();

  @BeforeEach
  void setUp() throws SQLException {
    data = DriverManager.getConnection("jdbc:duckdb:");
    try (var statement = data.createStatement()) {
      statement.execute("ATTACH ':memory:' AS shop");
      statement.execute("CREATE SCHEMA shop.public");
      statement.execute(
          "CREATE TABLE shop.public.customers (id INTEGER PRIMARY KEY, email VARCHAR NOT NULL)");
      statement.execute(
          "CREATE TABLE shop.public.orders (id INTEGER PRIMARY KEY, customer_id INTEGER NOT NULL, status VARCHAR NOT NULL, amount BIGINT NOT NULL, ordered_on DATE NOT NULL)");
      statement.execute(
          "INSERT INTO shop.public.customers VALUES (1, 'a@example.com'), (2, 'b@example.com'), (3, 'c@example.com')");
      statement.execute(
          "INSERT INTO shop.public.orders VALUES (10, 1, 'shipped', 100, DATE '2026-09-01'), (11, 1, 'cancelled', 50, DATE '2026-09-02'), (12, 2, 'shipped', 70, DATE '2026-09-03')");
    }
  }

  @AfterEach
  void tearDown() throws SQLException {
    data.close();
  }

  private SqlExecutor sql() {
    return text -> {
      try (var statement = data.createStatement();
          var rows = statement.executeQuery(text)) {
        var meta = rows.getMetaData();
        var columns = new ArrayList<String>();
        for (var i = 1; i <= meta.getColumnCount(); i++) {
          columns.add(meta.getColumnLabel(i));
        }
        var result = new ArrayList<List<JsonNode>>();
        while (rows.next()) {
          var row = new ArrayList<JsonNode>();
          for (var i = 1; i <= columns.size(); i++) {
            var value = rows.getObject(i);
            row.add(
                value == null
                    ? Json.MAPPER.nullNode()
                    : value instanceof Number number
                        ? Json.tree(number.longValue())
                        : Json.tree(value.toString()));
          }
          result.add(row);
        }
        return new SqlExecutor.Result(columns, result, UUID.randomUUID().toString());
      } catch (SQLException e) {
        throw new DataAccessException(String.valueOf(e.getMessage()), false);
      }
    };
  }

  private final LanguageModel model =
      new LanguageModel() {
        @Override
        public String identity() {
          return "scripted";
        }

        @Override
        public String complete(String purpose, String prompt) {
          prompts.add(purpose);
          return switch (purpose) {
            case "field-meaning" ->
                """
                Here it is: {"fields": [
                  {"field": "shop.public.orders.status", "meaning": "Order status", "valueKind": "classification",
                   "codes": {"shipped": "Shipped", "cancelled": "Cancelled"}, "documents": ["D1"], "reason": "glossary"},
                  {"field": "shop.public.orders.amount", "meaning": "Order amount excluding tax", "valueKind": "amount", "unit": "JPY", "documents": ["D1"]}
                ]}""";
            case "row-meaning" ->
                """
                {"datasets": [{"dataset": "shop.public.orders", "concept": {"name": "order", "label": "Order", "meaning": "A purchase"}, "rowKind": "event", "documents": []}]}""";
            case "analytical-semantics" ->
                """
                {"measures": [{"name": "sales", "label": "Sales", "meaning": "Revenue of shipped orders", "dataset": "shop.public.orders",
                  "function": "sum", "expression": "amount", "filter": "status <> 'cancelled'", "timeField": "ordered_on", "documents": ["D1"]}],
                 "terms": [{"text": "Sales", "refersTo": "measure:sales", "documents": ["D1"]}]}""";
            default -> "{}";
          };
        }
      };

  private SemanticCatalog create() {
    var configuration = new Configuration(Generators.standard(model), sql(), model);
    return SemanticCatalog.create(
        directory.resolve("catalog.duckdb"), DOMAIN, OWNER, configuration);
  }

  private static JsonNode metadata(String json) {
    return Json.read(json);
  }

  private static final String CUSTOMERS_METADATA =
      """
      {"resource": "shop.public.customers", "kind": "table",
       "columns": [{"name": "id", "sourceType": "integer", "analyticsType": "INT", "nullable": false, "ordinal": 1},
                   {"name": "email", "sourceType": "character varying", "analyticsType": "VARCHAR", "nullable": false, "ordinal": 2}],
       "primaryKey": {"name": "customers_pkey", "columns": ["id"], "enforced": true},
       "uniqueConstraints": [], "foreignKeys": [], "complete": {"columns": true, "constraints": true}}""";

  private static final String ORDERS_METADATA =
      """
      {"resource": "shop.public.orders", "kind": "table",
       "columns": [{"name": "id", "sourceType": "integer", "analyticsType": "INT", "nullable": false, "ordinal": 1},
                   {"name": "customer_id", "sourceType": "integer", "analyticsType": "INT", "nullable": false, "ordinal": 2},
                   {"name": "status", "sourceType": "character varying", "analyticsType": "VARCHAR", "nullable": false, "ordinal": 3},
                   {"name": "amount", "sourceType": "bigint", "analyticsType": "BIGINT", "nullable": false, "ordinal": 4},
                   {"name": "ordered_on", "sourceType": "date", "analyticsType": "DATE", "nullable": false, "ordinal": 5}],
       "primaryKey": {"name": "orders_pkey", "columns": ["id"], "enforced": true},
       "uniqueConstraints": [],
       "foreignKeys": [{"name": "orders_customer_fk", "columns": ["customer_id"], "references": {"resource": "shop.public.customers", "columns": ["id"]}, "enforced": true}],
       "complete": {"columns": true, "constraints": true}}""";

  private static void runAll(SemanticCatalog catalog) {
    for (var i = 0; i < 100 && !catalog.createdTasks().isEmpty(); i++) {
      var run = catalog.runTask(catalog.createdTasks().getFirst().id());
      assertThat(run.state()).as(run.failureReason()).isEqualTo(BuildTask.State.SUCCEEDED);
    }
    assertThat(catalog.createdTasks()).isEmpty();
  }

  /** Consents to every item awaiting review until none is left. */
  private static void consentToEverything(SemanticCatalog catalog) {
    for (var round = 0; round < 10; round++) {
      var queue = catalog.reads().reviewQueue(null, null, null, null);
      if (queue.items().isEmpty()) {
        return;
      }
      var answers = new ArrayList<AnswerInput>();
      for (var item : queue.items()) {
        for (var fact : item.facts()) {
          answers.add(
              new AnswerInput(
                  fact.fact(), Target.CONFIRMATION, AnswerType.CONSENT, "Agreed", null));
        }
      }
      catalog.submitAnswers(OWNER, null, "authz:owner", answers);
      runAll(catalog);
    }
  }

  @Test
  void declarationsAreConfirmedByMachineJudgementAndChecksAreRecordedFromData() {
    try (var catalog = create()) {
      catalog.registerTableMetadata(
          OWNER, null, "shop.public.customers", "v1", metadata(CUSTOMERS_METADATA));
      catalog.registerTableMetadata(
          OWNER, null, "shop.public.orders", "v1", metadata(ORDERS_METADATA));
      runAll(catalog);

      var reads = catalog.reads();
      assertThat(reads.findElements(null, ElementKind.DATASET, null, null).elements()).hasSize(2);
      assertThat(reads.findElements(null, ElementKind.FIELD, ORDERS, null).elements()).hasSize(5);
      var join = reads.findElements(null, ElementKind.JOIN, null, null).elements();
      assertThat(join).hasSize(1);
      var knowledge =
          reads.requestKnowledge(
              null,
              List.of(
                  new Request(ORDERS, null, null, null, Predicates.KEY),
                  new Request(ORDERS, null, null, null, Predicates.uniqueness(List.of("id"))),
                  new Request(join.getFirst().element(), null, null, null, Predicates.MULTIPLICITY),
                  new Request(
                      join.getFirst().element(), null, null, null, Predicates.PARTICIPATION_FROM),
                  new Request(
                      join.getFirst().element(), null, null, null, Predicates.PARTICIPATION_TO),
                  new Request(
                      Urns.field(ORDERS, "ordered_on"), null, null, null, Predicates.VALUE_KIND)),
              null);
      var values =
          knowledge.responses().stream()
              .map(response -> response.addresses().getFirst().selected())
              .map(content -> content == null ? null : content.value())
              .toList();
      assertThat(values).doesNotContainNull();
      assertThat(values.get(2).path("multiplicity").asText()).isEqualTo("many-to-one");
      assertThat(values.get(3).path("participation").asText()).isEqualTo("all");
      assertThat(values.get(4).path("participation").asText()).isEqualTo("some");
      assertThat(values.get(5).path("kind").asText()).isEqualTo("time");
      var uniqueness = knowledge.responses().get(1).addresses().getFirst().selected();
      assertThat(java.util.Objects.requireNonNull(uniqueness).checks())
          .extracting(check -> Json.tree(check.method()).asText())
          .contains("declared-enforced", "data-check");
      assertThat(catalog.verify().consistent()).isTrue();
    }
  }

  @Test
  void inferredMeaningAwaitsConsentAndAnsweredKnowledgeIsFoundByItsBusinessTerm() {
    try (var catalog = create()) {
      catalog.registerTableMetadata(
          OWNER, null, "shop.public.customers", "v1", metadata(CUSTOMERS_METADATA));
      catalog.registerTableMetadata(
          OWNER, null, "shop.public.orders", "v1", metadata(ORDERS_METADATA));
      catalog.registerObservation(
          OWNER,
          null,
          ObservationKind.DOCUMENT,
          null,
          null,
          Json.object()
              .put("title", "Glossary")
              .put("text", "Sales is the sum of amount of orders that are not cancelled."));
      runAll(catalog);
      var status = Urns.field(ORDERS, "status");
      var meaning =
          catalog
              .reads()
              .requestKnowledge(
                  null, List.of(new Request(status, null, null, null, Predicates.MEANING)), null);
      var address = meaning.responses().getFirst().addresses().getFirst();
      assertThat(address.status()).isEqualTo("awaiting-judgement");
      assertThat(address.material().getFirst().checks())
          .extracting(check -> Json.tree(check.method()).asText())
          .containsExactly("document");

      consentToEverything(catalog);

      var found = catalog.reads().findElements(null, null, null, "sales");
      assertThat(found.elements())
          .extracting(Outputs.ElementSummary::kind)
          .containsExactly(ElementKind.MEASURE);
      var measure = found.elements().getFirst().element();
      var rule =
          catalog
              .reads()
              .requestKnowledge(
                  null,
                  List.of(
                      new Request(measure, null, null, null, Predicates.AGGREGATION_RULE),
                      new Request(measure, null, null, null, Predicates.FILTER)),
                  null);
      assertThat(
              java.util.Objects.requireNonNull(
                      rule.responses().get(0).addresses().getFirst().selected())
                  .value()
                  .path("expression")
                  .asText())
          .isEqualTo("amount");
      assertThat(
              java.util.Objects.requireNonNull(
                      rule.responses().get(1).addresses().getFirst().selected())
                  .value()
                  .path("condition")
                  .asText())
          .isEqualTo("status <> 'cancelled'");
      assertThat(prompts).contains("field-meaning", "row-meaning", "analytical-semantics");
      assertThat(catalog.verify().consistent()).isTrue();
    }
  }

  @Test
  void aColumnThatDisappearsIsRetiredAndTheSameVersionIsNotRecordedTwice() {
    try (var catalog = create()) {
      catalog.registerTableMetadata(
          OWNER, null, "shop.public.customers", "v1", metadata(CUSTOMERS_METADATA));
      runAll(catalog);
      var again =
          catalog.registerTableMetadata(
              OWNER, null, "shop.public.customers", "v1", metadata(CUSTOMERS_METADATA));
      assertThat(again.recorded()).isEqualTo("nothing");
      var sameDeclaration =
          catalog.registerTableMetadata(
              OWNER, null, "shop.public.customers", "v2", metadata(CUSTOMERS_METADATA));
      assertThat(sameDeclaration.recorded()).isEqualTo("derived-only");

      var withoutEmail = metadata(CUSTOMERS_METADATA);
      ((com.fasterxml.jackson.databind.node.ArrayNode) withoutEmail.path("columns")).remove(1);
      var changed =
          catalog.registerTableMetadata(OWNER, null, "shop.public.customers", "v3", withoutEmail);
      assertThat(changed.recorded()).isEqualTo("observation-and-derived");
      runAll(catalog);

      assertThat(catalog.reads().findElements(null, ElementKind.FIELD, CUSTOMERS, null).elements())
          .extracting(Outputs.ElementSummary::label)
          .containsExactly("id");
    }
  }
}
