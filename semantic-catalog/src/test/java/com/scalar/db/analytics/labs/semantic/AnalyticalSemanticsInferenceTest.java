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
