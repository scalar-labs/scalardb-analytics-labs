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
import com.scalar.db.analytics.labs.semantic.model.FactRevision;
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
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Meaning inference: once a table's Dataset and Fields are confirmed, row and Field meaning
 * inference ask a language model what declarations and data cannot decide (see {@code
 * docs/semantic-catalog/generators.md}, "Meaning inference"). The model here is scripted per
 * Generator; the data lives in an in-memory DuckDB under the names the table metadata gives.
 */
class MeaningInferenceTest extends StoreScenarioTest {
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
  private static final AtomicLong VERSIONS = new AtomicLong();
  private static final String ROW = "row-meaning-inference";
  private static final String FIELD = "field-meaning-inference";
  private static final String CORRECTION = "correction-interpretation";

  private final List<DuckDbConstructionSql> engines = new ArrayList<>();

  @AfterEach
  void closeEngines() throws SQLException {
    for (var engine : engines) {
      engine.close();
    }
  }

  /** An order table with three orders, one of them not yet shipped. */
  private DuckDbConstructionSql orders() {
    var engine =
        new DuckDbConstructionSql(1000)
            .run(
                "ATTACH ':memory:' AS shop",
                "CREATE SCHEMA shop.public",
                "CREATE TABLE shop.public.orders"
                    + " (id INT, amount INT, status VARCHAR, ordered_at DATE, shipped_at DATE)",
                "INSERT INTO shop.public.orders VALUES"
                    + " (1, 1200, 'shipped', DATE '2026-11-01', DATE '2026-11-02'),"
                    + " (2, 800, 'shipped', DATE '2026-11-01', DATE '2026-11-03'),"
                    + " (3, 3000, 'ordered', DATE '2026-11-02', NULL)");
    engines.add(engine);
    return engine;
  }

  /** A policy evaluation model that asks for consent and records what it was asked about. */
  private static final class AskingConsent implements PolicyEvaluationModel {
    final List<String> asked = new ArrayList<>();

    @Override
    public JsonNode version() {
      return JSON.objectNode().put("model", "asking-consent");
    }

    @Override
    public synchronized Decision evaluate(Subject subject) {
      asked.add(subject.fact().revision().fact());
      return new Decision.RequestConsent("an inference only", JSON.objectNode());
    }
  }

  private static ObjectNode column(String name, String analyticsKind, boolean nullable) {
    return JSON.objectNode()
        .put("name", name)
        .put("nullable", nullable)
        .put("dataSourceType", analyticsKind)
        .set("analyticsType", JSON.objectNode().put("kind", analyticsKind));
  }

  private static ObjectNode ordersMetadata() {
    var node = JSON.objectNode();
    node.putArray("table").add("public").add("orders");
    node.put("kind", "table");
    var columns = node.putArray("columns");
    var list =
        List.of(
            column("id", "int", false),
            column("amount", "int", false),
            column("status", "text", false),
            column("ordered_at", "date", false),
            column("shipped_at", "date", true));
    for (var index = 0; index < list.size(); index++) {
      columns.add(list.get(index).deepCopy().put("ordinal", index + 1));
    }
    node.put("columnsMayBeHidden", false);
    var constraints = node.putObject("constraints").put("state", "value");
    var key =
        constraints
            .putArray("value")
            .addObject()
            .put("kind", "primary-key")
            .put("name", "orders_pkey")
            .put("enforced", true);
    key.putArray("columns").add("id");
    return node;
  }

  /** A table of integer columns {@code c1} to {@code c<count>} that declares no constraint. */
  private static ObjectNode wideMetadata(int count) {
    var node = JSON.objectNode();
    node.putArray("table").add("public").add("wide");
    node.put("kind", "table");
    var columns = node.putArray("columns");
    for (var index = 1; index <= count; index++) {
      columns.add(column("c" + index, "int", false).put("ordinal", index));
    }
    node.put("columnsMayBeHidden", false);
    node.putObject("constraints").put("state", "value").putArray("value");
    return node;
  }

  private static void register(Scenario s) {
    register(s, "orders", ordersMetadata(), "One row per customer order");
  }

  /** Registers a table's metadata; a different comment makes a new physical schema observation. */
  private static void register(Scenario s, String table, ObjectNode metadata, String comment) {
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
                new SourceMetadata(
                    "postgresql", JSON.objectNode().put("comment", comment).set("table", metadata)),
                Instant.parse("2026-11-16T00:00:00Z")),
            JSON.objectNode(),
            "digest-" + version));
  }

  private static BuildRun.Summary drain(
      Scenario s,
      PolicyEvaluationModel policy,
      ConstructionSql sql,
      ScriptedModel model,
      ModelInputSettings settings,
      BuildRun.Listener listener) {
    return store.runBuildTasks(
        s.domain,
        new BuildRun.Environment(policy, sql, model, settings),
        BuildRun.drain(),
        listener);
  }

  private static BuildRun.Summary drain(
      Scenario s, AskingConsent policy, ConstructionSql sql, ScriptedModel model) {
    var summary = drain(s, policy, sql, model, ModelInputSettings.defaults(), task -> {});
    assertThat(summary.finished())
        .allSatisfy(
            task -> assertThat(task.status()).isInstanceOf(BuildTask.Status.Succeeded.class));
    return summary;
  }

  // Scripts

  /** An answer for one target, each value given with a basis. */
  private static ObjectNode target(String ref, Object... predicateValues) {
    var target = JSON.objectNode().put("ref", ref);
    var values = target.putObject("values");
    for (var index = 0; index < predicateValues.length; index += 2) {
      var value = predicateValues[index + 1];
      var item = values.putObject((String) predicateValues[index]);
      if (value instanceof JsonNode json) {
        item.set("value", json);
      } else {
        item.put("value", (String) value);
      }
      item.put("basis", "the column's name and statistics");
    }
    return target;
  }

  private static String answer(ObjectNode... targets) {
    var answer = JSON.objectNode();
    var array = answer.putArray("targets");
    for (var target : targets) {
      array.add(target);
    }
    return answer.toString();
  }

  private static ScriptedModel ordersModel() {
    return new ScriptedModel()
        .answering(ROW, input -> answer(target("shop.public.orders", "row-kind", "event")))
        .answering(
            FIELD,
            input ->
                answer(
                    target(
                        "shop.public.orders.amount",
                        "meaning",
                        "合計金額",
                        "value-kind",
                        "amount",
                        "unit",
                        "JPY"),
                    target("shop.public.orders.status", "value-kind", "category"),
                    target(
                        "shop.public.orders.shipped_at",
                        "time-meaning",
                        JSON.objectNode().put("meaning", "出荷日").putNull("timeZone"))));
  }

  // Reading what was recorded

  private static String dataset(Scenario s) {
    return Urns.element(s.domain, ElementKind.DATASET, "shop.public.orders");
  }

  private static String field(Scenario s, String column) {
    return Urns.element(s.domain, ElementKind.FIELD, "shop.public.orders." + column);
  }

  private static List<FactRevision> facts(Scenario s, String element, String predicate) {
    var address = Urns.factAddress(element, predicate);
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

  private static FactRevision only(Scenario s, String element, String predicate) {
    var found = facts(s, element, predicate);
    assertThat(found).as(predicate + " of " + element).hasSize(1);
    return found.getFirst();
  }

  private static BuildTask.Result.Generated generated(BuildRun.Summary summary, String generator) {
    return summary.finished().stream()
        .filter(
            task ->
                task.work() instanceof BuildTask.Work.GeneratorRun run
                    && run.generator().equals(generator))
        .map(
            task ->
                (BuildTask.Result.Generated) ((BuildTask.Status.Succeeded) task.status()).result())
        .findFirst()
        .orElseThrow(() -> new AssertionError("No run of " + generator));
  }

  private static JsonNode inputOf(ScriptedModel model, String generator) {
    return model.calls.stream()
        .filter(call -> call.generator().equals(generator))
        .findFirst()
        .orElseThrow(() -> new AssertionError(generator + " did not ask the model"))
        .input();
  }

  private static JsonNode table(JsonNode input) {
    return input.path("tables").get(0);
  }

  private static List<String> asked(JsonNode input, String ref) {
    for (var target : input.path("targets")) {
      if (target.path("ref").asText().equals(ref)) {
        return StreamSupport.stream(target.path("predicates").spliterator(), false)
            .map(JsonNode::asText)
            .toList();
      }
    }
    return List.of();
  }

  // Scenarios

  @Test
  void confirmedFieldsAndDatasetsGetInferredCandidatesThatThePolicyEvaluationModelDecides() {
    var s = scenario();
    register(s);
    var policy = new AskingConsent();
    var model = ordersModel();

    var summary = drain(s, policy, orders(), model);

    var amountKind = only(s, field(s, "amount"), Vocabulary.VALUE_KIND);
    assertThat(amountKind.value().asText()).isEqualTo("amount");
    assertThat(amountKind.state()).isEqualTo(FactState.CANDIDATE);
    assertThat(amountKind.checks())
        .singleElement()
        .satisfies(
            check -> {
              assertThat(check.method()).isEqualTo(ValidationMethod.INFERENCE);
              assertThat(check.details().path("basis").asText())
                  .isEqualTo("the column's name and statistics");
            });
    assertThat(policy.asked).contains(amountKind.fact());
    assertThat(only(s, field(s, "amount"), Vocabulary.MEANING).value().asText()).isEqualTo("合計金額");
    assertThat(only(s, field(s, "amount"), Vocabulary.UNIT).value().asText()).isEqualTo("JPY");
    assertThat(only(s, field(s, "shipped_at"), Vocabulary.TIME_MEANING).value())
        .isEqualTo(JSON.objectNode().put("meaning", "出荷日").putNull("timeZone"));
    assertThat(only(s, dataset(s), Vocabulary.ROW_KIND).value().asText()).isEqualTo("event");

    // A candidate rests on the Field's existence and the physical schema it was read from.
    assertThat(amountKind.dependencies())
        .anySatisfy(dependency -> assertThat(dependency).isInstanceOf(Dependency.OnFact.class))
        .anySatisfy(dependency -> assertThat(dependency).isInstanceOf(Dependency.OnInput.class));
    // The version names the model and the instructions.
    var version = amountKind.provenance().path("version").asText();
    assertThat(version).contains("scripted-1").contains("\"name\":\"field-meaning-inference\"");
    assertThat(generated(summary, FIELD).generatorVersion()).isEqualTo(version);

    // A value outside the closed range is dropped and recorded as missing.
    assertThat(facts(s, field(s, "status"), Vocabulary.VALUE_KIND)).isEmpty();
    assertThat(generated(summary, FIELD).missingInputs())
        .anyMatch(
            missing ->
                missing.startsWith(
                    "value-kind of shop.public.orders.status, whose inferred value was"
                        + " dropped"));
  }

  @Test
  void theModelReadsMetadataAndCountStatisticsButNoValueSamplesByDefault() {
    var s = scenario();
    register(s);
    var model = ordersModel();

    var summary = drain(s, new AskingConsent(), orders(), model);

    var input = inputOf(model, FIELD);
    var table = table(input);
    assertThat(table.path("sourceMetadata").path("connector").asText()).isEqualTo("postgresql");
    assertThat(table.path("sourceMetadata").path("document").path("comment").asText())
        .isEqualTo("One row per customer order");
    assertThat(table.path("countStatistics").path("rows").asLong()).isEqualTo(3);
    assertThat(
            table
                .path("countStatistics")
                .path("columns")
                .path("shipped_at")
                .path("distinct")
                .asLong())
        .isEqualTo(2);
    assertThat(table.has("valueSamples")).isFalse();
    assertThat(input.toString()).doesNotContain("\"shipped\"").doesNotContain("\"ordered\"");
    assertThat(generated(summary, FIELD).missingInputs())
        .contains("the value samples of shop.public.orders, which the domain's settings withhold");

    // A value kind the column's type fixes is left to value kind from source type.
    assertThat(asked(input, "shop.public.orders.ordered_at"))
        .doesNotContain(Vocabulary.VALUE_KIND)
        .contains(Vocabulary.MEANING);
    assertThat(asked(input, "shop.public.orders.amount")).contains(Vocabulary.VALUE_KIND);

    // What the model read is recorded with each candidate, the statistics as a data observation.
    var amountKind = only(s, field(s, "amount"), Vocabulary.VALUE_KIND);
    assertThat(amountKind.checks().getFirst().reference()).isNotNull();
    assertThat(amountKind.inputsRead()).isNotEmpty();
  }

  @Test
  void withheldInputNeverReachesTheModelAndIsRecordedAsMissing() {
    var s = scenario();
    register(s);
    var model = ordersModel();

    var summary =
        drain(
            s,
            new AskingConsent(),
            orders(),
            model,
            new ModelInputSettings(false, false),
            task -> {});

    assertThat(summary.finished())
        .allSatisfy(
            task -> assertThat(task.status()).isInstanceOf(BuildTask.Status.Succeeded.class));
    var table = table(inputOf(model, FIELD));
    assertThat(table.has("countStatistics")).isFalse();
    assertThat(table.has("valueSamples")).isFalse();
    assertThat(generated(summary, FIELD).missingInputs())
        .contains(
            "the count statistics of shop.public.orders, which the domain's settings withhold",
            "the value samples of shop.public.orders, which the domain's settings withhold");
  }

  @Test
  void aDomainThatOptsInPassesSomeDistinctValues() {
    var s = scenario();
    register(s);
    var model = ordersModel();

    drain(s, new AskingConsent(), orders(), model, new ModelInputSettings(true, true), task -> {});

    var samples = table(inputOf(model, FIELD)).path("valueSamples").path("columns");
    assertThat(samples.path("status"))
        .containsExactly(JSON.textNode("ordered"), JSON.textNode("shipped"));
  }

  @Test
  void aPendingCandidateKeepsTheModelFromProposingAnotherThere() {
    var s = scenario();
    register(s);
    var model = ordersModel();
    var amount = field(s, "amount");

    // A candidate for the amount's meaning arrives as soon as its existence is confirmed.
    drain(
        s,
        new AskingConsent(),
        orders(),
        model,
        ModelInputSettings.defaults(),
        task -> {
          if (task.work() instanceof BuildTask.Work.GeneratorRun run
              && run.generator().equals("table-and-column-ingestion")) {
            proposeMeaning(s, amount, "an earlier meaning");
          }
        });

    assertThat(asked(inputOf(model, FIELD), "shop.public.orders.amount"))
        .doesNotContain(Vocabulary.MEANING)
        .contains(Vocabulary.VALUE_KIND);
    assertThat(facts(s, amount, Vocabulary.MEANING))
        .singleElement()
        .satisfies(fact -> assertThat(fact.value().asText()).isEqualTo("an earlier meaning"));
  }

  @Test
  void aTargetThatGetsACandidateWhileTheModelAnswersGetsNoSecondOne() {
    var s = scenario();
    register(s);
    var amount = field(s, "amount");
    var model =
        new ScriptedModel()
            .answering(
                FIELD,
                input -> {
                  proposeMeaning(s, amount, "written meanwhile");
                  return answer(target("shop.public.orders.amount", "meaning", "合計金額"));
                });

    drain(s, new AskingConsent(), orders(), model);

    assertThat(facts(s, amount, Vocabulary.MEANING))
        .singleElement()
        .satisfies(fact -> assertThat(fact.value().asText()).isEqualTo("written meanwhile"));
  }

  @Test
  void anAnswerNotInTheFormAskedForFailsTheTaskAndRecordsNothing() {
    var s = scenario();
    register(s);
    var model = new ScriptedModel().answering(FIELD, input -> "The amount is money.");

    var summary =
        drain(s, new AskingConsent(), orders(), model, ModelInputSettings.defaults(), task -> {});

    assertThat(summary.finished())
        .filteredOn(
            task ->
                task.work() instanceof BuildTask.Work.GeneratorRun run
                    && run.generator().equals(FIELD))
        .singleElement()
        .satisfies(
            task ->
                assertThat(((BuildTask.Status.Failed) task.status()).reason())
                    .contains("not one JSON object"));
    assertThat(facts(s, field(s, "amount"), Vocabulary.MEANING)).isEmpty();
  }

  @Test
  void aWideTableIsAskedAboutInCallsOfABoundedSize() {
    var s = scenario();
    var columns = 30;
    var definitions = new ArrayList<String>();
    var values = new ArrayList<String>();
    for (var index = 1; index <= columns; index++) {
      definitions.add("c" + index + " INT");
      values.add(String.valueOf(index));
    }
    var engine =
        new DuckDbConstructionSql(1000)
            .run(
                "ATTACH ':memory:' AS shop",
                "CREATE SCHEMA shop.public",
                "CREATE TABLE shop.public.wide (" + String.join(", ", definitions) + ")",
                "INSERT INTO shop.public.wide VALUES (" + String.join(", ", values) + ")");
    engines.add(engine);
    register(s, "wide", wideMetadata(columns), "A wide table");
    var model =
        new ScriptedModel()
            .answering(
                FIELD,
                input ->
                    answer(
                        StreamSupport.stream(input.path("targets").spliterator(), false)
                            .map(
                                target -> target(target.path("ref").asText(), "meaning", "a count"))
                            .toArray(ObjectNode[]::new)));

    drain(s, new AskingConsent(), engine, model);

    var calls = model.calls.stream().filter(call -> call.generator().equals(FIELD)).toList();
    assertThat(calls).hasSize(2);
    assertThat(calls)
        .allSatisfy(
            call -> assertThat(call.input().path("targets").size()).isLessThanOrEqualTo(25));
    for (var index = 1; index <= columns; index++) {
      var field = Urns.element(s.domain, ElementKind.FIELD, "shop.public.wide.c" + index);
      assertThat(facts(s, field, Vocabulary.MEANING)).as("meaning of c" + index).hasSize(1);
    }
  }

  @Test
  void aSchemaObservedWhileTheModelAnswersLeavesTheCandidateOnWhatTheModelRead() {
    var s = scenario();
    register(s);
    var observedAgain = new java.util.concurrent.atomic.AtomicBoolean();
    var model =
        new ScriptedModel()
            .answering(
                FIELD,
                input -> {
                  if (observedAgain.compareAndSet(false, true)) {
                    register(s, "orders", ordersMetadata(), "Customer orders, renamed comment");
                  }
                  return answer(target("shop.public.orders.amount", "meaning", "合計金額"));
                });

    drain(s, new AskingConsent(), orders(), model);

    var meaning = only(s, field(s, "amount"), Vocabulary.MEANING);
    // The physical schema observation it depends on is the one the model read, not the newer one.
    var comments =
        meaning.dependencies().stream()
            .filter(Dependency.OnInput.class::isInstance)
            .map(dependency -> ((Dependency.OnInput) dependency).observation().urnValue())
            .map(observation -> store.observationContent(s.domain, observation).orElseThrow())
            .filter(content -> content.has("sourceMetadata"))
            .map(
                content -> content.path("sourceMetadata").path("document").path("comment").asText())
            .toList();
    assertThat(comments).containsExactly("One row per customer order");
  }

  // Correction interpretation

  /** Answers correction interpretation with a revision of the first related Fact. */
  private static String revision(JsonNode input, @Nullable String value, @Nullable String scope) {
    var answer = JSON.objectNode();
    var target =
        answer
            .putArray("targets")
            .addObject()
            .put("ref", input.path("facts").get(0).path("ref").asText());
    target.put("value", value);
    target.put("scope", scope);
    target.put("basis", "the deciding party said so");
    return answer.toString();
  }

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
                    "the value presented for shop.public.orders.amount",
                    null))));
  }

  private static String existence(String element) {
    return Urns.fact(Urns.factAddress(element, Vocabulary.EXISTENCE), 1);
  }

  private static List<String> factsDependedOn(FactRevision revision) {
    return revision.dependencies().stream()
        .filter(Dependency.OnFact.class::isInstance)
        .map(dependency -> ((Dependency.OnFact) dependency).fact().urnValue())
        .toList();
  }

  @Test
  void aCorrectionYieldsOneRevisedCandidateThatThePolicyEvaluationModelDecides() {
    var s = scenario();
    register(s);
    var policy = new AskingConsent();
    var model = ordersModel();
    drain(s, policy, orders(), model);
    var amount = field(s, "amount");
    var presented = only(s, amount, Vocabulary.VALUE_KIND);

    var outcome = correct(s, presented.fact(), Transition.CONFIRM, "金額ではなく数量です");

    // The correction calls for correction interpretation in the same commit.
    var feedback = outcome.outcomes().getFirst().feedback();
    assertThat(outcome.buildTasks())
        .map(number -> store.buildTask(s.domain, number).orElseThrow().work())
        .contains(new BuildTask.Work.GeneratorRun(CORRECTION));

    model.answering(CORRECTION, input -> revision(input, "quantity", null));
    var summary = drain(s, policy, orders(), model);

    var input = inputOf(model, CORRECTION);
    assertThat(input.path("feedback").path("content").asText()).isEqualTo("金額ではなく数量です");
    assertThat(input.path("facts").get(0).path("ref").asText()).isEqualTo(presented.fact());
    var kinds = facts(s, amount, Vocabulary.VALUE_KIND);
    assertThat(kinds).hasSize(2);
    assertThat(kinds.getFirst().state()).isEqualTo(FactState.REJECTED);
    var revised = kinds.get(1);
    assertThat(revised.value().asText()).isEqualTo("quantity");
    // An inference only: the policy evaluation model decides it; it is never adopted inline.
    assertThat(revised.state()).isEqualTo(FactState.CANDIDATE);
    assertThat(revised.checks())
        .singleElement()
        .satisfies(check -> assertThat(check.method()).isEqualTo(ValidationMethod.INFERENCE));
    assertThat(policy.asked).contains(revised.fact());
    // It rests on the feedback and on the Field's existence, never on the rejected candidate.
    assertThat(revised.dependencies())
        .anySatisfy(
            dependency -> {
              assertThat(dependency).isInstanceOf(Dependency.OnInput.class);
              assertThat(((Dependency.OnInput) dependency).inputResource().urnValue())
                  .isEqualTo(feedback);
            });
    assertThat(factsDependedOn(revised)).containsExactly(existence(amount));
    // The corrected candidate is read, at its latest acceptance.
    assertThat(revised.inputsRead().toString())
        .contains(presented.fact().replace(":fact:", ":fact-revision:") + "@");
    assertThat(generated(summary, CORRECTION).missingInputs()).isEmpty();

    // Running again for the same feedback yields no second candidate and asks nothing.
    var task =
        outcome.buildTasks().stream()
            .map(number -> store.buildTask(s.domain, number).orElseThrow())
            .filter(found -> found.work().equals(new BuildTask.Work.GeneratorRun(CORRECTION)))
            .findFirst()
            .orElseThrow();
    store.requestGeneratorRun(s.domain, CORRECTION, task.triggers().getFirst());
    drain(s, policy, orders(), model);
    assertThat(model.calls.stream().filter(call -> call.generator().equals(CORRECTION))).hasSize(1);
    assertThat(facts(s, amount, Vocabulary.VALUE_KIND)).hasSize(2);
  }

  @Test
  void aCorrectionThatLimitsWhereAStatementHoldsYieldsACandidateAtAScopedFactAddress() {
    var s = scenario();
    register(s);
    var policy = new AskingConsent();
    var model = ordersModel();
    drain(s, policy, orders(), model);
    var amount = field(s, "amount");
    var presented = only(s, amount, Vocabulary.MEANING);

    correct(s, presented.fact(), Transition.CONFIRM, "法人注文では税抜きの合計金額です");
    model.answering(CORRECTION, input -> revision(input, "税抜きの合計金額", "法人注文"));
    drain(s, policy, orders(), model);

    var scoped =
        s.knowledge(s.lastCommit(), amount, Vocabulary.MEANING).stream()
            .filter(address -> "法人注文".equals(address.scopeDescription()))
            .toList();
    assertThat(scoped).singleElement();
    var address = scoped.getFirst().factAddress();
    assertThat(address).isEqualTo(Urns.numbered(Urns.factAddress(amount, Vocabulary.MEANING), 2));
    var revised = s.revision(Urns.fact(address, 1));
    assertThat(revised.value().asText()).isEqualTo("税抜きの合計金額");
    assertThat(revised.state()).isEqualTo(FactState.CANDIDATE);
    assertThat(factsDependedOn(revised)).containsExactly(existence(amount));
  }

  @Test
  void aCorrectionOfARetirementProposalYieldsACandidateThatReplacesTheConfirmedFact() {
    var s = scenario();
    register(s);
    var policy = new AskingConsent();
    var model = ordersModel();
    drain(s, policy, orders(), model);
    var amount = field(s, "amount");
    var confirmed = only(s, amount, Vocabulary.VALUE_KIND).fact();
    Judgement.submit(s, Judgement.answer(confirmed, AnswerType.CONSENT));
    Judgement.setPolicy(s, Preset.MANUAL);
    var commit = s.begin();
    Judgement.proposeRetire(commit, Ref.urn(confirmed));
    s.judge(commit);

    correct(s, confirmed, Transition.RETIRE, "金額ではなく数量です");
    model.answering(CORRECTION, input -> revision(input, "quantity", null));
    drain(s, policy, orders(), model);

    assertThat(s.state(confirmed)).contains(FactState.CONFIRMED);
    var kinds = facts(s, amount, Vocabulary.VALUE_KIND);
    assertThat(kinds).hasSize(2);
    var revised = kinds.get(1);
    assertThat(revised.value().asText()).isEqualTo("quantity");
    assertThat(revised.state()).isEqualTo(FactState.CANDIDATE);
    assertThat(factsDependedOn(revised)).containsExactlyInAnyOrder(existence(amount), confirmed);
  }

  @Test
  void aCorrectionWhoseWordingGivesNoRevisionIsRecordedOnTheBuildTask() {
    var s = scenario();
    register(s);
    var policy = new AskingConsent();
    var model = ordersModel();
    drain(s, policy, orders(), model);
    var amount = field(s, "amount");
    var presented = only(s, amount, Vocabulary.VALUE_KIND);

    correct(s, presented.fact(), Transition.CONFIRM, "違います");
    var summary = drain(s, policy, orders(), model);

    assertThat(facts(s, amount, Vocabulary.VALUE_KIND)).hasSize(1);
    assertThat(generated(summary, CORRECTION).missingInputs())
        .singleElement()
        .satisfies(
            missing ->
                assertThat(missing).contains("whose wording gave neither").endsWith(": 違います"));
  }

  @Test
  void aCorrectionOfAPredicateItDoesNotInterpretIsRecordedWithoutAskingTheModel() {
    var s = scenario();
    register(s);
    var policy = new AskingConsent();
    var model = ordersModel();
    drain(s, policy, orders(), model);
    var existence = existence(field(s, "amount"));
    Judgement.setPolicy(s, Preset.MANUAL);
    var commit = s.begin();
    Judgement.proposeRetire(commit, Ref.urn(existence));
    s.judge(commit);

    correct(s, existence, Transition.RETIRE, "列名が変わっただけです");
    var summary = drain(s, policy, orders(), model);

    assertThat(model.calls.stream().filter(call -> call.generator().equals(CORRECTION))).isEmpty();
    assertThat(generated(summary, CORRECTION).missingInputs())
        .singleElement()
        .satisfies(
            missing ->
                assertThat(missing)
                    .contains("whose predicate existence correction interpretation does not")
                    .endsWith(": 列名が変わっただけです"));
  }

  /** Writes a meaning candidate on a Field, as another Generator would. */
  private static void proposeMeaning(Scenario s, String field, String meaning) {
    var commit = s.begin();
    commit.candidate(commit.address(Ref.urn(field), Vocabulary.MEANING), meaning);
    s.write(commit);
  }
}
