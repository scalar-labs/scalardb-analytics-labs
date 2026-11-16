/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import static com.scalar.db.analytics.labs.semantic.Judgement.answer;
import static com.scalar.db.analytics.labs.semantic.Judgement.declaredDataset;
import static com.scalar.db.analytics.labs.semantic.Judgement.fact;
import static com.scalar.db.analytics.labs.semantic.Judgement.setPolicy;
import static com.scalar.db.analytics.labs.semantic.Judgement.submit;
import static com.scalar.db.analytics.labs.semantic.Judgement.supported;
import static com.scalar.db.analytics.labs.semantic.PolicyEvaluationTest.reviewedFacts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.scalar.db.analytics.labs.semantic.model.BuildRunInProgressException;
import com.scalar.db.analytics.labs.semantic.model.BuildTask;
import com.scalar.db.analytics.labs.semantic.model.ElementIdentity;
import com.scalar.db.analytics.labs.semantic.model.Knowledge;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Review;
import com.scalar.db.analytics.labs.semantic.model.SemanticCatalogException;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.AnswerType;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.CheckResult;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ConfirmationPath;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Preset;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ValidationMethod;
import com.scalar.db.analytics.labs.semantic.policy.PolicyEvaluationModel;
import com.scalar.db.analytics.labs.semantic.policy.PolicyEvaluationModel.Decision;
import com.scalar.db.analytics.labs.semantic.policy.PolicyEvaluationModel.Subject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Build tasks: the evaluation tasks a commit creates for what inline evaluation leaves to the
 * policy evaluation model, the re-evaluation a new policy version creates, and running them under
 * the domain's builder semaphore.
 */
class BuildTasksTest extends StoreScenarioTest {
  private static final JsonNode MODEL_VERSION =
      JsonNodeFactory.instance.objectNode().put("model", "stand-in").put("prompt", "v1");

  /** Policy evaluation reads no data values, so a construction query fails the test. */
  static final ConstructionSql NO_CONSTRUCTION_SQL =
      request -> {
        throw new AssertionError("Policy evaluation ran a construction query");
      };

  /** A stand-in for the policy evaluation model that answers by a rule and records its subjects. */
  private static final class StandIn implements PolicyEvaluationModel {
    private final Function<Subject, Decision> rule;
    final List<Subject> subjects = new ArrayList<>();

    StandIn(Function<Subject, Decision> rule) {
      this.rule = rule;
    }

    @Override
    public JsonNode version() {
      return MODEL_VERSION;
    }

    @Override
    public synchronized Decision evaluate(Subject subject) {
      subjects.add(subject);
      return rule.apply(subject);
    }
  }

  private static StandIn adopting() {
    return new StandIn(subject -> new Decision.Adopt("the name, values, and glossary agree"));
  }

  private static StandIn askingConsent() {
    return new StandIn(
        subject ->
            new Decision.RequestConsent(
                "only the name suggests it",
                JsonNodeFactory.instance.objectNode().put("suggested", "consent")));
  }

  private static BuildRun.Summary drain(Scenario s, PolicyEvaluationModel model) {
    return store.runBuildTasks(
        s.domain,
        new BuildRun.Environment(model, NO_CONSTRUCTION_SQL),
        BuildRun.drain(),
        task -> {});
  }

  /**
   * The tasks among these that evaluate the policy. Confirming a Dataset or Field also runs the
   * schema-interpretation Generators, which these scenarios built by hand give nothing to read.
   */
  private static List<Long> evaluations(Scenario s, List<Long> numbers) {
    return numbers.stream().filter(number -> !isGeneratorRun(task(s, number))).toList();
  }

  private static List<BuildTask> evaluations(BuildRun.Summary summary) {
    return evaluations(summary.finished());
  }

  private static List<BuildTask> evaluations(List<BuildTask> tasks) {
    return tasks.stream().filter(task -> !isGeneratorRun(task)).toList();
  }

  private static boolean isGeneratorRun(BuildTask task) {
    return task.work() instanceof BuildTask.Work.GeneratorRun;
  }

  private static BuildTask task(Scenario s, long number) {
    return store.buildTask(s.domain, number).orElseThrow();
  }

  private static BuildTask.Result result(Scenario s, long number) {
    return ((BuildTask.Status.Succeeded) task(s, number).status()).result();
  }

  /** A Dataset whose row kind only inference supports, which inline evaluation leaves undecided. */
  private record Inferred(CommitResult written, String dataset, String existence, String rowKind) {}

  private static Inferred inferredRowKind(Scenario s, String table) {
    var c = s.begin();
    var orders = declaredDataset(c, table);
    var rowKind =
        fact(
            c,
            c.address(orders.dataset(), "row-kind"),
            "event",
            List.of(supported(ValidationMethod.INFERENCE, "event")),
            orders.existence().on());
    var written = s.judge(c);
    return new Inferred(
        written,
        written.urn(orders.dataset()),
        written.urn(orders.existence().fact()),
        written.urn(rowKind.fact()));
  }

  @Test
  void aCandidateLeftUndecidedInlineGetsAnEvaluationTaskInTheSameCommit() {
    var s = scenario();
    var inferred = inferredRowKind(s, "shop.public.orders");

    assertThat(evaluations(s, inferred.written().buildTasks())).hasSize(1);
    var created = task(s, evaluations(s, inferred.written().buildTasks()).getFirst());
    assertThat(created.work()).isEqualTo(new BuildTask.Work.PolicyEvaluation(inferred.rowKind()));
    assertThat(created.status()).isEqualTo(new BuildTask.Status.Created());
    assertThat(created.triggers()).hasSize(1);
    assertThat(created.triggers().getFirst()).contains(":log-record:c" + s.lastCommit() + ".");

    // An additional acceptance evaluates the candidate again, and the trigger joins the created
    // task rather than creating another.
    var c = s.begin();
    c.add(
        new LogRecord.Accept(
            Ref.urn(inferred.rowKind()),
            List.of(
                s.on(inferred.existence()),
                s.on(selectedFact(s, inferred.dataset(), Vocabulary.PHYSICAL_CATALOG_REFERENCE))),
            List.of(supported(ValidationMethod.INFERENCE, "event")),
            Scenario.NOTHING,
            Scenario.NOTHING));
    var again = s.judge(c);
    assertThat(evaluations(s, again.buildTasks())).containsExactly(created.number());
    assertThat(task(s, created.number()).triggers()).hasSize(2);
    assertThat(evaluations(store.buildTasks(s.domain))).hasSize(1);
  }

  private static String selectedFact(Scenario s, String element, String predicate) {
    var knowledge = (Knowledge.Selected) s.only(s.lastCommit(), element, predicate);
    return knowledge.fact().revision().fact();
  }

  @Test
  void anAdoptionByTheModelConfirmsTheCandidateAndItsDependentsInOneCommit() {
    var s = scenario();
    var c = s.begin();
    var orders = declaredDataset(c, "shop.public.orders");
    var rowKind =
        fact(
            c,
            c.address(orders.dataset(), "row-kind"),
            "event",
            List.of(supported(ValidationMethod.INFERENCE, "event")),
            orders.existence().on());
    // Rests on the undecided row kind, so it is not evaluable until the row kind is judged.
    var grain =
        fact(
            c,
            c.address(orders.dataset(), "row-grain"),
            "order",
            List.of(supported(ValidationMethod.DECLARED_ENFORCED, "order")),
            rowKind.on());
    var written = s.judge(c);
    var rowKindUrn = written.urn(rowKind.fact());
    var grainUrn = written.urn(grain.fact());
    assertThat(s.state(grainUrn)).contains(FactState.CANDIDATE);

    var model = adopting();
    var summary = drain(s, model);

    var evaluated =
        (BuildTask.Result.Evaluated) result(s, evaluations(s, written.buildTasks()).getFirst());
    assertThat(evaluated.modelVersion()).isEqualTo(MODEL_VERSION);
    assertThat(evaluated.commit()).isEqualTo(s.lastCommit());
    assertThat(evaluations(summary)).extracting(BuildTask::number).containsExactly(1L);
    assertThat(s.state(rowKindUrn)).contains(FactState.CONFIRMED);
    assertThat(s.revision(rowKindUrn).confirmationPath())
        .isEqualTo(ConfirmationPath.MACHINE_JUDGEMENT);
    assertThat(s.revision(rowKindUrn).event().commit()).isEqualTo(evaluated.commit());
    // The dependent became evaluable and was adopted inline in the same commit.
    assertThat(s.state(grainUrn)).contains(FactState.CONFIRMED);
    assertThat(s.revision(grainUrn).event().commit()).isEqualTo(evaluated.commit());

    var subject = (Subject.Confirmation) model.subjects.getFirst();
    assertThat(subject.fact().revision().fact()).isEqualTo(rowKindUrn);
    assertThat(subject.target().predicate()).isEqualTo("row-kind");
    assertThat(subject.premises())
        .extracting(premise -> premise.revision().fact())
        .containsExactly(written.urn(orders.existence().fact()));
  }

  @Test
  void aConsentRequestByTheModelListsReviewMaterialAndMakesDependentsEvaluable() {
    var s = scenario();
    var c = s.begin();
    var orders = declaredDataset(c, "shop.public.orders");
    var rowKind =
        fact(
            c,
            c.address(orders.dataset(), "row-kind"),
            "event",
            List.of(supported(ValidationMethod.INFERENCE, "event")),
            orders.existence().on());
    var grain =
        fact(
            c,
            c.address(orders.dataset(), "row-grain"),
            "order line",
            List.of(supported(ValidationMethod.INFERENCE, "order line")),
            rowKind.on());
    var written = s.judge(c);
    var rowKindUrn = written.urn(rowKind.fact());
    var grainUrn = written.urn(grain.fact());

    var model = askingConsent();
    var summary = drain(s, model);

    // The row kind's consent request made the grain evaluable; inline evaluation left it to the
    // model too, and the same run evaluated it.
    assertThat(evaluations(summary)).hasSize(2);
    assertThat(model.subjects)
        .extracting(subject -> subject.fact().revision().fact())
        .containsExactly(rowKindUrn, grainUrn);
    assertThat(reviewedFacts(s)).containsExactlyInAnyOrder(rowKindUrn, grainUrn);
    var item =
        s.latest(snapshot -> snapshot.reviewList(Review.Query.ALL)).stream()
            .flatMap(group -> group.items().stream())
            .map(Review.Candidate.class::cast)
            .filter(candidate -> candidate.fact().revision().fact().equals(grainUrn))
            .findFirst()
            .orElseThrow();
    var evaluator = (LogRecord.Evaluator.Model) item.evaluation().record().evaluator();
    assertThat(evaluator.version()).isEqualTo(MODEL_VERSION);
    assertThat(evaluator.reviewMaterial())
        .isEqualTo(JsonNodeFactory.instance.objectNode().put("suggested", "consent"));
    assertThat(item.premiseItems()).containsExactly(rowKindUrn);

    // Consenting to both confirms them in one commit, as for any consent request.
    submit(s, answer(grainUrn, AnswerType.CONSENT), answer(rowKindUrn, AnswerType.CONSENT));
    assertThat(s.state(rowKindUrn)).contains(FactState.CONFIRMED);
    assertThat(s.state(grainUrn)).contains(FactState.CONFIRMED);
  }

  @Test
  void aCandidateJudgedBeforeItsTaskRunsRecordsNothing() {
    var s = scenario();
    var inferred = inferredRowKind(s, "shop.public.orders");
    var task = evaluations(s, inferred.written().buildTasks()).getFirst();
    submit(s, answer(inferred.rowKind(), AnswerType.CONSENT));
    assertThat(s.state(inferred.rowKind())).contains(FactState.CONFIRMED);
    var before = s.lastCommit();

    var model = adopting();
    drain(s, model);

    assertThat(result(s, task)).isEqualTo(new BuildTask.Result.NoLongerAwaiting());
    assertThat(model.subjects).isEmpty();
    assertThat(s.lastCommit()).isEqualTo(before);
  }

  @Test
  void anEvaluationMadeOnStaleMaterialIsReplacedByANewTask() {
    var s = scenario();
    var inferred = inferredRowKind(s, "shop.public.orders");
    var task = evaluations(s, inferred.written().buildTasks()).getFirst();
    var first = new AtomicBoolean(true);
    var model =
        new StandIn(
            subject -> {
              if (first.getAndSet(false)) {
                // The semantics owner sets a new version while the model runs.
                setPolicy(s, Preset.AUTO, true);
              }
              return new Decision.Adopt("agreed");
            });

    var summary = drain(s, model);

    var superseded = (BuildTask.Result.Superseded) result(s, task);
    assertThat(result(s, superseded.followUp())).isInstanceOf(BuildTask.Result.Evaluated.class);
    assertThat(evaluations(summary))
        .extracting(finished -> finished.work().kind())
        .containsExactly(
            BuildTask.Kind.POLICY_EVALUATION,
            BuildTask.Kind.POLICY_RE_EVALUATION,
            BuildTask.Kind.POLICY_EVALUATION);
    assertThat(model.subjects).hasSize(2);
    assertThat(s.state(inferred.rowKind())).contains(FactState.CONFIRMED);
  }

  @Test
  void aFailedTaskIsResumedAndRunsOnTheLatestState() {
    var s = scenario();
    var inferred = inferredRowKind(s, "shop.public.orders");
    var task = evaluations(s, inferred.written().buildTasks()).getFirst();

    var unavailable =
        new StandIn(
            subject -> {
              throw new IllegalStateException("The policy evaluation model is unavailable");
            });
    var summary = drain(s, unavailable);
    assertThat(evaluations(summary)).hasSize(1);
    assertThat(task(s, task).status())
        .isEqualTo(new BuildTask.Status.Failed("The policy evaluation model is unavailable"));
    assertThat(s.state(inferred.rowKind())).contains(FactState.CANDIDATE);
    // A failed task is not run again until resumed.
    assertThat(evaluations(drain(s, adopting()))).isEmpty();

    assertThat(store.resumeBuildTask(s.domain, task).status())
        .isEqualTo(new BuildTask.Status.Created());
    assertThatThrownBy(() -> store.resumeBuildTask(s.domain, task))
        .isInstanceOf(SemanticCatalogException.class)
        .hasMessageContaining("only a failed task is resumed");
    drain(s, adopting());
    assertThat(result(s, task)).isInstanceOf(BuildTask.Result.Evaluated.class);
    assertThat(s.state(inferred.rowKind())).contains(FactState.CONFIRMED);
  }

  @Test
  void aSecondRunIsRefusedWhileOperationsProceed() throws Exception {
    var s = scenario();
    var inferred = inferredRowKind(s, "shop.public.orders");
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var blocking =
        new StandIn(
            subject -> {
              entered.countDown();
              try {
                release.await(30, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return new Decision.Adopt("agreed");
            });
    var first = CompletableFuture.supplyAsync(() -> drain(s, blocking));
    assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();

    assertThatThrownBy(() -> drain(s, adopting())).isInstanceOf(BuildRunInProgressException.class);
    // Answers, reads, and other writes proceed while the run holds the semaphore.
    var other = inferredRowKind(s, "shop.public.customers");
    submit(s, answer(other.rowKind(), AnswerType.REJECTION));
    assertThat(s.state(other.rowKind())).contains(FactState.REJECTED);

    release.countDown();
    first.get(30, TimeUnit.SECONDS);
    assertThat(s.state(inferred.rowKind())).contains(FactState.CONFIRMED);
    // The semaphore is released with the run.
    drain(s, adopting());
  }

  @Test
  void aRunStartsByReturningAbandonedTasksToCreated() throws Exception {
    var s = scenario();
    var inferred = inferredRowKind(s, "shop.public.orders");
    var task = evaluations(s, inferred.written().buildTasks()).getFirst();
    // A run that stopped during its model call left the task running and wrote nothing.
    try (var connection = TestStore.connect();
        var statement =
            connection.prepareStatement(
                "UPDATE semantic_catalog.build_tasks SET state = 'running',"
                    + " content = jsonb_set(content, '{status}', '{\"state\": \"running\"}')"
                    + " WHERE domain = ? AND task = ?")) {
      statement.setString(1, s.domain);
      statement.setLong(2, task);
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
    assertThat(task(s, task).status()).isEqualTo(new BuildTask.Status.Running());

    var summary = drain(s, adopting());

    assertThat(summary.reset()).containsExactly(task);
    assertThat(result(s, task)).isInstanceOf(BuildTask.Result.Evaluated.class);
  }

  @Test
  void aWaitingRunPicksUpTasksAnotherWriterCreatesUntilStopped() throws Exception {
    var s = scenario();
    var stop = new AtomicBoolean();
    var finished = new CountDownLatch(1);
    var run =
        CompletableFuture.supplyAsync(
            () ->
                store.runBuildTasks(
                    s.domain,
                    new BuildRun.Environment(adopting(), NO_CONSTRUCTION_SQL),
                    BuildRun.keepWaiting(Duration.ofMillis(20), stop::get),
                    task -> finished.countDown()));

    var inferred = inferredRowKind(s, "shop.public.orders");
    assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
    stop.set(true);
    var summary = run.get(30, TimeUnit.SECONDS);

    assertThat(evaluations(summary))
        .extracting(BuildTask::number)
        .containsExactly(evaluations(s, inferred.written().buildTasks()).getFirst());
    assertThat(s.state(inferred.rowKind())).contains(FactState.CONFIRMED);
  }

  @Test
  void loosenedPolicyConfirmsTheCandidatesAwaitingConsentInOneCommit() {
    var s = scenario();
    setPolicy(s, Preset.MANUAL);
    var c = s.begin();
    var orders = declaredDataset(c, "shop.public.orders");
    var rowKind =
        fact(
            c,
            c.address(orders.dataset(), "row-kind"),
            "event",
            List.of(supported(ValidationMethod.INFERENCE, "event")),
            orders.existence().on());
    var written = s.judge(c);
    var facts =
        List.of(
            written.urn(orders.existence().fact()),
            written.urn(orders.reference().fact()),
            written.urn(rowKind.fact()));
    assertThat(reviewedFacts(s)).containsExactlyInAnyOrderElementsOf(facts);
    assertThat(evaluations(s, written.buildTasks())).isEmpty();

    var set =
        store.setPolicyVersion(
            s.domain, new LogRecord.PolicyVersion(Preset.FULL_AUTO, false, "owner", "loosen"));
    assertThat(task(s, set.reEvaluation()).work())
        .isEqualTo(new BuildTask.Work.PolicyReEvaluation());
    // Setting manual earlier created the task, which the run has not taken yet.
    assertThat(task(s, set.reEvaluation()).triggers()).endsWith(set.logRecord());

    var model = adopting();
    drain(s, model);

    var reEvaluated = (BuildTask.Result.ReEvaluated) result(s, set.reEvaluation());
    assertThat(reEvaluated.policyVersion()).isEqualTo(set.logRecord());
    assertThat(reEvaluated.commit()).isEqualTo(s.lastCommit());
    assertThat(reEvaluated.evaluationTasks()).isEmpty();
    for (var fact : facts) {
      assertThat(s.state(fact)).contains(FactState.CONFIRMED);
      assertThat(s.revision(fact).event().commit()).isEqualTo(reEvaluated.commit());
    }
    assertThat(model.subjects).isEmpty();
    assertThat(reviewedFacts(s)).isEmpty();
  }

  @Test
  void reEvaluationUnderAutoLeavesWhatInlineEvaluationCannotDecideToTheModel() {
    var s = scenario();
    setPolicy(s, Preset.MANUAL);
    var inferred = inferredRowKind(s, "shop.public.orders");
    assertThat(reviewedFacts(s)).contains(inferred.rowKind());

    var set =
        store.setPolicyVersion(
            s.domain, new LogRecord.PolicyVersion(Preset.AUTO, false, "owner", "back to auto"));
    // Setting the version sends every consent request back to policy evaluation at once: the
    // existence awaits policy evaluation, and the row kind resting on it awaits its premise.
    assertThat(reviewedFacts(s)).isEmpty();
    var existence =
        (Knowledge.AwaitingJudgement)
            s.only(s.lastCommit(), inferred.dataset(), Vocabulary.EXISTENCE);
    assertThat(existence.candidates().getFirst().waitingFor())
        .isEqualTo(Knowledge.Waiting.POLICY_EVALUATION);
    var rowKind =
        (Knowledge.AwaitingJudgement) s.only(s.lastCommit(), inferred.dataset(), "row-kind");
    assertThat(rowKind.candidates().getFirst().waitingFor())
        .isEqualTo(Knowledge.Waiting.DEPENDENCIES);
    // Two versions set before the re-evaluation runs share one task.
    var again =
        store.setPolicyVersion(
            s.domain, new LogRecord.PolicyVersion(Preset.AUTO, false, "owner", "same again"));
    assertThat(again.reEvaluation()).isEqualTo(set.reEvaluation());
    assertThat(task(s, set.reEvaluation()).triggers()).endsWith(set.logRecord(), again.logRecord());

    var model = askingConsent();
    drain(s, model);

    var reEvaluated = (BuildTask.Result.ReEvaluated) result(s, set.reEvaluation());
    assertThat(reEvaluated.policyVersion()).isEqualTo(again.logRecord());
    assertThat(reEvaluated.evaluationTasks()).hasSize(1);
    assertThat(task(s, reEvaluated.evaluationTasks().getFirst()).work())
        .isEqualTo(new BuildTask.Work.PolicyEvaluation(inferred.rowKind()));
    assertThat(s.state(inferred.existence())).contains(FactState.CONFIRMED);
    assertThat(model.subjects)
        .extracting(subject -> subject.fact().revision().fact())
        .containsExactly(inferred.rowKind());
    assertThat(reviewedFacts(s)).containsExactly(inferred.rowKind());
  }

  @Test
  void theModelEvaluatesARetirementProposalThatInlineEvaluationLeavesUndecided() {
    var s = scenario();
    var c = s.begin();
    var orders = declaredDataset(c, "shop.public.orders");
    var key =
        fact(
            c,
            c.address(orders.dataset(), "key"),
            "order_id",
            List.of(supported(ValidationMethod.DECLARED_ENFORCED, "order_id")),
            orders.existence().on());
    var written = s.judge(c);
    var keyUrn = written.urn(key.fact());

    c = s.begin();
    Judgement.proposeRetire(
        c, written.ref(key.fact()), supported(ValidationMethod.INFERENCE, "replaced"));
    var proposed = s.judge(c);
    assertThat(proposed.transitions().awaitingModel()).containsExactly(keyUrn);
    assertThat(s.state(keyUrn)).contains(FactState.CONFIRMED);

    var model = adopting();
    drain(s, model);

    assertThat(result(s, evaluations(s, proposed.buildTasks()).getFirst()))
        .isInstanceOf(BuildTask.Result.Evaluated.class);
    assertThat(s.state(keyUrn)).contains(FactState.RETIRED);
    var subject = (Subject.Retirement) model.subjects.getFirst();
    assertThat(subject.fact().revision().fact()).isEqualTo(keyUrn);
    assertThat(subject.proposalRecord().checks())
        .containsExactly(supported(ValidationMethod.INFERENCE, "replaced"));
  }

  @Test
  void anEvaluationUnderAnEarlierPolicyVersionNoLongerTakesEffect() {
    var s = scenario();
    var c = s.begin();
    var measure =
        c.add(new LogRecord.Element(new ElementIdentity.Unique(ElementKind.MEASURE, "ec-sales")));
    var existence =
        fact(
            c,
            c.address(measure, Vocabulary.EXISTENCE),
            "exists",
            List.of(Judgement.check(ValidationMethod.DOCUMENT, "exists", CheckResult.REFUTED)));
    var rule =
        fact(
            c,
            c.address(measure, "aggregation-rule"),
            "sum(amount)",
            List.of(supported(ValidationMethod.DECLARED_ENFORCED, "sum(amount)")),
            existence.on());
    var written = s.judge(c);
    var ruleUrn = written.urn(rule.fact());
    // Under auto the rule is adopted, pending the existence that awaits consent.
    assertThat(reviewedFacts(s)).containsExactly(written.urn(existence.fact()));

    // Tightening the policy withdraws that adoption before the re-evaluation runs: confirming the
    // premise evaluates the rule again under manual instead of confirming it.
    setPolicy(s, Preset.MANUAL);
    submit(s, answer(written.urn(existence.fact()), AnswerType.CONSENT));
    assertThat(s.state(ruleUrn)).contains(FactState.CANDIDATE);
    assertThat(reviewedFacts(s)).containsExactly(ruleUrn);
  }

  @Test
  void aRetirementProposalAwaitingConsentIsReEvaluatedUnderANewVersion() {
    var s = scenario();
    var c = s.begin();
    var orders = declaredDataset(c, "shop.public.orders");
    var written = s.judge(c);
    var existence = written.urn(orders.existence().fact());
    setPolicy(s, Preset.MANUAL);
    c = s.begin();
    Judgement.proposeRetire(
        c, written.ref(orders.existence().fact()), supported(ValidationMethod.INFERENCE, "gone"));
    s.judge(c);
    assertThat(reviewedFacts(s)).containsExactly(existence);

    setPolicy(s, Preset.FULL_AUTO);
    assertThat(reviewedFacts(s)).isEmpty();
    drain(s, adopting());

    assertThat(s.state(existence)).contains(FactState.RETIRED);
  }

  @Test
  void aRetirementProposalReplacedWhileTheModelRunsSupersedesItsEvaluation() {
    var s = scenario();
    var c = s.begin();
    var orders = declaredDataset(c, "shop.public.orders");
    var written = s.judge(c);
    var existence = written.urn(orders.existence().fact());
    c = s.begin();
    Judgement.proposeRetire(
        c, written.ref(orders.existence().fact()), supported(ValidationMethod.INFERENCE, "gone"));
    var first = evaluations(s, s.judge(c).buildTasks()).getFirst();
    var proposed = new AtomicBoolean();
    var model =
        new StandIn(
            subject -> {
              if (!proposed.getAndSet(true)) {
                var replacing = s.begin();
                Judgement.proposeRetire(
                    replacing,
                    Ref.urn(existence),
                    supported(ValidationMethod.INFERENCE, "renamed"));
                s.judge(replacing);
              }
              return new Decision.RequestConsent(
                  "only inference supports it", JsonNodeFactory.instance.objectNode());
            });

    drain(s, model);

    assertThat(result(s, first)).isInstanceOf(BuildTask.Result.Superseded.class);
    assertThat(model.subjects).hasSize(2);
    var second = (Subject.Retirement) model.subjects.get(1);
    assertThat(second.proposalRecord().checks())
        .containsExactly(supported(ValidationMethod.INFERENCE, "renamed"));
  }

  @Test
  void aRunWorksWithAPoolOfOneConnection() {
    try (var small = SemanticCatalogStore.open(TestStore.settingsWithPool(1))) {
      var s = new Scenario(small);
      var inferred = inferredRowKind(s, "shop.public.orders");
      small.runBuildTasks(
          s.domain,
          new BuildRun.Environment(adopting(), NO_CONSTRUCTION_SQL),
          BuildRun.drain(),
          task -> {});
      assertThat(s.state(inferred.rowKind())).contains(FactState.CONFIRMED);
    }
  }

  @Test
  void aRunThatLosesItsSemaphoreStops() throws Exception {
    var s = scenario();
    var started = new CountDownLatch(1);
    var run =
        CompletableFuture.supplyAsync(
            () ->
                store.runBuildTasks(
                    s.domain,
                    new BuildRun.Environment(adopting(), NO_CONSTRUCTION_SQL),
                    BuildRun.keepWaiting(
                        Duration.ofMillis(20),
                        () -> {
                          started.countDown();
                          return false;
                        }),
                    task -> {}));
    assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
    // The server ends the session that holds the semaphore, as on a timeout or a restart.
    try (var connection = TestStore.connect();
        var statement =
            connection.prepareStatement(
                "SELECT pg_terminate_backend(l.pid) FROM pg_locks l"
                    + " JOIN semantic_catalog.domains d"
                    + " ON ((l.classid::bigint << 32) | l.objid::bigint) = d.builder_lock"
                    + " WHERE l.locktype = 'advisory' AND d.domain = ?")) {
      statement.setString(1, s.domain);
      try (var rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
      }
    }

    assertThatThrownBy(() -> run.get(30, TimeUnit.SECONDS))
        .hasCauseInstanceOf(SemanticCatalogException.class)
        .hasMessageContaining("Lost the builder semaphore");
  }
}
