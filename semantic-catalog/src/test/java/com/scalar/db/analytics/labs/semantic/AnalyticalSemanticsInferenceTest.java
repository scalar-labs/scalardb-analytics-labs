/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scalar.db.analytics.labs.semantic.TableMetadataObservation.Read;
import com.scalar.db.analytics.labs.semantic.TableMetadataObservation.SourceMetadata;
import com.scalar.db.analytics.labs.semantic.model.BuildTask;
import com.scalar.db.analytics.labs.semantic.model.Dependency;
import com.scalar.db.analytics.labs.semantic.model.Document;
import com.scalar.db.analytics.labs.semantic.model.FactRevision;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ValidationMethod;
import com.scalar.db.analytics.labs.semantic.policy.PolicyEvaluationModel;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Analytical semantics inference on the EC/POS sample: from a registered glossary, a model proposes
 * the Measures EC売上 and 店舗売上 and the business terms that refer to them (see {@code
 * docs/semantic-catalog/generators.md}, "Meaning inference"). The model is scripted; the data lives
 * in an in-memory DuckDB under the names the table metadata gives.
 */
class AnalyticalSemanticsInferenceTest extends StoreScenarioTest {
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
  private static final AtomicLong VERSIONS = new AtomicLong();
  private static final String ANALYTICAL = "analytical-semantics-inference";
  private static final String FIELD = "field-meaning-inference";

  private static final Document GLOSSARY =
      new Document(
          "glossary.md",
          """
          # 販売ドメイン用語集

          ## 売上

          - **EC売上**: EC の注文の税抜金額の合計。キャンセルされた注文と返品された注文は含めない。
            どの期間の売上かは注文日で判定する。単に「売上」と言うときは EC売上を指す。
          - **店舗売上**: 実店舗の POS で記録した販売の税抜金額の合計。会員カードを使わない販売も含む。
            どの期間の売上かは販売日で判定する。
          """);

  private final List<DuckDbConstructionSql> engines = new ArrayList<>();

  @AfterEach
  void closeEngines() throws SQLException {
    for (var engine : engines) {
      engine.close();
    }
  }

  /** The EC orders and the POS sales. */
  private DuckDbConstructionSql data() {
    var engine =
        new DuckDbConstructionSql(1000)
            .run(
                "ATTACH ':memory:' AS shop",
                "CREATE SCHEMA shop.public",
                "CREATE TABLE shop.public.orders"
                    + " (id INT, amount INT, status VARCHAR, ordered_at DATE)",
                "INSERT INTO shop.public.orders VALUES"
                    + " (1, 1200, 'shipped', DATE '2026-11-01'),"
                    + " (2, 800, 'cancelled', DATE '2026-11-01')",
                "ATTACH ':memory:' AS pos",
                "CREATE SCHEMA pos.public",
                "CREATE TABLE pos.public.sales (id INT, member_id INT, amount INT, sold_at DATE)",
                "INSERT INTO pos.public.sales VALUES (1, NULL, 500, DATE '2026-11-02')");
    engines.add(engine);
    return engine;
  }

  /**
   * A policy evaluation model that adopts what is said of Datasets and Fields and asks for consent
   * on everything else, keeping the Facts it was asked about.
   */
  private static final class AdoptingDataSemantics implements PolicyEvaluationModel {
    final List<String> askedForConsent = new ArrayList<>();

    @Override
    public JsonNode version() {
      return JSON.objectNode().put("model", "adopting-data-semantics");
    }

    @Override
    public synchronized Decision evaluate(Subject subject) {
      var fact = subject.fact().revision().fact();
      if (fact.contains(":fact:dataset:") || fact.contains(":fact:field:")) {
        return new Decision.Adopt("data semantics");
      }
      askedForConsent.add(fact);
      return new Decision.RequestConsent("analytical semantics", JSON.objectNode());
    }
  }

  private static ObjectNode column(String name, String kind, int ordinal) {
    return JSON.objectNode()
        .put("name", name)
        .put("nullable", true)
        .put("dataSourceType", kind)
        .put("ordinal", ordinal)
        .set("analyticsType", JSON.objectNode().put("kind", kind));
  }

  private static void register(Scenario s, String identifier, String... columns) {
    var parts = identifier.split("\\.");
    var metadata = JSON.objectNode();
    metadata.putArray("table").add(parts[1]).add(parts[2]);
    metadata.put("kind", "table");
    var list = metadata.putArray("columns");
    for (var index = 0; index < columns.length; index += 2) {
      list.add(column(columns[index], columns[index + 1], index / 2 + 1));
    }
    metadata.put("columnsMayBeHidden", false);
    metadata.putObject("constraints").put("state", "value").putArray("value");
    var version = VERSIONS.incrementAndGet();
    store.registerTableMetadata(
        s.domain,
        "app",
        new TableMetadataObservation(
            identifier,
            Optional.of(Scenario.table(identifier)),
            version,
            new Read(
                true,
                metadata,
                new SourceMetadata("postgresql", JSON.objectNode().set("table", metadata)),
                Scenario.SCHEMA,
                Instant.parse("2026-11-16T00:00:00Z")),
            JSON.objectNode(),
            "digest-" + version));
  }

  private static void registerTables(Scenario s) {
    register(
        s,
        "shop.public.orders",
        "id",
        "int",
        "amount",
        "int",
        "status",
        "text",
        "ordered_at",
        "date");
    register(
        s, "pos.public.sales", "id", "int", "member_id", "int", "amount", "int", "sold_at", "date");
  }

  private static BuildRun.Summary drain(
      Scenario s, PolicyEvaluationModel policy, ConstructionSql sql, ScriptedModel model) {
    return store.runBuildTasks(
        s.domain,
        new BuildRun.Environment(policy, sql, model, ScriptedModel.NO_DATA),
        BuildRun.drain(),
        task -> {});
  }

  // The scripted answer

  private static ObjectNode value(
      Object value, String basis, String document, @Nullable String quotation) {
    var node = JSON.objectNode();
    if (value instanceof JsonNode json) {
      node.set("value", json);
    } else {
      node.put("value", (String) value);
    }
    node.put("basis", basis);
    if (quotation != null) {
      node.putObject("citation").put("document", document).put("quotation", quotation);
    }
    return node;
  }

  private static ObjectNode rule(String function, String expression) {
    return JSON.objectNode().put("function", function).put("expression", expression);
  }

  /** The glossary's Measures and terms, plus one Measure whose aggregation does not run. */
  private static String glossaryAnswer(JsonNode input) {
    return glossaryAnswerNode(input).toString();
  }

  private static ObjectNode glossaryAnswerNode(JsonNode input) {
    var document = input.path("documents").get(0).path("document").asText();
    var answer = JSON.objectNode();
    var measures = answer.putArray("measures");
    var ecSales = measures.addObject().put("ref", "measure:ec-sales");
    ecSales
        .putObject("existence")
        .put("basis", "the glossary defines EC売上")
        .putObject("citation")
        .put("document", document)
        .put("quotation", "EC売上**: EC の注文の税抜金額の合計。");
    var values = ecSales.putObject("values");
    values.set(
        Vocabulary.MEANING, value("EC の注文の税抜金額の合計", "the glossary", document, "EC の注文の税抜金額の合計"));
    values.set(Vocabulary.BASE_DATASET, value("shop.public.orders", "EC の注文", document, null));
    values.set(
        Vocabulary.AGGREGATION_RULE, value(rule("sum", "amount"), "税抜金額の合計", document, "税抜金額の合計"));
    values.set(
        Vocabulary.FILTER,
        value(
            "status NOT IN ('cancelled', 'returned')",
            "cancelled and returned orders do not count",
            document,
            "キャンセルされた注文と返品された注文は含めない"));
    values.set(
        Vocabulary.REFERENCE_TIME_FIELD,
        value("ordered_at", "注文日で判定する", document, "どの期間の売上かは注文日で判定する"));

    var storeSales = measures.addObject().put("ref", "measure:store-sales");
    storeSales.putObject("existence").put("basis", "the glossary defines 店舗売上");
    var storeValues = storeSales.putObject("values");
    storeValues.set(
        Vocabulary.MEANING, value("実店舗の POS で記録した販売の税抜金額の合計", "the glossary", document, null));
    storeValues.set(Vocabulary.BASE_DATASET, value("pos.public.sales", "POS の販売", document, null));
    storeValues.set(
        Vocabulary.AGGREGATION_RULE, value(rule("sum", "amount"), "税抜金額の合計", document, null));
    storeValues.set(Vocabulary.REFERENCE_TIME_FIELD, value("sold_at", "販売日で判定する", document, null));

    // Summing a text column binds to no function, so the Query Engine rejects it.
    var broken = measures.addObject().put("ref", "measure:status-total");
    broken.putObject("existence").put("basis", "a guess");
    var brokenValues = broken.putObject("values");
    brokenValues.set(
        Vocabulary.BASE_DATASET, value("shop.public.orders", "a guess", document, null));
    brokenValues.set(
        Vocabulary.AGGREGATION_RULE, value(rule("sum", "status"), "a guess", document, null));

    var terms = answer.putArray("businessTerms");
    term(terms, "business-term:ec-sales", "EC売上", "measure:ec-sales");
    term(terms, "business-term:sales", "売上", "measure:ec-sales");
    term(terms, "business-term:store-sales", "店舗売上", "measure:store-sales");
    return answer;
  }

  /**
   * The glossary's answer with the EC sales term named in Japanese, English, and French, a language
   * the catalog does not record names in.
   */
  private static String multilingualAnswer(JsonNode input) {
    var answer = glossaryAnswerNode(input);
    var names =
        ((ObjectNode) answer.path("businessTerms").get(0).path("values"))
            .putArray(Vocabulary.WRITTEN_FORM);
    names.add(value("EC売上", "the glossary", "", null).put("language", "ja"));
    names.add(value("EC sales", "the glossary heading", "", null).put("language", "en"));
    names.add(value("ventes en ligne", "a guess", "", null).put("language", "fr"));
    return answer.toString();
  }

  private static void term(
      com.fasterxml.jackson.databind.node.ArrayNode terms,
      String ref,
      String written,
      String referenced) {
    var term = terms.addObject().put("ref", ref);
    term.putObject("existence").put("basis", "the glossary uses " + written);
    var values = term.putObject("values");
    values
        .putArray(Vocabulary.WRITTEN_FORM)
        .add(value(written, "the glossary", "", null).put("language", "ja"));
    values.set(Vocabulary.REFERENCED_ELEMENT, value(referenced, "the glossary", "", null));
  }

  // Reading what was recorded

  private static String element(Scenario s, ElementKind kind, String identifier) {
    return Urns.element(s.domain, kind, identifier);
  }

  private static Optional<FactRevision> first(Scenario s, String element, String predicate) {
    // A business term's name is recorded in the language the answer gave, Japanese here.
    var language = Vocabulary.languageQualified(predicate) ? "ja" : null;
    var urn = Urns.fact(Urns.factAddress(element, predicate, language), 1);
    return s.latest(snapshot -> snapshot.latestRevision(urn));
  }

  private static FactRevision only(Scenario s, String element, String predicate) {
    return first(s, element, predicate)
        .orElseThrow(() -> new AssertionError("No " + predicate + " of " + element));
  }

  private static List<String> factDependencies(FactRevision revision) {
    return revision.dependencies().stream()
        .filter(Dependency.OnFact.class::isInstance)
        .map(dependency -> ((Dependency.OnFact) dependency).fact().urnValue())
        .toList();
  }

  private static String existence(String element) {
    return Urns.fact(Urns.factAddress(element, Vocabulary.EXISTENCE), 1);
  }

  private static BuildTask.Result.Generated generated(BuildRun.Summary summary) {
    return summary.finished().stream()
        .filter(
            task ->
                task.work() instanceof BuildTask.Work.GeneratorRun run
                    && run.generator().equals(ANALYTICAL))
        .map(
            task -> {
              assertThat(task.status()).isInstanceOf(BuildTask.Status.Succeeded.class);
              return (BuildTask.Result.Generated)
                  ((BuildTask.Status.Succeeded) task.status()).result();
            })
        .reduce((first, second) -> second)
        .orElseThrow(() -> new AssertionError("No run of " + ANALYTICAL));
  }

  private static ScriptedModel model(Function<JsonNode, String> analytical) {
    return new ScriptedModel().answering(ANALYTICAL, analytical);
  }

  // Scenarios

  @Test
  void theGlossaryYieldsTheTwoSalesMeasuresWithTheirTermsAndDefinitions() {
    var s = scenario();
    var sql = data();
    registerTables(s);
    var policy = new AdoptingDataSemantics();
    var model = model(AnalyticalSemanticsInferenceTest::glossaryAnswer);
    drain(s, policy, sql, model);

    var registration =
        (DocumentRegistration.Observed) store.registerDocument(s.domain, "owner", null, GLOSSARY);
    // Concept inference and identifier correspondence inference read documents too.
    assertThat(registration.buildTasks()).hasSize(3);
    var summary = drain(s, policy, sql, model);

    var ecSales = element(s, ElementKind.MEASURE, "ec-sales");
    var storeSales = element(s, ElementKind.MEASURE, "store-sales");
    var orders = element(s, ElementKind.DATASET, "shop.public.orders");
    var sales = element(s, ElementKind.DATASET, "pos.public.sales");

    // EC売上: existence and definitions, quoted from the glossary where the model cited it.
    var existence = only(s, ecSales, Vocabulary.EXISTENCE);
    assertThat(existence.state()).isEqualTo(FactState.CANDIDATE);
    assertThat(existence.checks())
        .singleElement()
        .satisfies(check -> assertThat(check.method()).isEqualTo(ValidationMethod.DOCUMENT));
    assertThat(only(s, ecSales, Vocabulary.BASE_DATASET).value().asText()).isEqualTo(orders);
    var aggregation = only(s, ecSales, Vocabulary.AGGREGATION_RULE);
    assertThat(aggregation.value()).isEqualTo(rule("sum", "amount"));
    assertThat(aggregation.checks())
        .singleElement()
        .satisfies(
            check -> {
              assertThat(check.method()).isEqualTo(ValidationMethod.DOCUMENT);
              assertThat(check.items())
                  .isEqualTo(JSON.arrayNode().add("function").add("expression"));
            });
    var filter = only(s, ecSales, Vocabulary.FILTER);
    assertThat(filter.value().asText()).isEqualTo("status NOT IN ('cancelled', 'returned')");
    assertThat(only(s, ecSales, Vocabulary.REFERENCE_TIME_FIELD).value().asText())
        .isEqualTo(element(s, ElementKind.FIELD, "shop.public.orders.ordered_at"));

    // The expressions' Fields became premise dependencies at acceptance.
    var amount = element(s, ElementKind.FIELD, "shop.public.orders.amount");
    var status = element(s, ElementKind.FIELD, "shop.public.orders.status");
    assertThat(factDependencies(aggregation))
        .contains(existence(ecSales), existence(amount))
        .anyMatch(fact -> fact.contains("/base-dataset/"));
    assertThat(factDependencies(filter)).contains(existence(status));

    // 店舗売上, from the POS sales, with only inferences because the model cited nothing.
    assertThat(only(s, storeSales, Vocabulary.BASE_DATASET).value().asText()).isEqualTo(sales);
    assertThat(only(s, storeSales, Vocabulary.AGGREGATION_RULE).checks())
        .singleElement()
        .satisfies(check -> assertThat(check.method()).isEqualTo(ValidationMethod.INFERENCE));
    assertThat(first(s, storeSales, Vocabulary.FILTER)).isEmpty();

    // The existence and the aggregation rule of a new Measure reach the deciding party together.
    assertThat(policy.askedForConsent)
        .contains(
            existence(ecSales),
            Urns.fact(Urns.factAddress(ecSales, Vocabulary.AGGREGATION_RULE), 1));

    // The terms refer to the Measures; the slugs are the model's, not digests of Japanese names.
    var salesTerm = element(s, ElementKind.BUSINESS_TERM, "sales");
    assertThat(only(s, salesTerm, Vocabulary.WRITTEN_FORM).value().asText()).isEqualTo("売上");
    assertThat(only(s, salesTerm, Vocabulary.REFERENCED_ELEMENT).value().asText())
        .isEqualTo(ecSales);
    assertThat(factDependencies(only(s, salesTerm, Vocabulary.REFERENCED_ELEMENT)))
        .contains(existence(ecSales));
    assertThat(
            only(
                    s,
                    element(s, ElementKind.BUSINESS_TERM, "store-sales"),
                    Vocabulary.REFERENCED_ELEMENT)
                .value()
                .asText())
        .isEqualTo(storeSales);
    assertThat(first(s, element(s, ElementKind.BUSINESS_TERM, "ec-sales"), Vocabulary.WRITTEN_FORM))
        .hasValueSatisfying(revision -> assertThat(revision.value().asText()).isEqualTo("EC売上"));

    // A Measure whose aggregation the Query Engine rejects is not proposed.
    assertThat(first(s, element(s, ElementKind.MEASURE, "status-total"), Vocabulary.EXISTENCE))
        .isEmpty();
    assertThat(generated(summary).missingInputs())
        .anyMatch(missing -> missing.contains("measure:status-total"));

    // Every model call was told to write in the domain's recording language, Japanese.
    assertThat(model.calls)
        .extracting(ScriptedModel.Call::generator)
        .contains(ANALYTICAL, FIELD, "row-meaning-inference");
    assertThat(model.calls)
        .allSatisfy(
            call -> assertThat(call.input().path("recordingLanguage").asText()).isEqualTo("ja"));

    // The model read the glossary, the tables with their columns, and no internal keys.
    var input = model.calls.stream().filter(call -> call.generator().equals(ANALYTICAL)).toList();
    assertThat(input).hasSize(1);
    var read = input.getFirst().input();
    assertThat(read.path("documents"))
        .extracting(section -> section.path("heading").asText())
        .containsExactly("販売ドメイン用語集", "売上");
    assertThat(read.path("tables"))
        .extracting(table -> table.path("ref").asText())
        .containsExactlyInAnyOrder("shop.public.orders", "pos.public.sales");
    assertThat(read.toString()).doesNotContain("\"_");
  }

  private static final Document FISCAL_GLOSSARY =
      new Document(
          "fiscal-glossary.md",
          """
          # Sales glossary

          ## Fiscal calendar

          The fiscal year starts in April. A fiscal month runs from the 21st to the 20th of the \
          next month and is named by the month it ends in; a boundary that falls on a holiday \
          moves to the next business day. The fiscal year is divided into quarters and months.

          ## Retail calendar

          The stores follow a 4-4-5 retail calendar whose year starts on the Sunday nearest \
          February 1. Its year is divided into months of four, four, and five weeks.

          ## Sales

          - **EC sales**: the total pre-tax amount of EC orders. EC sales are managed by fiscal \
          month.
          - **Store sales**: the total pre-tax amount of sales recorded at the stores' POS. They \
          are managed by retail week.
          """);

  /** A Measure in the fiscal answer, with its existence and the values every Measure needs. */
  private static ObjectNode fiscalMeasure(
      com.fasterxml.jackson.databind.node.ArrayNode measures,
      String ref,
      String meaning,
      String table,
      String timeColumn,
      String document) {
    var measure = measures.addObject().put("ref", ref);
    measure.putObject("existence").put("basis", "the glossary defines " + meaning);
    var values = measure.putObject("values");
    values.set(Vocabulary.MEANING, value(meaning, "the glossary", document, null));
    values.set(Vocabulary.BASE_DATASET, value(table, "the glossary", document, null));
    values.set(
        Vocabulary.AGGREGATION_RULE, value(rule("sum", "amount"), "a total", document, null));
    values.set(Vocabulary.REFERENCE_TIME_FIELD, value(timeColumn, "the period", document, null));
    return values;
  }

  /** A calendar in the fiscal answer with its rule and levels, each a name and an expression. */
  private static void calendar(
      com.fasterxml.jackson.databind.node.ArrayNode dimensions,
      String ref,
      String meaning,
      String rule,
      String document,
      String... namesAndExpressions) {
    var calendar = dimensions.addObject().put("ref", ref);
    calendar
        .putObject("existence")
        .put("basis", "the glossary defines it")
        .putObject("citation")
        .put("document", document)
        .put("quotation", rule);
    var values = calendar.putObject("values");
    values.set(Vocabulary.MEANING, value(meaning, "the glossary", document, null));
    values.set(Vocabulary.DIMENSION_KIND, value("calendar", "a calendar", document, null));
    values.set(Vocabulary.CALENDAR_RULE, value(rule, "the glossary's rule", document, rule));
    var levels = JSON.objectNode();
    var list = levels.putArray("levels");
    for (var i = 0; i < namesAndExpressions.length; i += 2) {
      var level = list.addObject().put("name", namesAndExpressions[i]);
      if (!namesAndExpressions[i + 1].isEmpty()) {
        level.put("expression", namesAndExpressions[i + 1]);
      }
    }
    values.set(Vocabulary.HIERARCHY, value(levels, "the glossary's divisions", document, null));
  }

  private static final String FISCAL_RULE =
      "A fiscal month runs from the 21st to the 20th of the next month and is named by the month"
          + " it ends in; a boundary that falls on a holiday moves to the next business day.";

  private static final String RETAIL_RULE =
      "The stores follow a 4-4-5 retail calendar whose year starts on the Sunday nearest February"
          + " 1.";

  /**
   * The fiscal glossary's answer: a fiscal calendar whose months close on the 20th, with one level
   * expression that reads another column; a 4-4-5 retail calendar with no expressions; EC sales by
   * fiscal month and store sales by retail week; and an age band with its kind alone.
   */
  private static String fiscalAnswer(JsonNode input) {
    var document = input.path("documents").get(0).path("document").asText();
    var answer = JSON.objectNode();
    var dimensions = answer.putArray("dimensions");
    calendar(
        dimensions,
        "dimension:fiscal-calendar",
        "The fiscal calendar",
        FISCAL_RULE,
        document,
        "fiscal year",
        "",
        "fiscal half",
        "date_trunc('fiscal_half', time_value)",
        "fiscal quarter",
        "strftime(amount, '%Y')",
        "fiscal month",
        "strftime(time_value - INTERVAL 20 DAY, '%Y-%m')");
    calendar(
        dimensions,
        "dimension:retail-calendar",
        "The stores' 4-4-5 retail calendar",
        RETAIL_RULE,
        document,
        "retail year",
        "",
        "retail month",
        "",
        "retail week",
        "");

    var ageBand = dimensions.addObject().put("ref", "dimension:age-band");
    ageBand.putObject("existence").put("basis", "a guess");
    ageBand
        .putObject("values")
        .set(Vocabulary.DIMENSION_KIND, value("classification", "a guess", document, null));

    var measures = answer.putArray("measures");
    fiscalMeasure(
            measures,
            "measure:ec-sales",
            "The total pre-tax amount of EC orders",
            "shop.public.orders",
            "ordered_at",
            document)
        .set(
            Vocabulary.DEFAULT_TIME_GRANULARITY,
            value(
                JSON.objectNode()
                    .put("dimension", "dimension:fiscal-calendar")
                    .put("level", "fiscal month"),
                "managed by fiscal month",
                document,
                "EC sales are managed by fiscal month."));
    fiscalMeasure(
            measures,
            "measure:store-sales",
            "The total pre-tax amount of store sales",
            "pos.public.sales",
            "sold_at",
            document)
        .set(
            Vocabulary.DEFAULT_TIME_GRANULARITY,
            value(
                JSON.objectNode()
                    .put("dimension", "dimension:retail-calendar")
                    .put("level", "retail week"),
                "managed by retail week",
                document,
                null));
    return answer.toString();
  }

  private static JsonNode level(FactRevision hierarchy, String name) {
    for (var level : hierarchy.value().path("levels")) {
      if (level.path("name").asText().equals(name)) {
        return level;
      }
    }
    throw new AssertionError("No level " + name);
  }

  @Test
  void calendarsAreProposedWithTheirWordingAndLevelsAndMeasuresReferToThem() {
    var s = scenario();
    var sql = data();
    registerTables(s);
    var policy = new AdoptingDataSemantics();
    var model = model(AnalyticalSemanticsInferenceTest::fiscalAnswer);
    drain(s, policy, sql, model);
    var registration =
        (DocumentRegistration.Observed)
            store.registerDocument(s.domain, "owner", null, FISCAL_GLOSSARY);
    var summary = drain(s, policy, sql, model);

    // The fiscal calendar keeps the document's wording, quoted from it, and rests on its kind.
    var fiscal = element(s, ElementKind.DIMENSION, "fiscal-calendar");
    assertThat(only(s, fiscal, Vocabulary.DIMENSION_KIND).value().asText()).isEqualTo("calendar");
    var rule = only(s, fiscal, Vocabulary.CALENDAR_RULE);
    assertThat(rule.value().asText()).isEqualTo(FISCAL_RULE);
    assertThat(rule.checks())
        .singleElement()
        .satisfies(check -> assertThat(check.method()).isEqualTo(ValidationMethod.DOCUMENT));
    var kindFact = Urns.fact(Urns.factAddress(fiscal, Vocabulary.DIMENSION_KIND), 1);
    var hierarchy = only(s, fiscal, Vocabulary.HIERARCHY);
    assertThat(factDependencies(rule)).contains(existence(fiscal), kindFact);
    assertThat(factDependencies(hierarchy)).contains(existence(fiscal), kindFact);

    // Its levels keep their names; the expression that ran on sample order dates stays, and the
    // ones that read another column or fail when the Query Engine runs them are dropped while their
    // levels stay.
    assertThat(hierarchy.value().path("levels"))
        .extracting(level -> level.path("name").asText())
        .containsExactly("fiscal year", "fiscal half", "fiscal quarter", "fiscal month");
    assertThat(level(hierarchy, "fiscal month").path("expression").asText())
        .isEqualTo("strftime(time_value - INTERVAL 20 DAY, '%Y-%m')");
    assertThat(level(hierarchy, "fiscal quarter").has("expression")).isFalse();
    assertThat(level(hierarchy, "fiscal half").has("expression")).isFalse();
    assertThat(generated(summary).missingInputs())
        .anyMatch(missing -> missing.contains("fiscal half") && missing.contains("did not run"));
    assertThat(sql.requests)
        .anyMatch(
            request ->
                request.sql().contains("strftime(time_value - INTERVAL 20 DAY, '%Y-%m')")
                    && request.sql().contains("AS time_value"));
    assertThat(generated(summary).missingInputs())
        .anyMatch(missing -> missing.contains("fiscal quarter") && missing.contains("amount"));

    // The retail calendar has no expression at all and is recorded by its wording alone.
    var retail = element(s, ElementKind.DIMENSION, "retail-calendar");
    assertThat(only(s, retail, Vocabulary.CALENDAR_RULE).value().asText()).isEqualTo(RETAIL_RULE);
    assertThat(only(s, retail, Vocabulary.HIERARCHY).value().path("levels"))
        .extracting(level -> level.path("name").asText())
        .containsExactly("retail year", "retail month", "retail week");

    // The Measures name their calendar by the URN issued in the same commit and their level in
    // words, resting on the calendar's kind.
    var ecSales = element(s, ElementKind.MEASURE, "ec-sales");
    var granularity = only(s, ecSales, Vocabulary.DEFAULT_TIME_GRANULARITY);
    assertThat(granularity.value())
        .isEqualTo(JSON.objectNode().put("dimension", fiscal).put("level", "fiscal month"));
    assertThat(factDependencies(granularity)).contains(existence(ecSales), kindFact);
    var storeSales = element(s, ElementKind.MEASURE, "store-sales");
    assertThat(only(s, storeSales, Vocabulary.DEFAULT_TIME_GRANULARITY).value())
        .isEqualTo(JSON.objectNode().put("dimension", retail).put("level", "retail week"));

    // A new Dimension with its kind alone, without its rule and hierarchy, is not proposed.
    assertThat(first(s, element(s, ElementKind.DIMENSION, "age-band"), Vocabulary.EXISTENCE))
        .isEmpty();
    assertThat(generated(summary).missingInputs())
        .anyMatch(missing -> missing.contains("dimension:age-band"));

    // A changed document runs it again: the calendars exist, and a granularity names its calendar
    // by the ref the model writes, not by its URN.
    store.registerDocument(
        s.domain,
        "owner",
        registration.inputResource(),
        new Document(
            FISCAL_GLOSSARY.name(), FISCAL_GLOSSARY.text() + "\nAll amounts are in JPY.\n"));
    drain(s, policy, sql, model);
    var again =
        model.calls.stream()
            .filter(each -> each.generator().equals(ANALYTICAL))
            .toList()
            .getLast()
            .input();
    assertThat(again.path("slugsInUse").path("dimension"))
        .extracting(JsonNode::asText)
        .contains("fiscal-calendar", "retail-calendar");
    assertThat(again.path("dimensions"))
        .extracting(dimension -> dimension.path("ref").asText())
        .contains("dimension:fiscal-calendar", "dimension:retail-calendar");
    var ecSalesNode =
        java.util.stream.StreamSupport.stream(again.path("measures").spliterator(), false)
            .filter(measure -> measure.path("ref").asText().equals("measure:ec-sales"))
            .findFirst()
            .orElseThrow();
    assertThat(
            ecSalesNode
                .path("values")
                .path(Vocabulary.DEFAULT_TIME_GRANULARITY)
                .path("value")
                .path("dimension")
                .asText())
        .isEqualTo("dimension:fiscal-calendar");
  }

  // Classifications

  private static final Document MEMBER_GLOSSARY =
      new Document(
          "member-glossary.md",
          """
          # Member glossary

          ## Age bands

          Members are grouped by their age today into ten-year bands: under 20, the 20s, the \
          30s, the 40s, the 50s, and 60 and over. Each band is split into five-year halves.

          ## Regions

          Prefectures are grouped into regions: Tokyo and Kanagawa are Kanto, Osaka and Kyoto \
          are Kansai. Regions are grouped into East Japan (Kanto) and West Japan (Kansai).

          ## Sales areas

          A member's sales area follows the area master table kept by the sales department.

          ## Member ranks

          A member is Gold when their purchases over the last year total 100,000 yen or more, \
          Silver from 30,000 yen, and Regular otherwise.

          ## Member status

          Members are also counted by their member status column.
          """);

  private static final String AGE_RULE =
      "Members are grouped by their age today into ten-year bands: under 20, the 20s, the 30s, the"
          + " 40s, the 50s, and 60 and over.";

  private static final String AGE_BAND =
      "CASE WHEN date_diff('year', birth_date, current_date) < 20 THEN 'under 20'"
          + " WHEN date_diff('year', birth_date, current_date) >= 60 THEN '60 and over'"
          + " ELSE CAST(date_diff('year', birth_date, current_date) // 10 * 10 AS VARCHAR) || 's'"
          + " END";

  private static final String BLOCK =
      "CASE WHEN prefecture IN ('Tokyo', 'Kanagawa') THEN 'East Japan'"
          + " WHEN prefecture IN ('Osaka', 'Kyoto') THEN 'West Japan' END";

  private static final String REGION =
      "CASE WHEN prefecture IN ('Tokyo', 'Kanagawa') THEN 'Kanto'"
          + " WHEN prefecture IN ('Osaka', 'Kyoto') THEN 'Kansai' END";

  /** The EC orders and the POS sales, with the members. */
  private DuckDbConstructionSql memberData() {
    return data()
        .run(
            "ATTACH ':memory:' AS crm",
            "CREATE SCHEMA crm.public",
            "CREATE TABLE crm.public.members"
                + " (member_id INT, birth_date DATE, prefecture VARCHAR, status VARCHAR)",
            "INSERT INTO crm.public.members VALUES"
                + " (1, DATE '1990-05-01', 'Tokyo', 'active'),"
                + " (2, DATE '2010-01-01', 'Osaka', 'inactive')");
  }

  /** A classification in the member answer with its rule, classified column, and levels. */
  private static void classification(
      com.fasterxml.jackson.databind.node.ArrayNode dimensions,
      String ref,
      String meaning,
      String rule,
      @Nullable String column,
      String document,
      String... namesAndExpressions) {
    var classification = dimensions.addObject().put("ref", ref);
    classification.putObject("existence").put("basis", "the glossary defines it");
    var values = classification.putObject("values");
    values.set(Vocabulary.MEANING, value(meaning, "the glossary", document, null));
    values.set(
        Vocabulary.DIMENSION_KIND, value("classification", "a classification", document, null));
    values.set(Vocabulary.CLASSIFICATION_RULE, value(rule, "the glossary's rule", document, rule));
    if (column != null) {
      values.set(
          Vocabulary.CLASSIFIED_FIELD, value(column, "the column it groups", document, null));
    }
    var levels = JSON.objectNode();
    var list = levels.putArray("levels");
    for (var i = 0; i < namesAndExpressions.length; i += 2) {
      var level = list.addObject().put("name", namesAndExpressions[i]);
      if (!namesAndExpressions[i + 1].isEmpty()) {
        level.put("expression", namesAndExpressions[i + 1]);
      }
    }
    values.set(Vocabulary.HIERARCHY, value(levels, "the glossary's levels", document, null));
  }

  /**
   * The member glossary's answer: age bands with an expression that runs and a five-year level
   * whose expression fails when it runs; regions in two levels; sales areas and member ranks by
   * their wording alone; and member status, which groups by a column's own values.
   */
  private static String memberAnswer(JsonNode input) {
    var document = input.path("documents").get(0).path("document").asText();
    var answer = JSON.objectNode();
    var dimensions = answer.putArray("dimensions");
    classification(
        dimensions,
        "dimension:age-band",
        "Members' ten-year age bands",
        AGE_RULE,
        "crm.public.members.birth_date",
        document,
        "age band",
        AGE_BAND,
        "five-year band",
        "date_diff('year', birth_date, current_date) // 5 * 5 + 'years'");
    classification(
        dimensions,
        "dimension:region",
        "Regions of prefectures",
        "Prefectures are grouped into regions: Tokyo and Kanagawa are Kanto, Osaka and Kyoto are"
            + " Kansai.",
        "crm.public.members.prefecture",
        document,
        "block",
        BLOCK,
        "region",
        REGION,
        "prefecture",
        "prefecture");
    classification(
        dimensions,
        "dimension:sales-area",
        "Members' sales areas",
        "A member's sales area follows the area master table kept by the sales department.",
        "crm.public.members.prefecture",
        document,
        "sales area",
        "");
    classification(
        dimensions,
        "dimension:member-rank",
        "Members' ranks by their purchases",
        "A member is Gold when their purchases over the last year total 100,000 yen or more,"
            + " Silver from 30,000 yen, and Regular otherwise.",
        "crm.public.members.member_id",
        document,
        "rank",
        "");
    classification(
        dimensions,
        "dimension:member-status",
        "Members by status",
        "Members are also counted by their member status column.",
        "crm.public.members.status",
        document,
        "status",
        "status");
    return answer.toString();
  }

  @Test
  void classificationsAreProposedWithTheirWordingAndTheExpressionsThatRun() {
    var s = scenario();
    var sql = memberData();
    registerTables(s);
    register(
        s,
        "crm.public.members",
        "member_id",
        "int",
        "birth_date",
        "date",
        "prefecture",
        "text",
        "status",
        "text");
    var policy = new AdoptingDataSemantics();
    var model = model(AnalyticalSemanticsInferenceTest::memberAnswer);
    drain(s, policy, sql, model);
    store.registerDocument(s.domain, "owner", null, MEMBER_GLOSSARY);
    var summary = drain(s, policy, sql, model);

    var birthDate = element(s, ElementKind.FIELD, "crm.public.members.birth_date");

    // The age bands keep the document's wording and name the birth date as the Field they
    // classify; the expression that ran on sample members stays, and the one that failed is
    // dropped while its level stays.
    var age = element(s, ElementKind.DIMENSION, "age-band");
    var kindFact = Urns.fact(Urns.factAddress(age, Vocabulary.DIMENSION_KIND), 1);
    assertThat(only(s, age, Vocabulary.DIMENSION_KIND).value().asText())
        .isEqualTo("classification");
    var rule = only(s, age, Vocabulary.CLASSIFICATION_RULE);
    assertThat(rule.value().asText()).isEqualTo(AGE_RULE);
    assertThat(rule.checks())
        .singleElement()
        .satisfies(check -> assertThat(check.method()).isEqualTo(ValidationMethod.DOCUMENT));
    var classified = only(s, age, Vocabulary.CLASSIFIED_FIELD);
    assertThat(classified.value().asText()).isEqualTo(birthDate);
    assertThat(factDependencies(classified)).contains(kindFact, existence(birthDate));
    var hierarchy = only(s, age, Vocabulary.HIERARCHY);
    assertThat(level(hierarchy, "age band").path("expression").asText()).isEqualTo(AGE_BAND);
    assertThat(level(hierarchy, "five-year band").has("expression")).isFalse();
    assertThat(generated(summary).missingInputs())
        .anyMatch(missing -> missing.contains("five-year band") && missing.contains("did not run"))
        // The failed sample query is recorded under the classification's own check name.
        .anyMatch(
            missing ->
                missing.contains("classification-level-check")
                    && missing.contains("five-year band"))
        .noneMatch(missing -> missing.contains("calendar-level-check dimension:age-band"));
    var classifiedFact = Urns.fact(Urns.factAddress(age, Vocabulary.CLASSIFIED_FIELD), 1);
    assertThat(factDependencies(hierarchy))
        .contains(kindFact, classifiedFact, existence(birthDate));
    assertThat(sql.requests)
        .anyMatch(
            request ->
                request.sql().contains(AGE_BAND)
                    && request.sql().contains("\"crm\".\"public\".\"members\"")
                    && request.sql().contains("GROUP BY 1"));

    // Regions keep their levels coarse to fine; the finest is the prefecture itself, whose bare
    // column expression is dropped while the level and the classification stay.
    var region = only(s, element(s, ElementKind.DIMENSION, "region"), Vocabulary.HIERARCHY);
    assertThat(region.value().path("levels"))
        .extracting(level -> level.path("name").asText())
        .containsExactly("block", "region", "prefecture");
    assertThat(region.value().path("levels"))
        .extracting(level -> level.path("expression").asText())
        .containsExactly(BLOCK, REGION, "");
    assertThat(generated(summary).missingInputs())
        .anyMatch(
            missing -> missing.contains("level prefecture") && missing.contains("only references"));

    // Sales areas and member ranks need other tables and are recorded by their wording alone.
    for (var slug : List.of("sales-area", "member-rank")) {
      var dimension = element(s, ElementKind.DIMENSION, slug);
      assertThat(only(s, dimension, Vocabulary.CLASSIFICATION_RULE).value().asText()).isNotBlank();
      assertThat(only(s, dimension, Vocabulary.HIERARCHY).value().path("levels"))
          .allSatisfy(level -> assertThat(level.has("expression")).isFalse());
    }

    // Member status groups by a column's own values, its meaning, and is not proposed.
    assertThat(first(s, element(s, ElementKind.DIMENSION, "member-status"), Vocabulary.EXISTENCE))
        .isEmpty();
    assertThat(generated(summary).missingInputs())
        .filteredOn(missing -> missing.contains("dimension:member-status"))
        .singleElement()
        .satisfies(missing -> assertThat(missing).contains("a column's own values"));

    // A changed document runs it again: the input names each classified Field by its table's ref
    // and its column, as the model writes it.
    store.registerDocument(
        s.domain,
        "owner",
        null,
        new Document("member-glossary-2.md", MEMBER_GLOSSARY.text() + "\nAges are in years.\n"));
    drain(s, policy, sql, model);
    var again =
        model.calls.stream()
            .filter(each -> each.generator().equals(ANALYTICAL))
            .toList()
            .getLast()
            .input();
    var ageNode =
        java.util.stream.StreamSupport.stream(again.path("dimensions").spliterator(), false)
            .filter(dimension -> dimension.path("ref").asText().equals("dimension:age-band"))
            .findFirst()
            .orElseThrow();
    assertThat(ageNode.path("values").path(Vocabulary.CLASSIFIED_FIELD).path("value").asText())
        .isEqualTo("crm.public.members.birth_date");
  }

  @Test
  void anExistingMeasureIsPointedToAndItsSlugIsGivenAsInUse() {
    var s = scenario();
    var sql = data();
    registerTables(s);
    var policy = new AdoptingDataSemantics();
    var model = model(AnalyticalSemanticsInferenceTest::glossaryAnswer);
    drain(s, policy, sql, model);
    var first =
        (DocumentRegistration.Observed) store.registerDocument(s.domain, "owner", null, GLOSSARY);
    drain(s, policy, sql, model);
    var commit = s.lastCommit();

    // A changed document starts the run again; the model sees the slugs in use and answers the
    // same refs, which now name existing elements whose Fact Addresses are taken.
    store.registerDocument(
        s.domain,
        "owner",
        first.inputResource(),
        new Document("glossary.md", GLOSSARY.text() + "\n金額はすべて円（JPY）。\n"));
    var summary = drain(s, policy, sql, model);

    var second = model.calls.stream().filter(call -> call.generator().equals(ANALYTICAL)).toList();
    assertThat(second).hasSize(2);
    var input = second.getLast().input();
    assertThat(input.path("slugsInUse").path("measure"))
        .extracting(JsonNode::asText)
        .contains("ec-sales", "store-sales");
    assertThat(input.path("measures"))
        .extracting(measure -> measure.path("ref").asText())
        .contains("measure:ec-sales", "measure:store-sales");
    assertThat(generated(summary).commit()).isNull();
    assertThat(s.lastCommit()).isEqualTo(commit + 1);
    assertThat(first(s, element(s, ElementKind.MEASURE, "ec-sales~2"), Vocabulary.EXISTENCE))
        .isEmpty();
  }

  @Test
  void aTermsNamesAreRecordedPerDeclaredLanguageAndOthersAreDropped() {
    var s = scenario();
    var sql = data();
    registerTables(s);
    var policy = new AdoptingDataSemantics();
    var model = model(AnalyticalSemanticsInferenceTest::multilingualAnswer);
    drain(s, policy, sql, model);
    store.registerDocument(s.domain, "owner", null, GLOSSARY);
    var summary = drain(s, policy, sql, model);

    var term = element(s, ElementKind.BUSINESS_TERM, "ec-sales");
    for (var name : List.of(List.of("ja", "EC売上"), List.of("en", "EC sales"))) {
      var urn = Urns.fact(Urns.factAddress(term, Vocabulary.WRITTEN_FORM, name.get(0)), 1);
      Optional<FactRevision> recorded = s.latest(snapshot -> snapshot.latestRevision(urn));
      assertThat(recorded)
          .hasValueSatisfying(
              revision -> assertThat(revision.value().asText()).isEqualTo(name.get(1)));
    }
    assertThat(generated(summary).missingInputs())
        .anyMatch(missing -> missing.startsWith("written-form in fr of business-term:ec-sales"));

    // The model was told which languages the catalog records names in.
    var call =
        model.calls.stream()
            .filter(each -> each.generator().equals(ANALYTICAL))
            .findFirst()
            .orElseThrow();
    assertThat(call.input().path("languages"))
        .extracting(JsonNode::asText)
        .containsExactly("ja", "en");
  }

  @Test
  void namesWrappedLikeOtherValuesAreStillRecorded() {
    var s = scenario();
    var sql = data();
    registerTables(s);
    var policy = new AdoptingDataSemantics();
    // The shape models give: the list of names wrapped as {"value": [...], "basis": ...}.
    var model =
        model(
            input -> {
              var answer = glossaryAnswerNode(input);
              var values = (ObjectNode) answer.path("businessTerms").get(0).path("values");
              var wrapper = JSON.objectNode().put("basis", "the glossary uses it");
              wrapper
                  .putArray("value")
                  .add(value("EC売上", "the glossary", "", null).put("language", "ja"));
              values.set(Vocabulary.WRITTEN_FORM, wrapper);
              return answer.toString();
            });
    drain(s, policy, sql, model);
    store.registerDocument(s.domain, "owner", null, GLOSSARY);
    var summary = drain(s, policy, sql, model);

    generated(summary);
    assertThat(name(s, element(s, ElementKind.BUSINESS_TERM, "ec-sales"), "ja"))
        .hasValueSatisfying(revision -> assertThat(revision.value().asText()).isEqualTo("EC売上"));
  }

  /**
   * A policy evaluation model that also adopts the existence of business terms, so a confirmed term
   * can lack a name, and asks for consent on everything else.
   */
  private static final class AdoptingTermExistence implements PolicyEvaluationModel {
    private final AdoptingDataSemantics data = new AdoptingDataSemantics();

    @Override
    public JsonNode version() {
      return JSON.objectNode().put("model", "adopting-term-existence");
    }

    @Override
    public synchronized Decision evaluate(Subject subject) {
      var fact = subject.fact().revision().fact();
      if (fact.contains(":fact:business-term:") && fact.contains("/existence")) {
        return new Decision.Adopt("the glossary uses the term");
      }
      return data.evaluate(subject);
    }
  }

  private static final Document BILINGUAL_GLOSSARY =
      new Document("glossary.md", GLOSSARY.text() + "\n英語の資料では EC売上を EC sales と書く。\n");

  /**
   * Answers the glossary call as {@link #glossaryAnswer} does, and a call that asks only for names
   * with an English name for each term asked about: for EC売上 the one the glossary states, for any
   * other an inference. It also gives a Measure and a meaning, which such a call does not ask for.
   */
  private static String namingAnswer(JsonNode input) {
    if (!input.has("missingNames")) {
      return glossaryAnswer(input);
    }
    var document = input.path("documents").get(0).path("document").asText();
    var answer = JSON.objectNode();
    var terms = answer.putArray("businessTerms");
    for (var entry : input.path("missingNames")) {
      var ref = entry.path("ref").asText();
      var term = terms.addObject().put("ref", ref);
      var values = term.putObject("values");
      var names = values.putArray(Vocabulary.WRITTEN_FORM);
      for (var language : entry.path("languages")) {
        var stated = ref.equals("business-term:ec-sales");
        names.add(
            value(
                    stated ? "EC sales" : "sales of " + ref,
                    stated ? "the glossary states it" : "what English readers would call it",
                    document,
                    stated ? "EC売上を EC sales と書く" : null)
                .put("language", language.asText()));
      }
      values.set(Vocabulary.MEANING, value("not asked for", "a guess", document, null));
    }
    var measure = answer.putArray("measures").addObject().put("ref", "measure:not-asked");
    measure.putObject("existence").put("basis", "a guess");
    return answer.toString();
  }

  private static Optional<FactRevision> name(Scenario s, String term, String language) {
    var urn = Urns.fact(Urns.factAddress(term, Vocabulary.WRITTEN_FORM, language), 1);
    return s.latest(snapshot -> snapshot.latestRevision(urn));
  }

  @Test
  void aConfirmedTermNamedOnlyInJapaneseGetsAnEnglishNameCandidate() {
    var s = scenario();
    var sql = data();
    registerTables(s);
    var policy = new AdoptingTermExistence();
    var model = model(AnalyticalSemanticsInferenceTest::namingAnswer);
    drain(s, policy, sql, model);
    store.registerDocument(s.domain, "owner", null, BILINGUAL_GLOSSARY);
    drain(s, policy, sql, model);

    var ecSales = element(s, ElementKind.BUSINESS_TERM, "ec-sales");
    var storeSales = element(s, ElementKind.BUSINESS_TERM, "store-sales");
    assertThat(s.state(existence(ecSales))).contains(FactState.CONFIRMED);

    // The name a document states is document-checked; one no document states is an inference.
    // Neither is adopted inline.
    assertThat(name(s, ecSales, "en"))
        .hasValueSatisfying(
            revision -> {
              assertThat(revision.value().asText()).isEqualTo("EC sales");
              assertThat(revision.checks())
                  .singleElement()
                  .satisfies(
                      check -> assertThat(check.method()).isEqualTo(ValidationMethod.DOCUMENT));
              assertThat(factDependencies(revision)).contains(existence(ecSales));
            });
    for (var term : List.of(ecSales, storeSales)) {
      assertThat(s.state(Urns.fact(Urns.factAddress(term, Vocabulary.WRITTEN_FORM, "en"), 1)))
          .contains(FactState.CANDIDATE);
    }
    assertThat(name(s, storeSales, "en"))
        .hasValueSatisfying(
            revision ->
                assertThat(revision.checks())
                    .singleElement()
                    .satisfies(
                        check -> assertThat(check.method()).isEqualTo(ValidationMethod.INFERENCE)));

    // One candidate per name: a later run finds the address taken and asks nothing more.
    for (var term : List.of(ecSales, storeSales)) {
      var second = Urns.fact(Urns.factAddress(term, Vocabulary.WRITTEN_FORM, "en"), 2);
      Optional<FactRevision> recorded = s.latest(snapshot -> snapshot.latestRevision(second));
      assertThat(recorded).isEmpty();
    }

    // The calls asked only for the English names, read no tables, and wrote no Measure or meaning.
    var naming =
        model.calls.stream()
            .filter(call -> call.generator().equals(ANALYTICAL) && call.input().has("missingNames"))
            .toList();
    assertThat(naming).isNotEmpty();
    assertThat(naming)
        .allSatisfy(
            call -> {
              var input = call.input();
              assertThat(input.path("recordingLanguage").asText()).isEqualTo("ja");
              assertThat(input.has("tables")).isFalse();
              assertThat(input.path("missingNames"))
                  .allSatisfy(
                      entry ->
                          assertThat(entry.path("languages"))
                              .extracting(JsonNode::asText)
                              .containsExactly("en"));
              assertThat(input.path("businessTerms"))
                  .allSatisfy(
                      term ->
                          assertThat(
                                  term.path("values")
                                      .path(Vocabulary.WRITTEN_FORM)
                                      .path("ja")
                                      .path("value")
                                      .asText())
                              .isNotEmpty());
              assertThat(input.toString()).doesNotContain("\"_");
            });
    assertThat(first(s, element(s, ElementKind.MEASURE, "not-asked"), Vocabulary.EXISTENCE))
        .isEmpty();
    assertThat(first(s, ecSales, Vocabulary.MEANING)).isEmpty();
  }

  @Test
  void aNameTheModelDoesNotGiveIsRecordedAsMissing() {
    var s = scenario();
    var sql = data();
    registerTables(s);
    var policy = new AdoptingTermExistence();
    var model =
        model(
            input ->
                input.has("missingNames")
                    ? JSON.objectNode().set("businessTerms", JSON.arrayNode()).toString()
                    : glossaryAnswer(input));
    drain(s, policy, sql, model);
    store.registerDocument(s.domain, "owner", null, GLOSSARY);
    var summary = drain(s, policy, sql, model);

    var ecSales = element(s, ElementKind.BUSINESS_TERM, "ec-sales");
    assertThat(s.state(existence(ecSales))).contains(FactState.CONFIRMED);
    assertThat(name(s, ecSales, "en")).isEmpty();
    assertThat(summary.finished())
        .filteredOn(
            task ->
                task.work() instanceof BuildTask.Work.GeneratorRun run
                    && run.generator().equals(ANALYTICAL))
        .flatExtracting(
            task ->
                ((BuildTask.Result.Generated) ((BuildTask.Status.Succeeded) task.status()).result())
                    .missingInputs())
        .contains("written-form in en of business-term:ec-sales, which the model did not give");
  }

  @Test
  void aConfirmedTermWithANameInEveryDeclaredLanguageIsNotAskedAbout() {
    var s = scenario();
    var sql = data();
    registerTables(s);
    var policy = new AdoptingTermExistence();
    var model =
        model(input -> input.has("missingNames") ? namingAnswer(input) : multilingualAnswer(input));
    drain(s, policy, sql, model);
    store.registerDocument(s.domain, "owner", null, GLOSSARY);
    drain(s, policy, sql, model);

    var ecSales = element(s, ElementKind.BUSINESS_TERM, "ec-sales");
    assertThat(s.state(existence(ecSales))).contains(FactState.CONFIRMED);
    assertThat(model.calls)
        .filteredOn(call -> call.input().has("missingNames"))
        .isNotEmpty()
        .allSatisfy(
            call ->
                assertThat(call.input().path("missingNames"))
                    .extracting(entry -> entry.path("ref").asText())
                    .doesNotContain("business-term:ec-sales"));
    assertThat(name(s, ecSales, "en"))
        .hasValueSatisfying(
            revision -> assertThat(revision.value().asText()).isEqualTo("EC sales"));
  }

  @Test
  void aSlugThatIsNotLowercaseAsciiWordsFailsTheRun() {
    var s = scenario();
    var sql = data();
    registerTables(s);
    var policy = new AdoptingDataSemantics();
    var model =
        model(
            input -> {
              var answer = JSON.objectNode();
              var measure = answer.putArray("measures").addObject().put("ref", "measure:EC売上");
              measure.putObject("existence").put("basis", "the glossary");
              var values = measure.putObject("values");
              values.set(Vocabulary.BASE_DATASET, value("shop.public.orders", "b", "", null));
              values.set(Vocabulary.AGGREGATION_RULE, value(rule("sum", "amount"), "b", "", null));
              return answer.toString();
            });
    drain(s, policy, sql, model);
    store.registerDocument(s.domain, "owner", null, GLOSSARY);

    var summary = drain(s, policy, sql, model);

    assertThat(summary.finished())
        .filteredOn(
            task ->
                task.work() instanceof BuildTask.Work.GeneratorRun run
                    && run.generator().equals(ANALYTICAL))
        .singleElement()
        .satisfies(
            task ->
                assertThat(task.status())
                    .isInstanceOfSatisfying(
                        BuildTask.Status.Failed.class,
                        failed -> assertThat(failed.reason()).contains("EC売上")));
  }

  @Test
  void aConfirmedFieldMeaningStartsTheRunOnceADocumentIsThere() {
    var s = scenario();
    var sql = data();
    var policy = new AdoptingDataSemantics();
    var model =
        model(AnalyticalSemanticsInferenceTest::glossaryAnswer)
            .answering(
                FIELD,
                input -> {
                  var answer = JSON.objectNode();
                  var target =
                      answer
                          .putArray("targets")
                          .addObject()
                          .put("ref", "shop.public.orders.amount");
                  target
                      .putObject("values")
                      .putObject("meaning")
                      .put("value", "税抜金額")
                      .put("basis", "the column name");
                  return answer.toString();
                });

    // The document comes first: no Dataset is confirmed yet, so the run asks nothing.
    store.registerDocument(s.domain, "owner", null, GLOSSARY);
    var before = drain(s, policy, sql, model);
    assertThat(generated(before).missingInputs())
        .containsExactly("a confirmed Dataset to base a Measure on");
    assertThat(model.calls).isEmpty();

    // The tables arrive; the adopted meaning of the amount column starts it again.
    registerTables(s);
    drain(s, policy, sql, model);

    assertThat(model.calls).anyMatch(call -> call.generator().equals(ANALYTICAL));
    assertThat(first(s, element(s, ElementKind.MEASURE, "ec-sales"), Vocabulary.EXISTENCE))
        .isPresent();
  }
}
