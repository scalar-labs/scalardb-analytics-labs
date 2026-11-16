/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import static com.scalar.db.analytics.labs.semantic.Judgement.declaredDataset;
import static com.scalar.db.analytics.labs.semantic.Judgement.fact;
import static com.scalar.db.analytics.labs.semantic.Judgement.supported;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.scalar.db.analytics.labs.semantic.llm.LanguageModel;
import com.scalar.db.analytics.labs.semantic.llm.LanguageModelSettings;
import com.scalar.db.analytics.labs.semantic.model.BuildTask;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.Review;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ConfirmationPath;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ValidationMethod;
import com.scalar.db.analytics.labs.semantic.policy.LanguageModelPolicyEvaluation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The policy evaluation model reached through the {@code command} language-model adapter: what a
 * build task records for each kind of answer the configured command gives, and for a command that
 * fails or does not answer.
 */
class LanguageModelPolicyEvaluationTest extends StoreScenarioTest {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String MODEL = "fixture-model-1";

  @TempDir Path directory;

  /**
   * A candidate row kind that only inference supports, which inline evaluation leaves undecided.
   */
  private record Undecided(long task, String rowKind) {}

  private static Undecided undecidedRowKind(Scenario s) {
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
    return new Undecided(written.buildTasks().getFirst(), written.urn(rowKind.fact()));
  }

  /**
   * A command that keeps the prompt it was given and the listing of the directory it ran in, then
   * answers with the given text.
   */
  private LanguageModelSettings answering(String answer) throws IOException {
    var file = directory.resolve("answer.txt");
    Files.writeString(file, answer);
    return command(
        "cat > '%s'; ls -A > '%s'; pwd > '%s'; cat '%s'"
            .formatted(
                directory.resolve("prompt.txt"),
                directory.resolve("listing.txt"),
                directory.resolve("cwd.txt"),
                file));
  }

  private static LanguageModelSettings command(String command) {
    return new LanguageModelSettings.Command(command, MODEL, Duration.ofSeconds(30));
  }

  private static LanguageModelPolicyEvaluation model(LanguageModelSettings settings) {
    return new LanguageModelPolicyEvaluation(LanguageModel.open(settings));
  }

  private static void drain(Scenario s, LanguageModelSettings settings) {
    store.runBuildTasks(
        s.domain,
        ScriptedModel.silent(model(settings), BuildTasksTest.NO_CONSTRUCTION_SQL),
        BuildRun.drain(),
        task -> {});
  }

  private static BuildTask.Status status(Scenario s, long task) {
    return store.buildTask(s.domain, task).orElseThrow().status();
  }

  @Test
  void anAdoptingAnswerConfirmsByMachineJudgementUnderTheModelAndPromptVersion() throws Exception {
    var s = scenario();
    var undecided = undecidedRowKind(s);

    var settings = answering("{\"decision\": \"adopt\", \"reason\": \"the name and kind agree\"}");
    drain(s, settings);

    var evaluated =
        (BuildTask.Result.Evaluated)
            ((BuildTask.Status.Succeeded) status(s, undecided.task())).result();
    assertThat(s.state(undecided.rowKind())).contains(FactState.CONFIRMED);
    assertThat(s.revision(undecided.rowKind()).confirmationPath())
        .isEqualTo(ConfirmationPath.MACHINE_JUDGEMENT);
    // The version names the adapter, the concrete model, and the instructions by name and hash.
    var version = evaluated.modelVersion();
    assertThat(version.path("model").path("adapter").asText()).isEqualTo("command");
    assertThat(version.path("model").path("model").asText()).isEqualTo(MODEL);
    assertThat(version.path("prompt").path("name").asText()).isEqualTo("policy-evaluation");
    assertThat(version.path("prompt").path("sha256").asText()).hasSize(64);
    assertThat(version).isEqualTo(model(settings).version());

    // The command was given the instructions and then the subject, and nothing else: the hashed
    // instructions are the whole fixed part of what it read.
    var prompt = Files.readString(directory.resolve("prompt.txt"));
    var subjectStart = prompt.indexOf("\n\n{");
    var instructions = prompt.substring(0, subjectStart);
    var digest = MessageDigest.getInstance("SHA-256");
    assertThat(HexFormat.of().formatHex(digest.digest(instructions.getBytes(UTF_8))))
        .isEqualTo(version.path("prompt").path("sha256").asText());
    var subject = MAPPER.readTree(prompt.substring(subjectStart + 2));
    assertThat(subject.path("kind").asText()).isEqualTo("confirmation");
    assertThat(subject.path("fact").path("revision").path("fact").asText())
        .isEqualTo(undecided.rowKind());
    // It ran in an empty directory of its own.
    assertThat(Files.readString(directory.resolve("listing.txt"))).isEmpty();
    assertThat(Files.readString(directory.resolve("cwd.txt")).strip())
        .isNotEqualTo(System.getProperty("user.dir"));
  }

  @Test
  void aConsentRequestReachesTheReviewItemWithItsReviewMaterial() throws Exception {
    var s = scenario();
    var undecided = undecidedRowKind(s);
    var material =
        """
        {"question": "Is each orders row one order event?", "uncertain": "only the name says so",
         "evidenceFor": ["the table is named orders"], "evidenceAgainst": [],
         "suggestedAnswer": "consent", "suggestionReasons": ["the name is conventional"]}""";

    // A model may wrap its JSON in a Markdown code fence.
    drain(
        s,
        answering(
            "```json\n{\"decision\": \"request-consent\", \"reason\": \"only the name suggests"
                + " it\", \"reviewMaterial\": "
                + material
                + "}\n```"));

    assertThat(s.state(undecided.rowKind())).contains(FactState.CANDIDATE);
    var item =
        s.latest(snapshot -> snapshot.reviewList(Review.Query.ALL)).stream()
            .flatMap(group -> group.items().stream())
            .map(Review.Candidate.class::cast)
            .filter(candidate -> candidate.fact().revision().fact().equals(undecided.rowKind()))
            .findFirst()
            .orElseThrow();
    assertThat(item.evaluation().record().reason()).isEqualTo("only the name suggests it");
    var evaluator = (LogRecord.Evaluator.Model) item.evaluation().record().evaluator();
    assertThat(evaluator.reviewMaterial()).isEqualTo(MAPPER.readTree(material));
  }

  @Test
  void aFailingCommandFailsTheTaskAndAResumeAfterTheFixRunsItAgain() throws Exception {
    var s = scenario();
    var undecided = undecidedRowKind(s);

    drain(s, command("echo 'not signed in' >&2; exit 3"));

    assertThat(status(s, undecided.task()))
        .isEqualTo(new BuildTask.Status.Failed("command: exit 3: not signed in"));
    assertThat(s.state(undecided.rowKind())).contains(FactState.CANDIDATE);

    store.resumeBuildTask(s.domain, undecided.task());
    drain(s, answering("{\"decision\": \"adopt\", \"reason\": \"the signals agree\"}"));
    assertThat(status(s, undecided.task())).isInstanceOf(BuildTask.Status.Succeeded.class);
    assertThat(s.state(undecided.rowKind())).contains(FactState.CONFIRMED);
  }

  @Test
  void anAnswerThatIsNotADecisionFailsTheTaskRatherThanAskingForConsent() throws Exception {
    var s = scenario();
    var undecided = undecidedRowKind(s);

    drain(s, answering("{\"decision\": \"request-consent\", \"reason\": \"unsure\"}"));

    assertThat(status(s, undecided.task()))
        .isEqualTo(
            new BuildTask.Status.Failed(
                "The policy evaluation model's answer is not a decision: a consent request"
                    + " carries no review material"));
    assertThat(s.state(undecided.rowKind())).contains(FactState.CANDIDATE);

    // Material with a part missing is not shown to the deciding party either.
    store.resumeBuildTask(s.domain, undecided.task());
    drain(
        s,
        answering(
            "{\"decision\": \"request-consent\", \"reason\": \"unsure\", \"reviewMaterial\":"
                + " {\"question\": \"Is each row an order?\"}}"));
    assertThat(status(s, undecided.task()))
        .isEqualTo(
            new BuildTask.Status.Failed(
                "The policy evaluation model's answer is not a decision: its review material has"
                    + " no uncertain"));
    assertThat(PolicyEvaluationTest.reviewedFacts(s)).isEmpty();
  }

  @Test
  void aCommandThatDoesNotAnswerInTimeFailsTheTask() {
    var s = scenario();
    var undecided = undecidedRowKind(s);

    drain(s, new LanguageModelSettings.Command("sleep 30", MODEL, Duration.ofSeconds(1)));

    assertThat(status(s, undecided.task()))
        .isEqualTo(new BuildTask.Status.Failed("command: no answer within 1s"));
  }
}
