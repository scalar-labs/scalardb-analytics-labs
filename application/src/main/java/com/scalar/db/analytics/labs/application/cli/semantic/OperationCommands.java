/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.cli.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.application.ApplicationFailure;
import com.scalar.db.analytics.labs.application.semantic.SemanticCatalogService;
import com.scalar.db.analytics.labs.semantic.Answers;
import com.scalar.db.analytics.labs.semantic.model.BuildTask;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.ReviewPresentation;
import com.scalar.db.analytics.labs.semantic.model.UsageFeedback;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.AnswerType;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Preset;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.SessionPurpose;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Transition;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The Semantic Catalog operations other than running build tasks (see {@code
 * docs/semantic-catalog/public-api.md}, "Operations"). Each is made as the calling principal.
 */
final class OperationCommands {
  private OperationCommands() {}

  @Command(
      name = "present",
      description =
          "Registers that Facts were presented to a person for a judgement; answers and conflict"
              + " resolutions reference the presentation number it prints.")
  static final class PresentCommand extends SemanticCall {
    @Option(
        names = "--kind",
        required = true,
        paramLabel = "KIND",
        description = "confirmation, retirement, or conflict.")
    private @Nullable String kind;

    @Option(
        names = "--fact",
        required = true,
        paramLabel = "URN",
        description = "A Fact presented; repeat for each.")
    private List<String> facts = new ArrayList<>();

    @Option(
        names = "--to",
        paramLabel = "PERSON",
        description = "The person asked; the person running the CLI when absent.")
    private @Nullable String recipient;

    @Option(
        names = "--context",
        required = true,
        paramLabel = "TEXT",
        description = "What was shown, or a reference to the screen or dialogue.")
    private @Nullable String context;

    PresentCommand(Map<String, String> environment) {
      super(environment);
    }

    @Override
    Map<String, Object> call(SemanticCatalogService service) {
      var presentation =
          service.present(
              constant(ReviewPresentation.Kind.class, Objects.requireNonNull(kind), "--kind"),
              facts,
              recipient,
              Objects.requireNonNull(context));
      return envelope("review-presentation", "presentation", presentation);
    }
  }

  @Command(
      name = "answer",
      description =
          "Submits the answers of the person running the CLI in one commit, from a JSON file.")
  static final class AnswerCommand extends SemanticCall {
    private final InputStream standardInput;

    @Option(
        names = "--file",
        required = true,
        paramLabel = "FILE",
        description =
            "A JSON object {\"answers\": [...]}, each answer {\"fact\": URN, \"type\": consent |"
                + " rejection | correction | unknown, \"wording\": TEXT, \"presentation\": N},"
                + " with \"transition\": retire for a retirement proposal and \"contextSummary\""
                + " for a correction; - reads standard input.")
    private @Nullable Path file;

    AnswerCommand(InputStream standardInput, Map<String, String> environment) {
      super(environment);
      this.standardInput = standardInput;
    }

    @Override
    Map<String, Object> call(SemanticCatalogService service) {
      var input = readJson(Objects.requireNonNull(file), standardInput).get("answers");
      if (input == null || !input.isArray() || input.isEmpty()) {
        throw new ApplicationFailure(
            "INVALID_REQUEST", "The input needs a non-empty \"answers\" array.");
      }
      var answers = new ArrayList<Answers.Answer>();
      for (var answer : input) {
        answers.add(answer(answer));
      }
      var outcomes = service.answer(answers);
      return envelope(
          "answer-submission",
          "catalogSnapshot",
          outcomes.catalogSnapshot(),
          "outcomes",
          outcomes.outcomes(),
          "cascaded",
          outcomes.cascaded(),
          "buildTasks",
          outcomes.buildTasks());
    }

    private static Answers.Answer answer(JsonNode answer) {
      var where = "An answer";
      var transition = optionalText(answer, "transition", where);
      var presentation = answer.get("presentation");
      if (presentation != null && !presentation.isNull() && !presentation.canConvertToLong()) {
        throw new ApplicationFailure(
            "INVALID_REQUEST", "An answer's \"presentation\" is a presentation number.");
      }
      var fact = text(answer, "fact", where);
      var type = constant(AnswerType.class, text(answer, "type", where), "answer type");
      var wording = Objects.requireNonNullElse(optionalText(answer, "wording", where), "");
      var contextSummary = optionalText(answer, "contextSummary", where);
      return convert(
          answer,
          given ->
              new Answers.Answer(
                  fact,
                  transition == null
                      ? Transition.CONFIRM
                      : constant(Transition.class, transition, "transition"),
                  type,
                  wording,
                  contextSummary,
                  presentation == null || presentation.isNull() ? null : presentation.longValue()));
    }
  }

  @Command(
      name = "select",
      description = "Resolves a conflict: selects one Fact at a Fact Address over the others.")
  static final class SelectCommand extends SemanticCall {
    @Option(
        names = "--fact-address",
        required = true,
        paramLabel = "URN",
        description = "The conflicting Fact Address.")
    private @Nullable String factAddress;

    @Option(names = "--fact", required = true, paramLabel = "URN", description = "The Fact chosen.")
    private @Nullable String chosen;

    @Option(
        names = "--passed-over",
        required = true,
        paramLabel = "URN",
        description = "A Fact passed over; repeat for each.")
    private List<String> passedOver = new ArrayList<>();

    @Option(
        names = "--wording",
        required = true,
        paramLabel = "TEXT",
        description = "What the person said.")
    private @Nullable String wording;

    @Option(names = "--presentation", paramLabel = "N", description = "The presentation answered.")
    private @Nullable Long presentation;

    SelectCommand(Map<String, String> environment) {
      super(environment);
    }

    @Override
    Map<String, Object> call(SemanticCatalogService service) {
      var snapshot =
          service.select(
              Objects.requireNonNull(factAddress),
              Objects.requireNonNull(chosen),
              passedOver,
              Objects.requireNonNull(wording),
              presentation);
      return envelope("conflict-resolution", "catalogSnapshot", snapshot);
    }
  }

  @Command(
      name = "feedback",
      description =
          "Registers usage feedback a session found worth keeping, such as missing knowledge met"
              + " while composing a query.")
  static final class FeedbackCommand extends SemanticCall {
    @Option(
        names = "--content",
        required = true,
        paramLabel = "TEXT",
        description = "What the session found.")
    private @Nullable String content;

    @Option(
        names = "--purpose",
        defaultValue = "composing-a-query",
        paramLabel = "PURPOSE",
        description =
            "The session's purpose: composing-a-query (default) or constructing-the-catalog.")
    private String purpose = "composing-a-query";

    @Option(
        names = "--context",
        required = true,
        paramLabel = "TEXT",
        description = "A summary of the session's context, such as the question asked.")
    private @Nullable String context;

    @Option(
        names = "--related-fact",
        paramLabel = "URN",
        description = "A Fact the feedback is about; repeat for each.")
    private List<String> relatedFacts = new ArrayList<>();

    FeedbackCommand(Map<String, String> environment) {
      super(environment);
    }

    @Override
    Map<String, Object> call(SemanticCatalogService service) {
      var sessionPurpose = constant(SessionPurpose.class, purpose, "--purpose");
      var feedback =
          convert(
              relatedFacts,
              facts ->
                  new UsageFeedback(
                      Objects.requireNonNull(content),
                      sessionPurpose,
                      Objects.requireNonNull(context),
                      facts.stream().map(Ref::urn).toList()));
      var registration = service.feedback(feedback);
      return envelope(
          "usage-feedback",
          "catalogSnapshot",
          registration.catalogSnapshot(),
          "inputResource",
          registration.inputResource(),
          "inputObservation",
          registration.inputObservation(),
          "buildTasks",
          registration.buildTasks());
    }
  }

  @Command(name = "policy", description = "Checks or sets the domain's user policy version.")
  static final class PolicyCommand implements Runnable {
    @Override
    public void run() {
      throw new CommandLine.ParameterException(
          new CommandLine(this), "A policy command is required.");
    }
  }

  /** The proposed setting of a user policy version. */
  static final class PolicySetting {
    @Parameters(index = "0", paramLabel = "PRESET", description = "full-auto, auto, or manual.")
    @Nullable String preset;

    @Option(
        names = "--count-declared-unenforced",
        description = "Count declarations the source does not enforce as evidence.")
    boolean countsDeclaredUnenforced;

    Preset preset() {
      return SemanticCall.constant(Preset.class, Objects.requireNonNull(preset), "PRESET");
    }
  }

  @Command(
      name = "check",
      description =
          "Shows the current version and what a proposed setting would adopt inline or send to the"
              + " policy evaluation model at the latest point.")
  static final class PolicyCheckCommand extends SemanticCall {
    @CommandLine.Mixin private PolicySetting setting = new PolicySetting();

    PolicyCheckCommand(Map<String, String> environment) {
      super(environment);
    }

    @Override
    Map<String, Object> call(SemanticCatalogService service) {
      var check = service.checkPolicy(setting.preset(), setting.countsDeclaredUnenforced);
      return envelope("policy-check", "check", check);
    }
  }

  @Command(
      name = "set",
      description =
          "Sets a new user policy version and creates the re-evaluation build task; check it first.")
  static final class PolicySetCommand extends SemanticCall {
    @CommandLine.Mixin private PolicySetting setting = new PolicySetting();

    @Option(
        names = "--reason",
        required = true,
        paramLabel = "TEXT",
        description = "The semantics owner's reason.")
    private @Nullable String reason;

    PolicySetCommand(Map<String, String> environment) {
      super(environment);
    }

    @Override
    Map<String, Object> call(SemanticCatalogService service) {
      var set =
          service.setPolicy(
              setting.preset(), setting.countsDeclaredUnenforced, Objects.requireNonNull(reason));
      return envelope(
          "policy-version", "logRecord", set.logRecord(), "reEvaluation", set.reEvaluation());
    }
  }

  @Command(
      name = "tasks",
      description = "Lists the domain's build tasks with their state, triggers, and results.")
  static final class TasksCommand extends SemanticCall {
    @Option(names = "--task", paramLabel = "N", description = "Only this task.")
    private @Nullable Long task;

    @Option(
        names = "--state",
        paramLabel = "STATE",
        description = "Only tasks in this state: created, running, succeeded, or failed.")
    private @Nullable String state;

    TasksCommand(Map<String, String> environment) {
      super(environment);
    }

    @Override
    Map<String, Object> call(SemanticCatalogService service) {
      var wanted = state == null ? null : constant(BuildTask.State.class, state, "--state");
      var tasks =
          service.tasks(task).stream()
              .filter(each -> wanted == null || each.status().state() == wanted)
              .toList();
      return envelope("build-tasks", "tasks", tasks);
    }
  }

  @Command(
      name = "resume",
      description = "Returns a failed build task to created, so the next build runs it.")
  static final class ResumeCommand extends SemanticCall {
    @Parameters(index = "0", paramLabel = "N", description = "The failed task's number.")
    private long task;

    ResumeCommand(Map<String, String> environment) {
      super(environment);
    }

    @Override
    Map<String, Object> call(SemanticCatalogService service) {
      return envelope("build-task", "task", service.resume(task));
    }
  }
}
