/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.cli.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.application.ApplicationFailure;
import com.scalar.db.analytics.labs.application.semantic.SemanticSession;
import com.scalar.db.analytics.labs.application.workspace.Workspace;
import com.scalar.db.analytics.labs.semantic.BuildTask;
import com.scalar.db.analytics.labs.semantic.Outputs;
import com.scalar.db.analytics.labs.semantic.SemanticCatalog.AnswerInput;
import com.scalar.db.analytics.labs.semantic.SnapshotReads;
import com.scalar.db.analytics.labs.semantic.SnapshotReads.Request;
import com.scalar.db.analytics.labs.semantic.model.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Json;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.AnswerType;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ObservationKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.PresentationKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Preset;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Target;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Shared conversions for the semantic commands. */
final class SemanticCommands {
  private SemanticCommands() {}

  static void register(
      picocli.CommandLine command, InputStream standardInput, Map<String, String> environment) {
    command.addSubcommand("build", new BuildCommand(standardInput, environment));
    command.addSubcommand("status", new StatusCommand(standardInput, environment));
    command.addSubcommand("tasks", new TasksCommand(standardInput, environment));
    command.addSubcommand("resume", new ResumeCommand(standardInput, environment));
    command.addSubcommand("review", new ReviewCommand(standardInput, environment));
    command.addSubcommand("present", new PresentCommand(standardInput, environment));
    command.addSubcommand("answer", new AnswerCommand(standardInput, environment));
    command.addSubcommand("select", new SelectCommand(standardInput, environment));
    command.addSubcommand("elements", new ElementsCommand(standardInput, environment));
    command.addSubcommand("knowledge", new KnowledgeCommand(standardInput, environment));
    command.addSubcommand("overview", new OverviewCommand(standardInput, environment));
    command.addSubcommand("fact", new FactCommand(standardInput, environment));
    command.addSubcommand("policy", new PolicyCommand(standardInput, environment));
    command.addSubcommand("observe", new ObserveCommand(standardInput, environment));
    command.addSubcommand("question", new QuestionCommand(standardInput, environment));
    command.addSubcommand("verify", new VerifyCommand(standardInput, environment));
  }

  static <T> T wire(Class<T> type, @Nullable String name, String option) {
    if (name == null) {
      throw new ApplicationFailure("INVALID_REQUEST", option + " is required.");
    }
    try {
      return Json.convert(Json.MAPPER.getNodeFactory().textNode(name), type);
    } catch (IllegalArgumentException e) {
      throw new ApplicationFailure("INVALID_REQUEST", "Unknown value for " + option + ": " + name);
    }
  }

  static @Nullable ElementKind kind(@Nullable String name) {
    return name == null ? null : wire(ElementKind.class, name, "--kind");
  }

  /** A short account of one build task run. */
  record RunSummary(
      String task,
      @Nullable String generator,
      BuildTask.State state,
      int accepted,
      int notAccepted,
      List<String> missingInputs,
      @Nullable String failureReason) {}

  static List<RunSummary> summarize(SemanticSession session, List<Outputs.TaskRun> runs) {
    var summaries = new ArrayList<RunSummary>();
    for (var run : runs) {
      var task = session.catalog().reads().tasks(run.task(), null).getFirst();
      var result = run.result();
      var missing = new ArrayList<String>();
      if (result != null) {
        result.path("missingInputs").forEach(item -> missing.add(item.asText()));
      }
      summaries.add(
          new RunSummary(
              run.task(),
              task.generator(),
              run.state(),
              result == null ? 0 : result.path("accepted").size(),
              result == null ? 0 : result.path("notAccepted").size(),
              missing,
              run.failureReason()));
    }
    return summaries;
  }
}

@Command(
    name = "build",
    description = "Refetches table metadata, registers it, and runs build tasks.")
final class BuildCommand extends SemanticCommand.Base {
  @Option(names = "--source", paramLabel = "NAME", description = "Refetch only this data source.")
  private @Nullable String source;

  @Option(names = "--principal", paramLabel = "PRINCIPAL")
  private @Nullable String principal;

  @Option(names = "--max-tasks", paramLabel = "N", defaultValue = "50")
  private int maxTasks;

  @Option(names = "--no-refetch", description = "Only run the build tasks already created.")
  private boolean noRefetch;

  @Option(
      names = "--watch",
      description =
          "Keep running build tasks as answers create them, printing one event line per finished"
              + " task, until none is created for --idle-seconds.")
  private boolean watch;

  @Option(names = "--idle-seconds", paramLabel = "SECONDS", defaultValue = "600")
  private long idleSeconds;

  @Option(names = "--poll-seconds", paramLabel = "SECONDS", defaultValue = "2")
  private long pollSeconds;

  BuildCommand(InputStream standardInput, Map<String, String> environment) {
    super(standardInput, environment);
  }

  @Override
  String schema() {
    return "build";
  }

  record Registered(@Nullable String resource, String recorded) {}

  /**
   * @param remainingTasks created tasks not run yet, for a later {@code --no-refetch} build
   * @param failedTasks failed tasks, which only {@code semantic resume} runs again
   */
  record Output(
      List<Registered> registered,
      List<SemanticCommands.RunSummary> runs,
      int remainingTasks,
      List<String> failedTasks,
      Outputs.SnapshotSummary summary) {}

  @Override
  Object run(Workspace workspace) {
    var who = principal(principal);
    return session(
        workspace,
        session -> {
          var registered = new ArrayList<Registered>();
          if (!noRefetch) {
            for (var observation : session.refetch(who, Optional.ofNullable(source))) {
              registered.add(new Registered(observation.inputResource(), observation.recorded()));
            }
          }
          List<Outputs.TaskRun> runs;
          if (watch) {
            var out = Objects.requireNonNull(commandSpec).commandLine().getOut();
            runs =
                session.watch(
                    java.time.Duration.ofSeconds(idleSeconds),
                    java.time.Duration.ofSeconds(pollSeconds),
                    run -> {
                      var event =
                          Json.object()
                              .put("schema", "scalardb-analytics/semantic-build-event")
                              .put("schemaVersion", SemanticCommand.SCHEMA_VERSION);
                      event.setAll(
                          (com.fasterxml.jackson.databind.node.ObjectNode)
                              Json.tree(
                                  SemanticCommands.summarize(session, List.of(run)).getFirst()));
                      event.put(
                          "awaitingReview", session.catalog().reads().summary().awaitingReview());
                      out.println(Json.write(event));
                    });
          } else {
            runs = session.runCreated(maxTasks);
          }
          return new Output(
              registered,
              SemanticCommands.summarize(session, runs),
              session.remainingTasks().size(),
              session.catalog().reads().tasks(null, BuildTask.State.FAILED).stream()
                  .map(BuildTask::id)
                  .toList(),
              session.catalog().reads().summary());
        });
  }
}

@Command(
    name = "status",
    description = "Summarizes the latest Catalog Snapshot and unfinished build tasks.")
final class StatusCommand extends SemanticCommand.Base {
  StatusCommand(InputStream standardInput, Map<String, String> environment) {
    super(standardInput, environment);
  }

  @Override
  String schema() {
    return "status";
  }

  record Output(Outputs.SnapshotSummary summary, List<BuildTask> unfinishedTasks) {}

  @Override
  Object run(Workspace workspace) {
    return session(
        workspace,
        session -> {
          var reads = session.catalog().reads();
          var unfinished = new ArrayList<BuildTask>();
          unfinished.addAll(reads.tasks(null, BuildTask.State.CREATED));
          unfinished.addAll(reads.tasks(null, BuildTask.State.FAILED));
          unfinished.addAll(reads.tasks(null, BuildTask.State.WAITING));
          return new Output(reads.summary(), unfinished);
        });
  }
}

@Command(name = "tasks", description = "Lists build tasks with their state, triggers, and results.")
final class TasksCommand extends SemanticCommand.Base {
  @Option(names = "--task", paramLabel = "ID")
  private @Nullable String task;

  @Option(
      names = "--state",
      paramLabel = "STATE",
      description = "created, running, waiting, succeeded, or failed.")
  private @Nullable String state;

  TasksCommand(InputStream standardInput, Map<String, String> environment) {
    super(standardInput, environment);
  }

  @Override
  String schema() {
    return "tasks";
  }

  record Output(List<BuildTask> tasks) {}

  @Override
  Object run(Workspace workspace) {
    var filter =
        state == null ? null : SemanticCommands.wire(BuildTask.State.class, state, "--state");
    return session(workspace, session -> new Output(session.catalog().reads().tasks(task, filter)));
  }
}

@Command(name = "resume", description = "Resumes a failed build task.")
final class ResumeCommand extends SemanticCommand.Base {
  @Option(names = "--task", required = true, paramLabel = "ID")
  private @Nullable String task;

  @Option(
      names = "--max-tasks",
      paramLabel = "N",
      defaultValue = "50",
      description = "Then run up to N created build tasks.")
  private int maxTasks;

  @Option(
      names = "--running",
      description = "Also resume a task left running by a build that stopped during the run.")
  private boolean running;

  ResumeCommand(InputStream standardInput, Map<String, String> environment) {
    super(standardInput, environment);
  }

  @Override
  String schema() {
    return "resume";
  }

  record Output(Outputs.TaskRun resumed, List<SemanticCommands.RunSummary> runs) {}

  @Override
  Object run(Workspace workspace) {
    return session(
        workspace,
        session -> {
          var resumed = session.catalog().resumeTask(Objects.requireNonNull(task), running);
          return new Output(
              resumed, SemanticCommands.summarize(session, session.runCreated(maxTasks)));
        });
  }
}

@Command(
    name = "review",
    description = "Lists the candidates, retirements, and conflicts awaiting a judgement.")
final class ReviewCommand extends SemanticCommand.Base {
  @Option(names = "--snapshot", paramLabel = "URN")
  private @Nullable String snapshot;

  @Option(
      names = "--kind",
      paramLabel = "KIND",
      description = "confirmation, retirement, or conflict.")
  private @Nullable String kind;

  @Option(
      names = "--target",
      paramLabel = "URN",
      description = "An element, Fact, or Fact Address URN.")
  private @Nullable String target;

  @Option(names = "--question", paramLabel = "URN")
  private @Nullable String question;

  @Option(
      names = "--unpresented",
      description = "Only the items no review presentation has targeted yet.")
  private boolean unpresented;

  ReviewCommand(InputStream standardInput, Map<String, String> environment) {
    super(standardInput, environment);
  }

  @Override
  String schema() {
    return "review-queue";
  }

  @Override
  Object run(Workspace workspace) {
    var presentationKind =
        kind == null ? null : SemanticCommands.wire(PresentationKind.class, kind, "--kind");
    return session(
        workspace,
        session ->
            session
                .catalog()
                .reads()
                .reviewQueue(snapshot, presentationKind, target, question, unpresented));
  }
}

@Command(
    name = "present",
    description = "Records that candidates were presented to a deciding party.")
final class PresentCommand extends SemanticCommand.Base {
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
      description = "A Fact presented; repeatable.")
  private List<String> facts = new ArrayList<>();

  @Option(
      names = "--to",
      paramLabel = "PRINCIPAL",
      description = "Who was asked. Defaults to the operating system user.")
  private @Nullable String presentedTo;

  @Option(
      names = "--context",
      required = true,
      paramLabel = "TEXT",
      description = "A reference to the dialogue or screen presented.")
  private @Nullable String context;

  @Option(names = "--question", paramLabel = "URN")
  private @Nullable String question;

  PresentCommand(InputStream standardInput, Map<String, String> environment) {
    super(standardInput, environment);
  }

  @Override
  String schema() {
    return "presentation";
  }

  @Override
  Object run(Workspace workspace) {
    var presentationKind = SemanticCommands.wire(PresentationKind.class, kind, "--kind");
    var to = principal(presentedTo);
    return session(
        workspace,
        session ->
            session
                .catalog()
                .registerPresentation(
                    presentationKind, facts, to, null, Objects.requireNonNull(context), question));
  }
}

@Command(name = "answer", description = "Submits a deciding party's answers in one commit.")
final class AnswerCommand extends SemanticCommand.Base {
  @Option(
      names = "--principal",
      paramLabel = "PRINCIPAL",
      description = "The deciding party. Defaults to the operating system user.")
  private @Nullable String principal;

  @Option(
      names = "--file",
      paramLabel = "PATH",
      description =
          "JSON: {\"answers\": [{\"fact\", \"type\", \"wording\", \"target\", \"presentation\"}]}.")
  private @Nullable Path file;

  @Option(names = "--stdin", description = "Read the JSON from standard input.")
  private boolean stdin;

  @Option(
      names = "--call-id",
      paramLabel = "ID",
      description = "Makes a resend with the same input write nothing.")
  private @Nullable String callId;

  @Option(names = "--run-tasks", description = "Then run the build tasks the answers created.")
  private boolean runTasks;

  @Option(names = "--max-tasks", paramLabel = "N", defaultValue = "50")
  private int maxTasks;

  AnswerCommand(InputStream standardInput, Map<String, String> environment) {
    super(standardInput, environment);
  }

  @Override
  String schema() {
    return "answers";
  }

  record Output(
      Outputs.AnswersSubmitted submitted,
      List<SemanticCommands.RunSummary> runs,
      Outputs.SnapshotSummary summary) {}

  @Override
  Object run(Workspace workspace) throws IOException {
    var who = principal(principal);
    var input = readJson(file, stdin);
    var items = input.isArray() ? input : input.path("answers");
    var answers = new ArrayList<AnswerInput>();
    for (var item : items) {
      answers.add(
          new AnswerInput(
              requireText(item, "fact"),
              SemanticCommands.wire(
                  Target.class, item.path("target").asText("confirmation"), "target"),
              SemanticCommands.wire(AnswerType.class, item.path("type").asText(null), "type"),
              item.path("wording").asText(""),
              item.path("presentation").isTextual() ? item.path("presentation").asText() : null));
    }
    return session(
        workspace,
        session -> {
          var submitted =
              session.catalog().submitAnswers(who, callId, session.authorize(who), answers);
          var runs = runTasks ? session.runCreated(maxTasks) : List.<Outputs.TaskRun>of();
          return new Output(
              submitted,
              SemanticCommands.summarize(session, runs),
              session.catalog().reads().summary());
        });
  }

  private static String requireText(JsonNode node, String field) {
    if (!node.path(field).isTextual()) {
      throw new ApplicationFailure("INVALID_REQUEST", "Each answer needs \"" + field + "\".");
    }
    return node.path(field).asText();
  }
}

@Command(name = "select", description = "Selects one Fact in a conflict.")
final class SelectCommand extends SemanticCommand.Base {
  @Option(names = "--principal", paramLabel = "PRINCIPAL")
  private @Nullable String principal;

  @Option(names = "--address", required = true, paramLabel = "URN")
  private @Nullable String address;

  @Option(names = "--fact", required = true, paramLabel = "URN", description = "The Fact selected.")
  private @Nullable String fact;

  @Option(
      names = "--not",
      required = true,
      paramLabel = "URN",
      description = "A Fact not selected; repeatable.")
  private List<String> notSelected = new ArrayList<>();

  @Option(names = "--wording", required = true, paramLabel = "TEXT")
  private @Nullable String wording;

  @Option(names = "--presentation", paramLabel = "ID")
  private @Nullable String presentation;

  @Option(names = "--call-id", paramLabel = "ID")
  private @Nullable String callId;

  SelectCommand(InputStream standardInput, Map<String, String> environment) {
    super(standardInput, environment);
  }

  @Override
  String schema() {
    return "selection";
  }

  @Override
  Object run(Workspace workspace) {
    var who = principal(principal);
    return session(
        workspace,
        session ->
            session
                .catalog()
                .selectInConflict(
                    who,
                    callId,
                    session.authorize(who),
                    Objects.requireNonNull(address),
                    Objects.requireNonNull(fact),
                    notSelected,
                    Objects.requireNonNull(wording),
                    presentation));
  }
}

@Command(name = "elements", description = "Finds elements whose existence is selected at a point.")
final class ElementsCommand extends SemanticCommand.Base {
  @Option(names = "--snapshot", paramLabel = "URN")
  private @Nullable String snapshot;

  @Option(
      names = "--kind",
      paramLabel = "KIND",
      description =
          "dataset, field, concept, measure, dimension, business-term, join, identifier-correspondence, or concept-relationship.")
  private @Nullable String kind;

  @Option(names = "--parent", paramLabel = "URN")
  private @Nullable String parent;

  @Option(
      names = "--term",
      paramLabel = "TEXT",
      description = "The written form of a business term; returns what the term refers to.")
  private @Nullable String term;

  ElementsCommand(InputStream standardInput, Map<String, String> environment) {
    super(standardInput, environment);
  }

  @Override
  String schema() {
    return "elements";
  }

  @Override
  Object run(Workspace workspace) {
    var elementKind = SemanticCommands.kind(kind);
    return session(
        workspace,
        session -> session.catalog().reads().findElements(snapshot, elementKind, parent, term));
  }
}

@Command(
    name = "knowledge",
    description =
        "Requests knowledge: the status and judgement material for element and predicate pairs.")
final class KnowledgeCommand extends SemanticCommand.Base {
  @Option(
      names = "--snapshot",
      paramLabel = "URN",
      description = "Pin the point; defaults to the latest.")
  private @Nullable String snapshot;

  @Option(
      names = "--request",
      paramLabel = "URN=PREDICATE",
      description =
          "An element and predicate; repeatable. The predicate * requests every predicate with knowledge.")
  private List<String> requests = new ArrayList<>();

  @Option(
      names = "--entity",
      paramLabel = "URN",
      description = "An entity the question mentions; repeatable.")
  private List<String> entities = new ArrayList<>();

  @Option(
      names = "--file",
      paramLabel = "PATH",
      description =
          "JSON: {\"requests\": [{\"element\" | \"edgeKind\", \"from\", \"to\", \"predicate\"}], \"entities\": []}.")
  private @Nullable Path file;

  @Option(names = "--stdin")
  private boolean stdin;

  KnowledgeCommand(InputStream standardInput, Map<String, String> environment) {
    super(standardInput, environment);
  }

  @Override
  String schema() {
    return "knowledge";
  }

  @Override
  Object run(Workspace workspace) throws IOException {
    var list = new ArrayList<Request>();
    var mentioned = new ArrayList<>(entities);
    for (var request : requests) {
      var separator = request.lastIndexOf('=');
      if (separator <= 0) {
        throw new ApplicationFailure("INVALID_REQUEST", "A --request is URN=PREDICATE.");
      }
      list.add(
          new Request(
              request.substring(0, separator), null, null, null, request.substring(separator + 1)));
    }
    if (file != null || stdin) {
      var input = readJson(file, stdin);
      for (var item : input.path("requests")) {
        list.add(
            new Request(
                item.path("element").isTextual() ? item.path("element").asText() : null,
                item.path("edgeKind").isTextual()
                    ? SemanticCommands.kind(item.path("edgeKind").asText())
                    : null,
                item.path("from").isTextual() ? item.path("from").asText() : null,
                item.path("to").isTextual() ? item.path("to").asText() : null,
                item.path("predicate").asText(SnapshotReads.ALL_PREDICATES)));
      }
      input.path("entities").forEach(entity -> mentioned.add(entity.asText()));
    }
    if (list.isEmpty()) {
      throw new ApplicationFailure(
          "INVALID_REQUEST", "Give at least one --request, or --file or --stdin.");
    }
    return session(
        workspace,
        session ->
            session
                .catalog()
                .reads()
                .requestKnowledge(snapshot, list, mentioned.isEmpty() ? null : mentioned));
  }
}

@Command(
    name = "overview",
    description = "Lists every element with its selected knowledge and open statuses, compactly.")
final class OverviewCommand extends SemanticCommand.Base {
  @Option(
      names = "--snapshot",
      paramLabel = "URN",
      description = "Pin the point; defaults to the latest.")
  private @Nullable String snapshot;

  @Option(names = "--kind", paramLabel = "KIND")
  private @Nullable String kind;

  OverviewCommand(InputStream standardInput, Map<String, String> environment) {
    super(standardInput, environment);
  }

  @Override
  String schema() {
    return "overview";
  }

  /** One predicate's knowledge: the selected value and its revision, or the status. */
  record Knowledge(
      @Nullable JsonNode value,
      @Nullable String factRevision,
      @Nullable String scope,
      @Nullable String status,
      @Nullable List<JsonNode> candidates) {}

  record Entry(
      String element,
      ElementKind kind,
      String label,
      @Nullable String parent,
      @Nullable String from,
      @Nullable String to,
      @Nullable JsonNode identifying,
      Map<String, List<Knowledge>> knowledge) {}

  record Output(String snapshot, List<Entry> elements) {}

  @Override
  Object run(Workspace workspace) {
    var elementKind = SemanticCommands.kind(kind);
    return session(
        workspace,
        session -> {
          var reads = session.catalog().reads();
          var found = reads.findElements(snapshot, elementKind, null, null);
          var entries = new ArrayList<Entry>();
          for (var element : found.elements()) {
            var response =
                reads.requestKnowledge(
                    found.snapshot(),
                    List.of(
                        new Request(
                            element.element(), null, null, null, SnapshotReads.ALL_PREDICATES)),
                    null);
            var knowledge = new LinkedHashMap<String, List<Knowledge>>();
            for (var request : response.responses()) {
              if (request.predicate().equals("exists")) {
                continue;
              }
              var list = new ArrayList<Knowledge>();
              for (var address : request.addresses()) {
                var scope = address.scope().unrestricted() ? null : address.scope().description();
                var selected = address.selected();
                if (selected != null && address.status() == null) {
                  list.add(
                      new Knowledge(selected.value(), selected.factRevision(), scope, null, null));
                } else {
                  var candidates = new ArrayList<JsonNode>();
                  for (var material : address.material()) {
                    candidates.add(
                        Json.object()
                            .put("fact", material.fact())
                            .put("state", Json.tree(material.state()).asText())
                            .set("value", material.value()));
                  }
                  list.add(
                      new Knowledge(
                          selected == null ? null : selected.value(),
                          selected == null ? null : selected.factRevision(),
                          scope,
                          address.status(),
                          candidates));
                }
              }
              knowledge.put(request.predicate(), list);
            }
            entries.add(
                new Entry(
                    element.element(),
                    element.kind(),
                    element.label(),
                    element.parent(),
                    element.from(),
                    element.to(),
                    element.identifying(),
                    knowledge));
          }
          return new Output(found.snapshot(), entries);
        });
  }
}

@Command(
    name = "fact",
    description = "Retrieves a Fact Revision with its rationale, by revision URN or Fact URN.")
final class FactCommand extends SemanticCommand.Base {
  @Parameters(paramLabel = "URN")
  private @Nullable String urn;

  @Option(
      names = "--snapshot",
      paramLabel = "URN",
      description = "For a Fact URN, the point; defaults to the latest.")
  private @Nullable String snapshot;

  FactCommand(InputStream standardInput, Map<String, String> environment) {
    super(standardInput, environment);
  }

  @Override
  String schema() {
    return "fact-revision";
  }

  @Override
  Object run(Workspace workspace) {
    return session(
        workspace,
        session -> session.catalog().reads().factRevision(Objects.requireNonNull(urn), snapshot));
  }
}

@Command(name = "policy", description = "Shows, previews, or sets the user policy version.")
final class PolicyCommand extends SemanticCommand.Base {
  @Option(
      names = "--preset",
      paramLabel = "PRESET",
      description = "full-auto, auto, or manual. Without --set, previews it.")
  private @Nullable String preset;

  @Option(
      names = "--count-declared-unenforced",
      description = "Count declared but unenforced constraints as deterministic.")
  private boolean countDeclaredUnenforced;

  @Option(names = "--set", description = "Record the setting as a new version.")
  private boolean set;

  @Option(names = "--reason", paramLabel = "TEXT", defaultValue = "")
  private String reason = "";

  @Option(names = "--principal", paramLabel = "PRINCIPAL")
  private @Nullable String principal;

  @Option(names = "--max-tasks", paramLabel = "N", defaultValue = "50")
  private int maxTasks;

  PolicyCommand(InputStream standardInput, Map<String, String> environment) {
    super(standardInput, environment);
  }

  @Override
  String schema() {
    return "policy";
  }

  record Output(
      Outputs.@Nullable PolicyPreview preview,
      Outputs.@Nullable PolicyVersionSet set,
      List<SemanticCommands.RunSummary> runs,
      Outputs.SnapshotSummary summary) {}

  @Override
  Object run(Workspace workspace) {
    var who = principal(principal);
    return session(
        workspace,
        session -> {
          var reads = session.catalog().reads();
          if (preset == null) {
            return new Output(null, null, List.of(), reads.summary());
          }
          var value = SemanticCommands.wire(Preset.class, preset, "--preset");
          if (!set) {
            return new Output(
                reads.previewPolicy(value, countDeclaredUnenforced),
                null,
                List.of(),
                reads.summary());
          }
          session.authorize(who);
          var recorded =
              session.catalog().setPolicyVersion(who, null, value, countDeclaredUnenforced, reason);
          var runs = SemanticCommands.summarize(session, session.runCreated(maxTasks));
          return new Output(null, recorded, runs, session.catalog().reads().summary());
        });
  }
}

@Command(
    name = "observe",
    description =
        "Registers a document, ruling, missing knowledge, or user correction as an observation.")
final class ObserveCommand extends SemanticCommand.Base {
  @Option(
      names = "--kind",
      required = true,
      paramLabel = "KIND",
      description = "document, ruling, missing-knowledge, or user-correction.")
  private @Nullable String kind;

  @Option(names = "--text", paramLabel = "TEXT")
  private @Nullable String text;

  @Option(
      names = "--file",
      paramLabel = "PATH",
      description = "UTF-8 text; for missing-knowledge, JSON is also accepted.")
  private @Nullable Path file;

  @Option(names = "--title", paramLabel = "TITLE")
  private @Nullable String title;

  @Option(
      names = "--resource",
      paramLabel = "URN",
      description = "Replace the content of an existing document or ruling.")
  private @Nullable String resource;

  @Option(
      names = "--question",
      paramLabel = "URN",
      description = "Required for missing-knowledge and user-correction.")
  private @Nullable String question;

  @Option(
      names = "--fact",
      paramLabel = "URN",
      description = "For a user-correction, the Fact corrected.")
  private @Nullable String fact;

  @Option(names = "--principal", paramLabel = "PRINCIPAL")
  private @Nullable String principal;

  @Option(names = "--run-tasks", description = "Then run the build tasks the observation created.")
  private boolean runTasks;

  @Option(names = "--max-tasks", paramLabel = "N", defaultValue = "50")
  private int maxTasks;

  ObserveCommand(InputStream standardInput, Map<String, String> environment) {
    super(standardInput, environment);
  }

  @Override
  String schema() {
    return "observation";
  }

  record Output(Outputs.ObservationRegistered registered, List<SemanticCommands.RunSummary> runs) {}

  @Override
  Object run(Workspace workspace) throws IOException {
    var observationKind = SemanticCommands.wire(ObservationKind.class, kind, "--kind");
    var body = text;
    if (file != null) {
      body = Files.readString(file, StandardCharsets.UTF_8);
    }
    if (body == null || body.isBlank()) {
      throw new ApplicationFailure("INVALID_REQUEST", "Give the content with --text or --file.");
    }
    var content = Json.object();
    if (title != null) {
      content.put("title", title);
    }
    if (observationKind == ObservationKind.MISSING_KNOWLEDGE && body.strip().startsWith("{")) {
      content.setAll((com.fasterxml.jackson.databind.node.ObjectNode) Json.read(body));
    } else {
      content.put("text", body);
    }
    if (fact != null) {
      content.put("fact", fact);
    }
    var who = principal(principal);
    var finalContent = content;
    return session(
        workspace,
        session -> {
          var registered =
              session
                  .catalog()
                  .registerObservation(
                      who, null, observationKind, resource, question, finalContent);
          var runs = runTasks ? session.runCreated(maxTasks) : List.<Outputs.TaskRun>of();
          return new Output(registered, SemanticCommands.summarize(session, runs));
        });
  }
}

@Command(
    name = "question",
    description = "Issues a question URN for tracing knowledge requests, answers, and citations.")
final class QuestionCommand extends SemanticCommand.Base {
  @Option(names = "--text", paramLabel = "TEXT")
  private @Nullable String text;

  QuestionCommand(InputStream standardInput, Map<String, String> environment) {
    super(standardInput, environment);
  }

  @Override
  String schema() {
    return "question";
  }

  record Output(String question, @Nullable String text, String snapshot) {}

  @Override
  Object run(Workspace workspace) {
    return session(
        workspace,
        session -> new Output(Urns.question(), text, session.catalog().latestSnapshot()));
  }
}

@Command(
    name = "verify",
    description = "Replays the log and compares it with the materialized state.")
final class VerifyCommand extends SemanticCommand.Base {
  VerifyCommand(InputStream standardInput, Map<String, String> environment) {
    super(standardInput, environment);
  }

  @Override
  String schema() {
    return "verification";
  }

  @Override
  Object run(Workspace workspace) {
    return session(workspace, session -> session.catalog().verify());
  }
}
