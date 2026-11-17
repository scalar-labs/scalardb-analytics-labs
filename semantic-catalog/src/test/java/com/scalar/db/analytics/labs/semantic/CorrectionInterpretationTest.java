/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.scalar.db.analytics.labs.semantic.TableMetadataObservation.Read;
import com.scalar.db.analytics.labs.semantic.TableMetadataObservation.SourceMetadata;
import com.scalar.db.analytics.labs.semantic.TableMetadataObservation.SourceMetadataSchema;
import com.scalar.db.analytics.labs.semantic.model.BuildTask;
import com.scalar.db.analytics.labs.semantic.model.Dependency;
import com.scalar.db.analytics.labs.semantic.model.ElementIdentity;
import com.scalar.db.analytics.labs.semantic.model.FactRevision;
import com.scalar.db.analytics.labs.semantic.model.IdentityKey;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.MatchingCondition;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.AnswerType;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Preset;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Transition;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ValidationMethod;
import com.scalar.db.analytics.labs.semantic.policy.PolicyEvaluationModel;
import com.scalar.db.analytics.labs.semantic.projection.DimensionValues;
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
 * Correction interpretation of the predicates beyond row and Field meaning: values that stand
 * alone, values that name other elements, and a Measure's expression values (see {@code
 * docs/semantic-catalog/generators.md}, "Meaning inference").
 */
class CorrectionInterpretationTest extends StoreScenarioTest {
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
  private static final AtomicLong VERSIONS = new AtomicLong();
  private static final String CORRECTION = "correction-interpretation";

  private final List<DuckDbConstructionSql> engines = new ArrayList<>();

  @AfterEach
  void closeEngines() throws SQLException {
    for (var engine : engines) {
      engine.close();
    }
  }

  /** Orders, some of them shipped, and their shipments. */
  private DuckDbConstructionSql shop() {
    var engine =
        new DuckDbConstructionSql(1000)
            .run(
                "ATTACH ':memory:' AS shop",
                "CREATE SCHEMA shop.public",
                "CREATE TABLE shop.public.orders (id INT, amount INT, status VARCHAR)",
                "INSERT INTO shop.public.orders VALUES"
                    + " (1, 1200, 'shipped'), (2, 800, 'shipped'), (3, 3000, 'ordered')",
                "CREATE TABLE shop.public.shipments (id INT, order_id INT)",
                "INSERT INTO shop.public.shipments VALUES (10, 1), (11, 2)");
    engines.add(engine);
    return engine;
  }

  /** A policy evaluation model that asks for consent on everything. */
  private static final class AskingConsent implements PolicyEvaluationModel {
    @Override
    public JsonNode version() {
      return JSON.objectNode().put("model", "asking-consent");
    }

    @Override
    public Decision evaluate(Subject subject) {
      return new Decision.RequestConsent("an inference only", JSON.objectNode());
    }
  }

  private static final SourceMetadataSchema SCHEMA =
      new SourceMetadataSchema("schema-digest", JSON.objectNode().put("type", "object"));

  private static void register(Scenario s, String table, List<String> columns, String key) {
    var metadata = JSON.objectNode();
    metadata.putArray("table").add("public").add(table);
    metadata.put("kind", "table");
    var list = metadata.putArray("columns");
    for (var index = 0; index < columns.size(); index++) {
      var name = columns.get(index);
      var kind = name.equals("status") ? "text" : "int";
      list.addObject()
          .put("name", name)
          .put("nullable", false)
          .put("dataSourceType", kind)
          .put("ordinal", index + 1)
          .set("analyticsType", JSON.objectNode().put("kind", kind));
    }
    metadata.put("columnsMayBeHidden", false);
    var constraint =
        metadata
            .putObject("constraints")
            .put("state", "value")
            .putArray("value")
            .addObject()
            .put("kind", "primary-key")
            .put("name", table + "_pkey")
            .put("enforced", true);
    constraint.putArray("columns").add(key);
    var identifier = "shop.public." + table;
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
                SCHEMA,
                Instant.parse("2026-11-17T00:00:00Z")),
            JSON.objectNode(),
            "digest-" + version));
  }

  private BuildRun.Summary drain(Scenario s, ScriptedModel model) {
    var summary =
        store.runBuildTasks(
            s.domain,
            new BuildRun.Environment(
                new AskingConsent(), shop(), model, ModelInputSettings.defaults()),
            BuildRun.drain(),
            task -> {});
    assertThat(summary.finished())
        .allSatisfy(
            task -> assertThat(task.status()).isInstanceOf(BuildTask.Status.Succeeded.class));
    return summary;
  }

  // The catalog the corrections are about

  private static String dataset(Scenario s, String table) {
    return Urns.element(s.domain, ElementKind.DATASET, "shop.public." + table);
  }

  private static String field(Scenario s, String table, String column) {
    return Urns.element(s.domain, ElementKind.FIELD, "shop.public." + table + "." + column);
  }

  private static String existence(String element) {
    return Urns.fact(Urns.factAddress(element, Vocabulary.EXISTENCE), 1);
  }

  /** A registered shop whose Datasets and Fields are confirmed from their declarations. */
  private Scenario shopScenario() {
    var s = scenario();
    register(s, "orders", List.of("id", "amount", "status"), "id");
    register(s, "shipments", List.of("id", "order_id"), "id");
    drain(s, new ScriptedModel());
    assertThat(s.state(existence(dataset(s, "orders")))).contains(FactState.CONFIRMED);
    return s;
  }

  /** A new node with a candidate existence, as the dependency on that existence names it. */
  private record Node(Ref element, Dependency.OnFact existence) {}

  private static Node node(Scenario.Commit c, ElementKind kind, String slug) {
    var element = c.add(new LogRecord.Element(new ElementIdentity.Unique(kind, slug)));
    return new Node(
        element, c.closedRangeDependency(c.address(element, Vocabulary.EXISTENCE), "exists"));
  }

  /** Adds a candidate with a value, an identity key, and dependencies, as a Generator would. */
  private static Dependency.OnFact candidate(
      Scenario.Commit c, Ref address, JsonNode value, IdentityKey key, Dependency... dependencies) {
    var fact = c.add(new LogRecord.Fact(address, value, key));
    return new Dependency.OnFact(fact, c.accept(fact, dependencies));
  }

  // Correcting

  private static Answers.Outcomes correct(
      Scenario s, String fact, Transition transition, String wording) {
    return store.submitAnswers(
        s.domain,
        new Answers.Submission(
            "owner",
            "authority-check",
            List.of(
                new Answers.Answer(
                    fact,
                    transition,
                    AnswerType.CORRECTION,
                    wording,
                    "the value presented for review",
                    null))));
  }

  /** Answers correction interpretation with a revised value of the first related Fact. */
  private static Function<JsonNode, String> revising(JsonNode value) {
    return input -> {
      var answer = JSON.objectNode();
      answer
          .putArray("targets")
          .addObject()
          .put("ref", input.path("facts").get(0).path("ref").asText())
          .putNull("scope")
          .put("basis", "the deciding party said so")
          .set("value", value);
      return answer.toString();
    };
  }

  private static JsonNode inputOf(ScriptedModel model) {
    return model.calls.stream()
        .filter(call -> call.generator().equals(CORRECTION))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Correction interpretation did not ask the model"))
        .input();
  }

  private static List<FactRevision> facts(
      Scenario s, String element, String predicate, @Nullable String language) {
    var address = Urns.factAddress(element, predicate, language);
    var found = new ArrayList<FactRevision>();
    for (var number = 1; number <= 10; number++) {
      var urn = Urns.fact(address, number);
      var revision = s.latest(snapshot -> snapshot.latestRevision(urn));
      if (revision.isEmpty()) {
        break;
      }
      found.add(revision.get());
    }
    return found;
  }

  /** The revised candidate: the second Fact at the corrected Fact's Fact Address. */
  private static FactRevision revised(
      Scenario s, String element, String predicate, @Nullable String language) {
    var found = facts(s, element, predicate, language);
    assertThat(found).as(predicate + " of " + element).hasSize(2);
    var revised = found.get(1);
    assertThat(revised.state()).isEqualTo(FactState.CANDIDATE);
    assertThat(revised.checks())
        .singleElement()
        .satisfies(check -> assertThat(check.method()).isEqualTo(ValidationMethod.INFERENCE));
    assertThat(revised.dependencies())
        .anySatisfy(dependency -> assertThat(dependency).isInstanceOf(Dependency.OnInput.class));
    return revised;
  }

  private static FactRevision revised(Scenario s, String element, String predicate) {
    return revised(s, element, predicate, null);
  }

  private static List<String> factsDependedOn(FactRevision revision) {
    return revision.dependencies().stream()
        .filter(Dependency.OnFact.class::isInstance)
        .map(dependency -> ((Dependency.OnFact) dependency).fact().urnValue())
        .toList();
  }

  private static BuildTask.Result.Generated generated(BuildRun.Summary summary) {
    return summary.finished().stream()
        .filter(
            task ->
                task.work() instanceof BuildTask.Work.GeneratorRun run
                    && run.generator().equals(CORRECTION))
        .map(
            task ->
                (BuildTask.Result.Generated) ((BuildTask.Status.Succeeded) task.status()).result())
        .findFirst()
        .orElseThrow(() -> new AssertionError("No run of " + CORRECTION));
  }

  // Values that stand alone

  @Test
  void aCorrectedMultiplicityIsReadInTheFormConceptInferenceGivesIt() {
    var s = shopScenario();
    var c = s.begin();
    var customer = node(c, ElementKind.CONCEPT, "customer");
    var order = node(c, ElementKind.CONCEPT, "order");
    var edge =
        c.add(
            new LogRecord.Element(
                new ElementIdentity.ConceptRelationship(
                    customer.element(), order.element(), true, Vocabulary.ASSOCIATION)));
    var edgeExistence =
        c.closedRangeDependency(
            c.address(edge, Vocabulary.EXISTENCE),
            "exists",
            customer.existence(),
            order.existence());
    var multiplicity =
        candidate(
            c,
            c.address(edge, Vocabulary.MULTIPLICITY),
            JSON.objectNode().put("from", "exactly-one").put("to", "zero-or-more"),
            IdentityKey.of(
                new IdentityKey.Part.Choice("exactly-one"),
                new IdentityKey.Part.Choice("zero-or-more")),
            edgeExistence);
    var written = s.write(c);
    drain(s, new ScriptedModel());
    var relationship = written.urn(edge);
    var corrected = written.urn(multiplicity.fact());

    correct(s, corrected, Transition.CONFIRM, "注文は必ず 1 件以上ある");
    var model =
        new ScriptedModel()
            .answering(
                CORRECTION,
                revising(JSON.objectNode().put("from", "exactly-one").put("to", "one-or-more")));
    drain(s, model);

    // The call is told to write in the domain's recording language.
    assertThat(inputOf(model).path("recordingLanguage").asText()).isEqualTo("ja");
    var input = inputOf(model).path("facts").get(0);
    assertThat(input.path("ends").path("from").asText()).isEqualTo("customer");
    assertThat(input.path("ends").path("to").asText()).isEqualTo("order");
    assertThat(input.path("valueForm").asText()).contains("one-or-more");
    var revised = revised(s, relationship, Vocabulary.MULTIPLICITY);
    assertThat(revised.value())
        .isEqualTo(JSON.objectNode().put("from", "exactly-one").put("to", "one-or-more"));
    assertThat(factsDependedOn(revised)).containsExactly(written.urn(edgeExistence.fact()));
  }

  @Test
  void aCorrectedNameKeepsTheLanguageOfItsFactAddress() {
    var s = shopScenario();
    var c = s.begin();
    var term = node(c, ElementKind.BUSINESS_TERM, "ec-sales");
    var name = c.address(term.element(), Vocabulary.WRITTEN_FORM, "ja");
    var english = c.address(term.element(), Vocabulary.WRITTEN_FORM, "en");
    var corrected = c.candidate(name, "EC売上", term.existence());
    c.candidate(english, "EC sales", term.existence());
    var written = s.write(c);
    drain(s, new ScriptedModel());
    var element = written.urn(term.element());

    correct(s, written.urn(corrected), Transition.CONFIRM, "EC売上高と呼んでいる");
    var model = new ScriptedModel().answering(CORRECTION, revising(JSON.textNode("EC売上高")));
    drain(s, model);

    assertThat(inputOf(model).path("facts").get(0).path("language").asText()).isEqualTo("ja");
    var revised = revised(s, element, Vocabulary.WRITTEN_FORM, "ja");
    assertThat(revised.value().asText()).isEqualTo("EC売上高");
    assertThat(facts(s, element, Vocabulary.WRITTEN_FORM, "en")).hasSize(1);
    assertThat(factsDependedOn(revised)).containsExactly(existence(element));
  }

  @Test
  void aCorrectedBusinessDefinitionOfACorrespondenceIsADescription() {
    var s = shopScenario();
    var c = s.begin();
    var correspondence =
        c.correspondence(
            MatchingCondition.direct(
                MatchingCondition.Pair.of(
                    Ref.urn(field(s, "shipments", "order_id")),
                    Ref.urn(field(s, "orders", "id")))));
    var definition = c.candidate(c.address(correspondence, Vocabulary.BUSINESS_DEFINITION), "同じ注文");
    var written = s.write(c);
    drain(s, new ScriptedModel());
    var edge = written.urn(correspondence);

    correct(s, written.urn(definition), Transition.CONFIRM, "出荷された注文を指す");
    var model = new ScriptedModel().answering(CORRECTION, revising(JSON.textNode(" 出荷された注文 ")));
    drain(s, model);

    var revised = revised(s, edge, Vocabulary.BUSINESS_DEFINITION);
    assertThat(revised.value().asText()).isEqualTo("出荷された注文");
  }

  // Values that name other elements

  @Test
  void aCorrectedConceptIsChosenAmongTheConceptsInUseAndDependsOnItsExistence() {
    var s = shopScenario();
    var c = s.begin();
    var customer = node(c, ElementKind.CONCEPT, "customer");
    var order = node(c, ElementKind.CONCEPT, "order");
    var id = field(s, "orders", "id");
    var represented =
        candidate(
            c,
            c.address(Ref.urn(id), Vocabulary.REPRESENTED_CONCEPT),
            JSON.nullNode(),
            IdentityKey.of(new IdentityKey.Part.Reference(customer.element())),
            s.on(existence(id)),
            customer.existence());
    var written = s.write(c);
    drain(s, new ScriptedModel());
    var orderConcept = written.urn(order.element());

    correct(s, written.urn(represented.fact()), Transition.CONFIRM, "顧客ではなく注文の ID");
    var model = new ScriptedModel().answering(CORRECTION, revising(JSON.textNode("order")));
    drain(s, model);

    var input = inputOf(model).path("facts").get(0);
    assertThat(input.path("value").asText()).isEqualTo("customer");
    assertThat(input.path("choices").path("concepts").findValuesAsText("ref"))
        .containsExactly("customer", "order");
    var revised = revised(s, id, Vocabulary.REPRESENTED_CONCEPT);
    assertThat(revised.value().asText()).isEqualTo(orderConcept);
    // It depends on the concept it names, not on the one the corrected value named.
    assertThat(factsDependedOn(revised))
        .containsExactlyInAnyOrder(existence(id), existence(orderConcept));
  }

  @Test
  void aConceptNotInUseCreatesNothingAndIsRecordedOnTheBuildTask() {
    var s = shopScenario();
    var c = s.begin();
    var customer = node(c, ElementKind.CONCEPT, "customer");
    var id = field(s, "orders", "id");
    var represented =
        candidate(
            c,
            c.address(Ref.urn(id), Vocabulary.REPRESENTED_CONCEPT),
            JSON.nullNode(),
            IdentityKey.of(new IdentityKey.Part.Reference(customer.element())),
            s.on(existence(id)),
            customer.existence());
    var written = s.write(c);
    drain(s, new ScriptedModel());

    correct(s, written.urn(represented.fact()), Transition.CONFIRM, "会員を指す");
    var model = new ScriptedModel().answering(CORRECTION, revising(JSON.textNode("member")));
    var summary = drain(s, model);

    assertThat(facts(s, id, Vocabulary.REPRESENTED_CONCEPT, null)).hasSize(1);
    assertThat(s.state(existence(Urns.element(s.domain, ElementKind.CONCEPT, "member")))).isEmpty();
    assertThat(generated(summary).missingInputs())
        .singleElement()
        .satisfies(
            missing ->
                assertThat(missing)
                    .contains("whose value was dropped because it is not one of the names listed")
                    .endsWith(": 会員を指す"));
  }

  @Test
  void aCorrectedKeyNamesColumnsOfItsDatasetAndReplacesTheDeclaredKey() {
    var s = shopScenario();
    var orders = dataset(s, "orders");
    var declared = Urns.fact(Urns.factAddress(orders, Vocabulary.KEY), 1);
    assertThat(s.state(declared)).contains(FactState.CONFIRMED);
    Judgement.setPolicy(s, Preset.MANUAL);
    var commit = s.begin();
    Judgement.proposeRetire(commit, Ref.urn(declared));
    s.judge(commit);

    correct(s, declared, Transition.RETIRE, "注文 ID とステータスで一意");
    var columns = JSON.arrayNode().add("status").add("id");
    var model = new ScriptedModel().answering(CORRECTION, revising(columns));
    drain(s, model);

    var input = inputOf(model).path("facts").get(0);
    assertThat(input.path("value")).isEqualTo(JSON.arrayNode().add("id"));
    assertThat(input.path("choices").path("columns"))
        .extracting(JsonNode::asText)
        .containsExactlyInAnyOrder("id", "amount", "status");
    var revised = revised(s, orders, Vocabulary.KEY);
    var fields = JSON.arrayNode();
    List.of(field(s, "orders", "id"), field(s, "orders", "status")).stream()
        .sorted()
        .forEach(fields::add);
    assertThat(revised.value()).isEqualTo(JSON.objectNode().set("fields", fields));
    assertThat(factsDependedOn(revised))
        .contains(
            declared,
            existence(orders),
            existence(field(s, "orders", "id")),
            existence(field(s, "orders", "status")));
  }

  /** A Measure over the orders table: its existence, base Dataset, and aggregation rule. */
  private record Measure(String element, String baseDataset, String aggregationRule) {}

  private static Measure ecSales(Scenario s) {
    var c = s.begin();
    var measure = node(c, ElementKind.MEASURE, "ec-sales");
    var orders = dataset(s, "orders");
    var base =
        candidate(
            c,
            c.address(measure.element(), Vocabulary.BASE_DATASET),
            JSON.textNode(orders),
            IdentityKey.of(new IdentityKey.Part.Reference(Ref.urn(orders))),
            measure.existence(),
            s.on(existence(orders)));
    var rule =
        c.aggregationRule(
            c.address(measure.element(), Vocabulary.AGGREGATION_RULE), "sum", "amount", base);
    var written = s.write(c);
    return new Measure(written.urn(measure.element()), written.urn(base.fact()), written.urn(rule));
  }

  @Test
  void aCorrectedBaseDatasetIsChosenAmongTheConfirmedDatasets() {
    var s = shopScenario();
    var measure = ecSales(s);
    drain(s, new ScriptedModel());

    correct(s, measure.baseDataset(), Transition.CONFIRM, "出荷テーブルを集計する");
    var model =
        new ScriptedModel().answering(CORRECTION, revising(JSON.textNode("shop.public.shipments")));
    drain(s, model);

    var input = inputOf(model).path("facts").get(0);
    assertThat(input.path("value").asText()).isEqualTo("shop.public.orders");
    assertThat(input.path("choices").path("tables").findValuesAsText("ref"))
        .containsExactlyInAnyOrder("shop.public.orders", "shop.public.shipments");
    var revised = revised(s, measure.element(), Vocabulary.BASE_DATASET);
    var shipments = dataset(s, "shipments");
    assertThat(revised.value().asText()).isEqualTo(shipments);
    assertThat(factsDependedOn(revised))
        .containsExactlyInAnyOrder(existence(measure.element()), existence(shipments));
  }

  // Expression values

  @Test
  void aCorrectedAggregationRuleIsParsedAndRunBeforeItIsRecorded() {
    var s = shopScenario();
    var measure = ecSales(s);
    drain(s, new ScriptedModel());

    correct(s, measure.aggregationRule(), Transition.CONFIRM, "注文数で数える");
    var rule = JSON.objectNode().put("function", "count-distinct").put("expression", "id");
    var model = new ScriptedModel().answering(CORRECTION, revising(rule));
    var summary = drain(s, model);

    var input = inputOf(model).path("facts").get(0);
    assertThat(input.has("_base")).isFalse();
    assertThat(input.path("choices").path("columns"))
        .extracting(JsonNode::asText)
        .containsExactlyInAnyOrder("id", "amount", "status");
    var revised = revised(s, measure.element(), Vocabulary.AGGREGATION_RULE);
    assertThat(revised.value()).isEqualTo(rule);
    // Acceptance adds the Field the expression names; the one the corrected rule named, amount,
    // stays behind.
    assertThat(factsDependedOn(revised))
        .containsExactlyInAnyOrder(measure.baseDataset(), existence(field(s, "orders", "id")));
    assertThat(generated(summary).missingInputs()).isEmpty();
  }

  @Test
  void aCorrectedFilterOfAConfirmedOneRunsWithTheMeasuresAggregationRule() {
    var s = shopScenario();
    var measure = ecSales(s);
    var c = s.begin();
    var filter =
        c.candidate(
            c.address(Ref.urn(measure.element()), Vocabulary.FILTER),
            "status = 'ordered'",
            s.on(measure.baseDataset()));
    var confirmed = s.write(c).urn(filter);
    drain(s, new ScriptedModel());
    // A Fact is confirmed only once its premises are.
    for (var fact : List.of(existence(measure.element()), measure.baseDataset(), confirmed)) {
      Judgement.submit(s, Judgement.answer(fact, AnswerType.CONSENT));
    }
    assertThat(s.state(confirmed)).contains(FactState.CONFIRMED);
    Judgement.setPolicy(s, Preset.MANUAL);
    var commit = s.begin();
    Judgement.proposeRetire(commit, Ref.urn(confirmed));
    s.judge(commit);

    correct(s, confirmed, Transition.RETIRE, "出荷済みの注文だけ数える");
    var model =
        new ScriptedModel().answering(CORRECTION, revising(JSON.textNode("status = 'shipped'")));
    var summary = drain(s, model);

    var revised = revised(s, measure.element(), Vocabulary.FILTER);
    assertThat(revised.value().asText()).isEqualTo("status = 'shipped'");
    assertThat(factsDependedOn(revised))
        .containsExactlyInAnyOrder(
            confirmed, measure.baseDataset(), existence(field(s, "orders", "status")));
    assertThat(generated(summary).missingInputs()).isEmpty();
  }

  @Test
  void aCorrectedExpressionNamingAMissingColumnIsRecordedWithoutFailingTheTask() {
    var s = shopScenario();
    var measure = ecSales(s);
    drain(s, new ScriptedModel());

    correct(s, measure.aggregationRule(), Transition.CONFIRM, "値引き後の金額");
    var rule = JSON.objectNode().put("function", "sum").put("expression", "amount - discount");
    var model = new ScriptedModel().answering(CORRECTION, revising(rule));
    var summary = drain(s, model);

    assertThat(facts(s, measure.element(), Vocabulary.AGGREGATION_RULE, null)).hasSize(1);
    // The check query the Query Engine rejects is missing input too, as for any check.
    assertThat(generated(summary).missingInputs())
        .anySatisfy(missing -> assertThat(missing).startsWith("the read of aggregation-check"))
        .anySatisfy(
            missing ->
                assertThat(missing)
                    .contains("whose value was dropped because it names column discount")
                    .endsWith(": 値引き後の金額"));
  }

  // What a correction does not revise

  @Test
  void aCorrectionOfAPhysicalCatalogReferenceIsRecordedWithoutAskingTheModel() {
    var s = shopScenario();
    var orders = dataset(s, "orders");
    var reference = Urns.fact(Urns.factAddress(orders, Vocabulary.PHYSICAL_CATALOG_REFERENCE), 1);
    Judgement.setPolicy(s, Preset.MANUAL);
    var commit = s.begin();
    Judgement.proposeRetire(commit, Ref.urn(reference));
    s.judge(commit);

    correct(s, reference, Transition.RETIRE, "別のテーブルです");
    var model = new ScriptedModel();
    var summary = drain(s, model);

    assertThat(model.calls.stream().filter(call -> call.generator().equals(CORRECTION))).isEmpty();
    assertThat(generated(summary).missingInputs())
        .singleElement()
        .satisfies(
            missing ->
                assertThat(missing)
                    .contains("a correction does not revise, because a Physical Catalog reference")
                    .endsWith(": 別のテーブルです"));
  }

  // Calendars

  /** A calendar with a candidate kind, as a dependency on that kind names it. */
  private record Calendar(Node node, Dependency.OnFact kind) {}

  private static Calendar calendar(Scenario.Commit c, String slug) {
    var node = node(c, ElementKind.DIMENSION, slug);
    var kind =
        candidate(
            c,
            c.address(node.element(), Vocabulary.DIMENSION_KIND),
            JSON.textNode("calendar"),
            IdentityKey.choice("calendar"),
            node.existence());
    return new Calendar(node, kind);
  }

  /** A candidate with no identity key, a description or a structured value. */
  private static Dependency.OnFact described(
      Scenario.Commit c, Ref address, JsonNode value, Dependency... dependencies) {
    var fact = c.add(new LogRecord.Fact(address, value, null));
    return new Dependency.OnFact(fact, c.accept(fact, dependencies));
  }

  private static JsonNode levels(String name, String expression) {
    var value = JSON.objectNode();
    var level = value.putArray("levels").addObject().put("name", name);
    if (!expression.isEmpty()) {
      level.put("expression", expression);
    }
    return value;
  }

  @Test
  void aCorrectedGranularityChoosesAmongTheCalendarsAndRestsOnTheChosenCalendarsKind() {
    var s = shopScenario();
    var c = s.begin();
    var fiscal = calendar(c, "fiscal-calendar");
    var retail = calendar(c, "retail-calendar");
    described(
        c,
        c.address(retail.node().element(), Vocabulary.HIERARCHY),
        levels("retail week", ""),
        retail.node().existence(),
        retail.kind());
    var measure = node(c, ElementKind.MEASURE, "ec-sales");
    var granularity =
        candidate(
            c,
            c.address(measure.element(), Vocabulary.DEFAULT_TIME_GRANULARITY),
            JSON.nullNode(),
            DimensionValues.Granularity.identityKey(fiscal.node().element(), "fiscal month"),
            measure.existence(),
            fiscal.kind());
    var written = s.write(c);
    drain(s, new ScriptedModel());
    var ecSales = written.urn(measure.element());
    var retailCalendar = written.urn(retail.node().element());

    correct(s, written.urn(granularity.fact()), Transition.CONFIRM, "Reported by retail week");
    var model =
        new ScriptedModel()
            .answering(
                CORRECTION,
                revising(
                    JSON.objectNode()
                        .put("dimension", "dimension:retail-calendar")
                        .put("level", "retail week")));
    drain(s, model);

    var input = inputOf(model).path("facts").get(0);
    assertThat(input.path("value").path("dimension").asText())
        .isEqualTo("dimension:fiscal-calendar");
    assertThat(input.path("choices").path("calendars").findValuesAsText("ref"))
        .containsExactlyInAnyOrder("dimension:fiscal-calendar", "dimension:retail-calendar");
    var revised = revised(s, ecSales, Vocabulary.DEFAULT_TIME_GRANULARITY);
    assertThat(revised.value())
        .isEqualTo(JSON.objectNode().put("dimension", retailCalendar).put("level", "retail week"));
    // It rests on the kind of the calendar it names, not on the one the corrected value named.
    assertThat(factsDependedOn(revised))
        .containsExactlyInAnyOrder(existence(ecSales), written.urn(retail.kind().fact()));
  }

  @Test
  void aCorrectedCalendarRuleIsTheNewWordingAndStillRestsOnTheKind() {
    var s = shopScenario();
    var c = s.begin();
    var fiscal = calendar(c, "fiscal-calendar");
    var rule =
        described(
            c,
            c.address(fiscal.node().element(), Vocabulary.CALENDAR_RULE),
            JSON.textNode("A fiscal month closes on the 20th."),
            fiscal.node().existence(),
            fiscal.kind());
    var written = s.write(c);
    drain(s, new ScriptedModel());
    var calendar = written.urn(fiscal.node().element());

    var wording =
        "A fiscal month closes on the 25th; a holiday moves it to the business day before.";
    correct(s, written.urn(rule.fact()), Transition.CONFIRM, "It closes on the 25th");
    drain(s, new ScriptedModel().answering(CORRECTION, revising(JSON.textNode(wording))));

    var revised = revised(s, calendar, Vocabulary.CALENDAR_RULE);
    assertThat(revised.value().asText()).isEqualTo(wording);
    assertThat(factsDependedOn(revised)).contains(written.urn(fiscal.kind().fact()));
  }

  @Test
  void aCorrectedLevelExpressionReadingAnotherColumnIsRecordedWithoutFailingTheTask() {
    var s = shopScenario();
    var c = s.begin();
    var fiscal = calendar(c, "fiscal-calendar");
    var hierarchy =
        described(
            c,
            c.address(fiscal.node().element(), Vocabulary.HIERARCHY),
            levels("fiscal month", "strftime(time_value - INTERVAL 20 DAY, '%Y-%m')"),
            fiscal.node().existence(),
            fiscal.kind());
    var written = s.write(c);
    drain(s, new ScriptedModel());
    var calendar = written.urn(fiscal.node().element());

    correct(s, written.urn(hierarchy.fact()), Transition.CONFIRM, "Months by order amount");
    var summary =
        drain(
            s,
            new ScriptedModel()
                .answering(
                    CORRECTION, revising(levels("fiscal month", "strftime(amount, '%Y-%m')"))));

    assertThat(facts(s, calendar, Vocabulary.HIERARCHY, null)).hasSize(1);
    assertThat(generated(summary).missingInputs())
        .singleElement()
        .satisfies(missing -> assertThat(missing).contains("reads amount"));
  }

  @Test
  void aCorrectionOfACalendarWhoseKindNoLongerStandsIsRecordedWithoutFailingTheTask() {
    var s = shopScenario();
    var c = s.begin();
    var fiscal = calendar(c, "fiscal-calendar");
    var rule =
        described(
            c,
            c.address(fiscal.node().element(), Vocabulary.CALENDAR_RULE),
            JSON.textNode("A fiscal month closes on the 20th."),
            fiscal.node().existence(),
            fiscal.kind());
    var written = s.write(c);
    drain(s, new ScriptedModel());
    var calendar = written.urn(fiscal.node().element());
    var kind = Ref.urn(written.urn(fiscal.kind().fact()));

    correct(s, written.urn(rule.fact()), Transition.CONFIRM, "It closes on the 25th");
    var confirm = s.begin();
    confirm.confirmByMachine(Ref.urn(existence(calendar)));
    confirm.confirmByMachine(kind);
    s.write(confirm);
    var retire = s.begin();
    retire.retireByMachine(kind);
    s.judge(retire);
    var summary =
        drain(
            s,
            new ScriptedModel()
                .answering(
                    CORRECTION, revising(JSON.textNode("A fiscal month closes on the 25th."))));

    assertThat(facts(s, calendar, Vocabulary.CALENDAR_RULE, null)).hasSize(1);
    assertThat(generated(summary).missingInputs())
        .anySatisfy(missing -> assertThat(missing).contains("its Dimension's kind does not stand"));
  }

  // Classifications

  /** A classification of the orders' status with a candidate kind and classified Field. */
  private record Classification(Node node, Dependency.OnFact kind, Dependency.OnFact classified) {}

  private static Classification classification(Scenario s, Scenario.Commit c, String slug) {
    var node = node(c, ElementKind.DIMENSION, slug);
    var kind =
        candidate(
            c,
            c.address(node.element(), Vocabulary.DIMENSION_KIND),
            JSON.textNode("classification"),
            IdentityKey.choice("classification"),
            node.existence());
    var status = field(s, "orders", "status");
    var classified =
        candidate(
            c,
            c.address(node.element(), Vocabulary.CLASSIFIED_FIELD),
            JSON.textNode(status),
            IdentityKey.of(new IdentityKey.Part.Reference(Ref.urn(status))),
            node.existence(),
            kind);
    return new Classification(node, kind, classified);
  }

  private static final String ORDER_STATE =
      "CASE WHEN status IN ('shipped', 'delivered') THEN 'fulfilled' ELSE 'open' END";

  @Test
  void aCorrectedClassifiedFieldIsChosenAmongTheColumnsAndRestsOnItsExistence() {
    var s = shopScenario();
    var c = s.begin();
    var states = classification(s, c, "order-state");
    var written = s.write(c);
    drain(s, new ScriptedModel());
    var dimension = written.urn(states.node().element());

    correct(s, written.urn(states.classified().fact()), Transition.CONFIRM, "It groups the amount");
    var model =
        new ScriptedModel()
            .answering(CORRECTION, revising(JSON.textNode("shop.public.orders.amount")));
    drain(s, model);

    var input = inputOf(model).path("facts").get(0);
    assertThat(input.path("value").asText()).isEqualTo("shop.public.orders.status");
    assertThat(input.path("choices").path("tables").findValuesAsText("ref"))
        .contains("shop.public.orders", "shop.public.shipments");
    var revised = revised(s, dimension, Vocabulary.CLASSIFIED_FIELD);
    assertThat(revised.value().asText()).isEqualTo(field(s, "orders", "amount"));
    // It rests on the kind and on the Field it names now, not on the one it named before.
    assertThat(factsDependedOn(revised))
        .contains(written.urn(states.kind().fact()), existence(field(s, "orders", "amount")))
        .doesNotContain(existence(field(s, "orders", "status")));
  }

  @Test
  void aCorrectedClassificationLevelIsParsedOverItsClassifiedFieldsTable() {
    var s = shopScenario();
    var c = s.begin();
    var states = classification(s, c, "order-state");
    var hierarchy =
        described(
            c,
            c.address(states.node().element(), Vocabulary.HIERARCHY),
            levels("order state", ORDER_STATE),
            states.node().existence(),
            states.kind(),
            states.classified());
    var written = s.write(c);
    drain(s, new ScriptedModel());
    var dimension = written.urn(states.node().element());

    // A level reading a column of another table is dropped without failing the task.
    correct(s, written.urn(hierarchy.fact()), Transition.CONFIRM, "By the order a shipment has");
    var summary =
        drain(
            s,
            new ScriptedModel()
                .answering(
                    CORRECTION,
                    revising(
                        levels(
                            "order state",
                            "CASE WHEN order_id IS NULL THEN 'open' ELSE 'fulfilled' END"))));
    assertThat(facts(s, dimension, Vocabulary.HIERARCHY, null)).hasSize(1);
    assertThat(generated(summary).missingInputs())
        .anySatisfy(missing -> assertThat(missing).contains("has no Field for"));
  }

  @Test
  void aCorrectedClassificationLevelRestsOnTheClassifiedFieldAndTheFieldsItReads() {
    var s = shopScenario();
    var c = s.begin();
    var states = classification(s, c, "order-state");
    var hierarchy =
        described(
            c,
            c.address(states.node().element(), Vocabulary.HIERARCHY),
            levels("order state", ""),
            states.node().existence(),
            states.kind());
    var written = s.write(c);
    drain(s, new ScriptedModel());
    var dimension = written.urn(states.node().element());

    correct(s, written.urn(hierarchy.fact()), Transition.CONFIRM, "Shipped orders are fulfilled");
    drain(
        s, new ScriptedModel().answering(CORRECTION, revising(levels("order state", ORDER_STATE))));

    var revised = revised(s, dimension, Vocabulary.HIERARCHY);
    assertThat(revised.value().path("levels").get(0).path("expression").asText())
        .isEqualTo(ORDER_STATE);
    assertThat(factsDependedOn(revised))
        .contains(
            written.urn(states.kind().fact()),
            written.urn(states.classified().fact()),
            existence(field(s, "orders", "status")));
  }
}
