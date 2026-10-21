/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scalar.db.analytics.labs.semantic.CatalogState.FactRecord;
import com.scalar.db.analytics.labs.semantic.Outputs.AnswerResult;
import com.scalar.db.analytics.labs.semantic.Outputs.AnswersSubmitted;
import com.scalar.db.analytics.labs.semantic.Outputs.CascadedTransition;
import com.scalar.db.analytics.labs.semantic.Outputs.Effects;
import com.scalar.db.analytics.labs.semantic.Outputs.ObservationRegistered;
import com.scalar.db.analytics.labs.semantic.Outputs.PolicyVersionSet;
import com.scalar.db.analytics.labs.semantic.Outputs.PresentationRegistered;
import com.scalar.db.analytics.labs.semantic.Outputs.SelectionMade;
import com.scalar.db.analytics.labs.semantic.Outputs.TaskRun;
import com.scalar.db.analytics.labs.semantic.Outputs.Verification;
import com.scalar.db.analytics.labs.semantic.SemanticCatalogException.Kind;
import com.scalar.db.analytics.labs.semantic.generator.CatalogView;
import com.scalar.db.analytics.labs.semantic.generator.DataAccess;
import com.scalar.db.analytics.labs.semantic.generator.Generator;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorException;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorResult;
import com.scalar.db.analytics.labs.semantic.generator.LanguageModel;
import com.scalar.db.analytics.labs.semantic.model.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Json;
import com.scalar.db.analytics.labs.semantic.model.LogEntry;
import com.scalar.db.analytics.labs.semantic.model.Predicates;
import com.scalar.db.analytics.labs.semantic.model.Provenance;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.AnswerType;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ObservationKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.PresentationKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Preset;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Target;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The Semantic Catalog public API for one domain.
 *
 * <p>Opening the catalog replays the domain's log into memory. Operations are processed serially:
 * each builds one commit, applies it to the in-memory state, and writes it to storage in one
 * transaction together with the records kept outside the log. A failed write leaves the in-memory
 * state ahead of storage, so the instance must be discarded after a storage failure.
 */
public final class SemanticCatalog implements AutoCloseable {
  private final Storage storage;
  private final String domain;
  private final CatalogState state;
  private final Map<String, Generator> generators = new LinkedHashMap<>();
  private final SqlExecutor sql;
  private final LanguageModel model;
  private final Map<String, BuildTask> tasks = new LinkedHashMap<>();
  private final Map<String, ReviewPresentation> presentations = new LinkedHashMap<>();
  private boolean broken;

  private SemanticCatalog(
      Storage storage,
      String domain,
      List<Generator> generators,
      SqlExecutor sql,
      LanguageModel model) {
    this.storage = storage;
    this.domain = domain;
    this.state = replay(storage, domain);
    generators.forEach(generator -> this.generators.put(generator.name(), generator));
    this.sql = sql;
    this.model = model;
    storage.tasks().forEach(task -> tasks.put(task.id(), task));
    storage
        .presentations()
        .forEach(presentation -> presentations.put(presentation.id(), presentation));
  }

  /** What the catalog runs build tasks with. */
  public record Configuration(List<Generator> generators, SqlExecutor sql, LanguageModel model) {
    public static final Configuration READ_ONLY =
        new Configuration(List.of(), SqlExecutor.UNAVAILABLE, LanguageModel.UNAVAILABLE);
  }

  /**
   * Creates the storage of a new domain and records its initial user policy version: preset {@code
   * auto}, not counting {@code declared-unenforced} as deterministic.
   */
  public static SemanticCatalog create(
      Path file, String domain, String principal, Configuration configuration) {
    if (Files.exists(file)) {
      throw SemanticCatalogException.invalid("The Semantic Catalog storage already exists.");
    }
    var storage = Storage.open(file);
    storage.createDomain(domain);
    var catalog =
        new SemanticCatalog(
            storage,
            domain,
            configuration.generators(),
            configuration.sql(),
            configuration.model());
    catalog.operation(
        "create-domain",
        principal,
        null,
        null,
        commit -> {
          commit.append(
              new LogEntry.PolicyVersion(
                  Preset.AUTO, false, principal, "Initial version at domain creation."));
          return effects -> effects;
        });
    return catalog;
  }

  public static SemanticCatalog open(Path file, Configuration configuration) {
    if (!Files.isRegularFile(file)) {
      throw SemanticCatalogException.notFound("The Semantic Catalog storage does not exist.");
    }
    var storage = Storage.open(file);
    var domain = storage.domainName();
    if (domain == null) {
      storage.close();
      throw new SemanticCatalogException(Kind.STORAGE_FAILURE, "The storage has no domain.");
    }
    return new SemanticCatalog(
        storage, domain, configuration.generators(), configuration.sql(), configuration.model());
  }

  private static CatalogState replay(Storage storage, String domain) {
    var state = new CatalogState(domain);
    long current = -1;
    for (var record : storage.readLog()) {
      if (record.commit() != current) {
        if (current >= 0) {
          state.endCommit(current);
        }
        current = record.commit();
      }
      state.apply(record.id(), record.commit(), record.entry());
    }
    if (current >= 0) {
      state.endCommit(current);
    }
    return state;
  }

  public String domain() {
    return domain;
  }

  public long latestCommit() {
    return state.commit();
  }

  public String latestSnapshot() {
    return Urns.snapshot(domain, state.commit());
  }

  /** Read access for the public API's reads. */
  public SnapshotReads reads() {
    return new SnapshotReads(state, tasks.values(), presentations.values());
  }

  CatalogView view() {
    return state;
  }

  // Operation framework

  private <T> T operation(
      String name,
      String principal,
      @Nullable String callId,
      @Nullable Object input,
      Function<Commit, Function<Effects, T>> body) {
    if (broken) {
      throw new SemanticCatalogException(
          Kind.STORAGE_FAILURE, "The catalog must be reopened after a storage failure.");
    }
    String inputDigest = null;
    if (callId != null) {
      inputDigest =
          Json.digest(
              Json.object()
                  .put("operation", name)
                  .set("input", input == null ? Json.object() : Json.tree(input)));
      var previous = storage.call(callId);
      if (previous.isPresent()) {
        if (!previous.get().inputDigest().equals(inputDigest)) {
          throw SemanticCatalogException.invalid(
              "A different input was sent with the same call identifier.");
        }
        return replayed(previous.get().output());
      }
    }
    var commit = new Commit(state, name, principal);
    var changedTasks = new LinkedHashMap<String, BuildTask>();
    var builder = body.apply(commit);
    Effects effects;
    if (commit.empty()) {
      effects = new Effects(null, List.of());
    } else {
      commit.close();
      var triggered = createTasks(commit, changedTasks);
      effects = new Effects(Urns.snapshot(domain, commit.number), triggered);
    }
    var output = builder.apply(effects);
    var call =
        callId == null
            ? null
            : new Storage.CallRecord(
                callId,
                name,
                Objects.requireNonNull(inputDigest),
                Json.tree(new Replay(output.getClass().getName(), Json.tree(output))));
    write(
        commit.empty() ? null : commit,
        changedTasks.values(),
        pendingPresentations,
        call,
        pendingDerived);
    pendingPresentations.clear();
    pendingDerived = null;
    return output;
  }

  private record Replay(String type, JsonNode output) {}

  @SuppressWarnings("unchecked")
  private static <T> T replayed(JsonNode stored) {
    var replay = Json.convert(stored, Replay.class);
    try {
      return (T) Json.convert(replay.output(), Class.forName(replay.type()));
    } catch (ClassNotFoundException e) {
      throw new SemanticCatalogException(
          Kind.STORAGE_FAILURE, "A stored call output cannot be read.", e);
    }
  }

  private final List<ReviewPresentation> pendingPresentations = new ArrayList<>();
  private Storage.@Nullable DerivedRecord pendingDerived;

  private void write(
      @Nullable Commit commit,
      java.util.Collection<BuildTask> changedTasks,
      java.util.Collection<ReviewPresentation> changedPresentations,
      Storage.@Nullable CallRecord call,
      Storage.@Nullable DerivedRecord derived) {
    try {
      storage.write(
          new Storage.Write(
              commit,
              state,
              List.copyOf(changedTasks),
              List.copyOf(changedPresentations),
              call,
              derived));
    } catch (SemanticCatalogException e) {
      broken = commit != null;
      throw e;
    }
  }

  // Build task creation (section 7 of the domain model)

  private List<String> createTasks(Commit commit, Map<String, BuildTask> changed) {
    var triggered = new LinkedHashSet<String>();
    for (var record : commit.records()) {
      var id = record.id();
      switch (record.entry()) {
        case LogEntry.Observe observe when observe.kind() != ObservationKind.DATA -> {
          var kind = Json.tree(observe.kind()).asText();
          for (var generator : generators.values()) {
            if (generator.inputs().contains(kind)) {
              triggered.add(trigger(generator.name(), id, changed));
            }
          }
          if (observe.question() != null) {
            // Question-time observations name the question as their trigger too.
          }
        }
        case LogEntry.Confirm confirm -> {
          var fact = state.requireFact(confirm.fact());
          var kind = state.elementRecord(fact.element).descriptor.kind();
          var predicate = Predicates.isUniqueness(fact.predicate) ? "uniqueness" : fact.predicate;
          var input = Generator.confirmed(kind, predicate);
          for (var generator : generators.values()) {
            if (generator.inputs().contains(input)) {
              triggered.add(trigger(generator.name(), id, changed));
            }
          }
        }
        case LogEntry.Answer answer -> {
          var fact = state.requireFact(answer.fact());
          var remakes =
              answer.answerType() == AnswerType.REJECTION
                  || answer.answerType() == AnswerType.CORRECTION
                  || (answer.target() == Target.RETIREMENT
                      && answer.answerType() == AnswerType.CONSENT);
          if (remakes && generators.containsKey(fact.generator)) {
            triggered.add(trigger(fact.generator, id, changed));
          }
          if (answer.answerType() == AnswerType.CORRECTION) {
            for (var generator : generators.values()) {
              if (generator.inputs().contains(Generator.CORRECTION_ANSWER)) {
                triggered.add(trigger(generator.name(), id, changed));
              }
            }
          }
        }
        case LogEntry.Reject reject when reject.cause() != null -> {
          var fact = state.requireFact(reject.fact());
          if (generators.containsKey(fact.generator)) {
            triggered.add(trigger(fact.generator, id, changed));
          }
        }
        case LogEntry.Retire retire when retire.cause() != null -> {
          var fact = state.requireFact(retire.fact());
          if (generators.containsKey(fact.generator)) {
            triggered.add(trigger(fact.generator, id, changed));
          }
        }
        case LogEntry.PolicyVersion ignored when commit.number > 1 ->
            triggered.add(trigger(null, id, changed));
        default -> {}
      }
    }
    return List.copyOf(triggered);
  }

  /**
   * Adds a trigger to the created build task of the same kind and Generator, or creates one. A null
   * Generator means a policy re-evaluation.
   */
  private String trigger(
      @Nullable String generator, String trigger, Map<String, BuildTask> changed) {
    var now = Instant.now().toString();
    for (var task : tasks.values()) {
      if (task.state() == BuildTask.State.CREATED && Objects.equals(task.generator(), generator)) {
        var next = task.withTrigger(trigger, now);
        tasks.put(next.id(), next);
        changed.put(next.id(), next);
        return next.id();
      }
    }
    var sequence = tasks.size() + 1L;
    var task =
        new BuildTask(
            "task-" + sequence,
            sequence,
            generator == null ? BuildTask.Kind.POLICY_REEVALUATION : BuildTask.Kind.GENERATOR_RUN,
            generator,
            generator == null ? null : Objects.requireNonNull(generators.get(generator)).version(),
            BuildTask.State.CREATED,
            List.of(trigger),
            null,
            List.of(),
            null,
            null,
            null,
            now,
            now);
    tasks.put(task.id(), task);
    changed.put(task.id(), task);
    return task.id();
  }

  // Operations

  /**
   * Registers the table metadata the application retrieved for one Physical Catalog table (3.1).
   *
   * @param resourceIdentifier the table's resource identifier, {@code source.namespace.table}
   * @param version the table metadata version
   * @param metadata the whole table metadata; {@code columns[].analyticsType} is derived, the rest
   *     is the source-declaration part
   */
  public ObservationRegistered registerTableMetadata(
      String principal,
      @Nullable String callId,
      String resourceIdentifier,
      String version,
      JsonNode metadata) {
    var resource = Urns.tableResource(domain, resourceIdentifier);
    var input = Json.object().put("resource", resourceIdentifier).put("version", version);
    input.set("metadata", metadata);
    return operation(
        "register-table-metadata",
        principal,
        callId,
        input,
        commit -> {
          var latest = state.latestObservation(resource);
          if (storage.derivedRecordExists(resource, version)) {
            return effects ->
                new ObservationRegistered(
                    resource,
                    latest.map(CatalogView.ObservationView::urn).orElse(null),
                    "nothing",
                    effects);
          }
          var derived = derivedPart(metadata);
          pendingDerived = new Storage.DerivedRecord(resource, version, derived);
          var digest = Json.digest(sourceDeclaration(metadata));
          if (latest.isPresent() && latest.get().digest().equals(digest)) {
            return effects ->
                new ObservationRegistered(resource, latest.get().urn(), "derived-only", effects);
          }
          var urn = Urns.observation(domain, commit.nextId(0));
          commit.append(
              new LogEntry.Observe(
                  urn, resource, ObservationKind.PHYSICAL_SCHEMA, digest, metadata, version, null));
          return effects ->
              new ObservationRegistered(resource, urn, "observation-and-derived", effects);
        });
  }

  /** The source-declaration part of table metadata: everything except the Analytics types. */
  static JsonNode sourceDeclaration(JsonNode metadata) {
    var copy = metadata.deepCopy();
    if (copy instanceof ObjectNode object) {
      object
          .path("columns")
          .forEach(
              column -> {
                if (column instanceof ObjectNode columnObject) {
                  columnObject.remove("analyticsType");
                }
              });
    }
    return copy;
  }

  private static JsonNode derivedPart(JsonNode metadata) {
    var derived = Json.object();
    var columns = Json.array();
    metadata
        .path("columns")
        .forEach(
            column -> {
              var entry = Json.object().put("name", column.path("name").asText());
              entry.set("analyticsType", column.path("analyticsType"));
              columns.add(entry);
            });
    derived.set("columns", columns);
    return derived;
  }

  /**
   * Registers a document, a ruling, or a part of a trace record (3.1).
   *
   * @param inputResource the existing Input Resource of a document or ruling, or null to register a
   *     new one; ignored for trace record parts, whose Input Resource is the question URN
   */
  public ObservationRegistered registerObservation(
      String principal,
      @Nullable String callId,
      ObservationKind kind,
      @Nullable String inputResource,
      @Nullable String question,
      JsonNode content) {
    if (kind == ObservationKind.PHYSICAL_SCHEMA || kind == ObservationKind.DATA) {
      throw SemanticCatalogException.invalid(
          "Physical schema and data observations are not registered this way.");
    }
    var traceRecord =
        kind == ObservationKind.MISSING_KNOWLEDGE || kind == ObservationKind.USER_CORRECTION;
    if (traceRecord && question == null) {
      throw SemanticCatalogException.invalid("A trace record part needs a question URN.");
    }
    if (inputResource != null && !traceRecord && state.latestObservation(inputResource).isEmpty()) {
      throw SemanticCatalogException.notFound("The Input Resource does not exist.");
    }
    var input =
        Json.object()
            .put("kind", Json.tree(kind).asText())
            .put("resource", inputResource)
            .put("question", question);
    input.set("content", content);
    return operation(
        "register-observation",
        principal,
        callId,
        input,
        commit -> {
          var resource =
              traceRecord
                  ? Objects.requireNonNull(question)
                  : inputResource == null ? Urns.documentResource(domain) : inputResource;
          var digest = Json.digest(content);
          var latest = state.latestObservation(resource);
          if (latest.isPresent() && latest.get().digest().equals(digest)) {
            return effects ->
                new ObservationRegistered(resource, latest.get().urn(), "nothing", effects);
          }
          var urn = Urns.observation(domain, commit.nextId(0));
          commit.append(new LogEntry.Observe(urn, resource, kind, digest, content, null, question));
          return effects -> new ObservationRegistered(resource, urn, "observation", effects);
        });
  }

  /** One answer in a submission (3.2). */
  public record AnswerInput(
      String fact, Target target, AnswerType type, String wording, @Nullable String presentation) {}

  /**
   * Submits answers from one deciding party in one commit (3.2).
   *
   * @param authorityCheck a reference to the result of checking the deciding party's authority
   */
  public AnswersSubmitted submitAnswers(
      String principal,
      @Nullable String callId,
      @Nullable String authorityCheck,
      List<AnswerInput> answers) {
    if (authorityCheck == null || authorityCheck.isBlank()) {
      throw new SemanticCatalogException(
          Kind.NO_AUTHORITY_CHECK, "The answers have no authority check result.");
    }
    if (answers.isEmpty()) {
      throw SemanticCatalogException.invalid("No answers were given.");
    }
    for (var answer : answers) {
      var fact = state.factRecord(answer.fact());
      if (fact == null) {
        throw SemanticCatalogException.notFound("Fact not found: " + answer.fact());
      }
      if (answer.target() == Target.RETIREMENT && fact.openRetirementProposal == null) {
        throw SemanticCatalogException.notAccepted(
            "The Fact has no open retirement proposal: " + answer.fact());
      }
      if (answer.presentation() != null && !presentations.containsKey(answer.presentation())) {
        throw SemanticCatalogException.notFound(
            "Review presentation not found: " + answer.presentation());
      }
    }
    var input = Json.object().put("authorityCheck", authorityCheck);
    input.set("answers", Json.tree(answers));
    var ordered = dependencyOrder(answers);
    return operation(
        "submit-answers",
        principal,
        callId,
        input,
        commit -> {
          var judge = new Judge(state, commit);
          var results = new ArrayList<AnswerResult>();
          var answered = new HashSet<String>();
          for (var answer : ordered) {
            var outcome =
                judge.answer(
                    state.requireFact(answer.fact()),
                    answer.target(),
                    answer.type(),
                    principal,
                    authorityCheck,
                    answer.wording(),
                    answer.presentation());
            answered.add(answer.fact());
            results.add(
                new AnswerResult(
                    answer.fact(),
                    outcome.answerEvent(),
                    outcome.transition(),
                    outcome.reasonForNoTransition()));
            if (answer.presentation() != null) {
              var presentation =
                  Objects.requireNonNull(presentations.get(answer.presentation()))
                      .withResult(outcome.answerEvent());
              presentations.put(presentation.id(), presentation);
              pendingPresentations.add(presentation);
            }
          }
          var cascaded = cascadedTransitions(commit, answered);
          return effects -> new AnswersSubmitted(results, cascaded, effects);
        });
  }

  /**
   * Orders the answers of one submission so that an answer to a Fact comes after the answers to the
   * Facts it depends on. Answers are written in one commit either way, but a consent processed
   * before the consent to its dependency would only be recorded, without confirming.
   */
  private List<AnswerInput> dependencyOrder(List<AnswerInput> answers) {
    var remaining = new ArrayList<>(answers);
    var ordered = new ArrayList<AnswerInput>();
    while (!remaining.isEmpty()) {
      var pending = new HashSet<String>();
      remaining.forEach(answer -> pending.add(answer.fact()));
      var ready =
          remaining.stream()
              .filter(
                  answer ->
                      state.requireFact(answer.fact()).factDependencies().stream()
                          .noneMatch(
                              dependency ->
                                  !dependency.equals(answer.fact())
                                      && pending.contains(dependency)))
              .toList();
      var next = ready.isEmpty() ? List.of(remaining.getFirst()) : ready;
      ordered.addAll(next);
      remaining.removeAll(next);
    }
    return ordered;
  }

  private List<CascadedTransition> cascadedTransitions(Commit commit, Set<String> direct) {
    var cascaded = new ArrayList<CascadedTransition>();
    for (var record : commit.records()) {
      switch (record.entry()) {
        case LogEntry.Confirm confirm when !direct.contains(confirm.fact()) ->
            cascaded.add(new CascadedTransition(confirm.fact(), "confirmed"));
        case LogEntry.Reject reject when !direct.contains(reject.fact()) ->
            cascaded.add(new CascadedTransition(reject.fact(), "rejected"));
        case LogEntry.Retire retire when !direct.contains(retire.fact()) ->
            cascaded.add(new CascadedTransition(retire.fact(), "retired"));
        case LogEntry.EvaluatePolicy evaluation when !direct.contains(evaluation.fact()) ->
            cascaded.add(new CascadedTransition(evaluation.fact(), "evaluated"));
        default -> {}
      }
    }
    return cascaded;
  }

  /** Selects one Fact in a conflict (3.3). */
  public SelectionMade selectInConflict(
      String principal,
      @Nullable String callId,
      @Nullable String authorityCheck,
      String address,
      String selected,
      List<String> notSelected,
      String wording,
      @Nullable String presentation) {
    if (authorityCheck == null || authorityCheck.isBlank()) {
      throw new SemanticCatalogException(
          Kind.NO_AUTHORITY_CHECK, "The selection has no authority check result.");
    }
    var record = state.address(address);
    if (record == null) {
      throw SemanticCatalogException.notFound("Fact Address not found.");
    }
    var eligible = record.selectionAt(state.commit()).eligible();
    if (notSelected.isEmpty()
        || !eligible.contains(selected)
        || !eligible.containsAll(notSelected)
        || notSelected.contains(selected)) {
      throw SemanticCatalogException.notAccepted(
          "The Facts are not eligible Facts of the Fact Address at the latest commit.");
    }
    if (presentation != null && !presentations.containsKey(presentation)) {
      throw SemanticCatalogException.notFound("Review presentation not found.");
    }
    var input =
        Json.object().put("address", address).put("selected", selected).put("wording", wording);
    input.set("notSelected", Json.tree(notSelected));
    return operation(
        "select-in-conflict",
        principal,
        callId,
        input,
        commit -> {
          var id =
              commit.append(
                  new LogEntry.Select(
                      address, selected, notSelected, principal, authorityCheck, wording));
          if (presentation != null) {
            var updated = Objects.requireNonNull(presentations.get(presentation)).withResult(id);
            presentations.put(updated.id(), updated);
            pendingPresentations.add(updated);
          }
          return effects ->
              new SelectionMade(id, record.selectionAt(commit.number).selected(), effects);
        });
  }

  /** Records a new user policy version (3.4); a build task re-evaluates the candidates. */
  public PolicyVersionSet setPolicyVersion(
      String principal,
      @Nullable String callId,
      Preset preset,
      boolean countDeclaredUnenforced,
      String reason) {
    var input =
        Json.object()
            .put("preset", Judge.presetName(preset))
            .put("countDeclaredUnenforced", countDeclaredUnenforced)
            .put("reason", reason);
    return operation(
        "set-policy-version",
        principal,
        callId,
        input,
        commit -> {
          var id =
              commit.append(
                  new LogEntry.PolicyVersion(preset, countDeclaredUnenforced, principal, reason));
          return effects -> new PolicyVersionSet(id, effects);
        });
  }

  /** Registers that a judgement was requested from a deciding party (3.7). */
  public PresentationRegistered registerPresentation(
      PresentationKind kind,
      List<String> facts,
      String presentedTo,
      @Nullable String presentedAt,
      String context,
      @Nullable String question) {
    if (facts.isEmpty()) {
      throw SemanticCatalogException.invalid("A presentation needs at least one target Fact.");
    }
    var targets = new ArrayList<ReviewPresentation.Target>();
    for (var urn : facts) {
      var fact = state.factRecord(urn);
      if (fact == null) {
        throw SemanticCatalogException.notFound("Fact not found: " + urn);
      }
      targets.add(new ReviewPresentation.Target(urn, fact.address));
    }
    var presentation =
        new ReviewPresentation(
            "presentation-" + (presentations.size() + 1),
            kind,
            targets,
            presentedTo,
            presentedAt == null ? Instant.now().toString() : presentedAt,
            context,
            question,
            List.of());
    presentations.put(presentation.id(), presentation);
    write(null, List.of(), List.of(presentation), null, null);
    return new PresentationRegistered(presentation.id());
  }

  // Running build tasks (3.5, 3.6)

  public List<BuildTask> createdTasks() {
    return tasks.values().stream().filter(task -> task.state() == BuildTask.State.CREATED).toList();
  }

  public TaskRun runTask(String taskId) {
    var task = tasks.get(taskId);
    if (task == null) {
      throw SemanticCatalogException.notFound("Build task not found.");
    }
    if (task.state() != BuildTask.State.CREATED) {
      throw SemanticCatalogException.notAccepted("The build task is not created.");
    }
    return run(task);
  }

  public TaskRun resumeTask(String taskId) {
    var task = tasks.get(taskId);
    if (task == null) {
      throw SemanticCatalogException.notFound("Build task not found.");
    }
    if (task.state() != BuildTask.State.FAILED) {
      throw SemanticCatalogException.notAccepted("The build task has not failed.");
    }
    return run(task);
  }

  private TaskRun run(BuildTask task) {
    var generator = task.generator() == null ? null : generators.get(task.generator());
    if (task.kind() == BuildTask.Kind.GENERATOR_RUN && generator == null) {
      throw SemanticCatalogException.notAccepted("The build task's Generator is not configured.");
    }
    var running =
        task.running(generator == null ? null : generator.version(), Instant.now().toString());
    tasks.put(running.id(), running);
    write(null, List.of(running), List.of(), null, null);

    var nextCommit = state.commit() + 1;
    var data = new RecordingDataAccess(nextCommit);
    GeneratorResult result = null;
    String failure = null;
    if (generator != null) {
      try {
        result = generator.run(new Context(running, data));
      } catch (GeneratorException | RuntimeException e) {
        failure = Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName());
      }
    }
    if (failure != null) {
      var failed =
          running.finished(
              BuildTask.State.FAILED, null, data.read(), null, failure, Instant.now().toString());
      tasks.put(failed.id(), failed);
      write(null, List.of(failed), List.of(), null, null);
      return new TaskRun(
          failed.id(), failed.state(), null, null, failure, new Effects(null, List.of()));
    }

    var commit = new Commit(state, "run-task", "system");
    var outcome = Json.object();
    var accepted = Json.array();
    var notAccepted = Json.array();
    var policyVersion = state.policyAt(nextCommit).id();
    var judge = new Judge(state, commit);
    for (var observation : data.pending()) {
      commit.append(observation);
    }
    if (generator != null && result != null) {
      var provenance = new Provenance(generator.name(), generator.version(), running.id());
      for (var candidate : result.candidates()) {
        var acceptance = judge.accept(candidate, provenance);
        record(acceptance, accepted, notAccepted, candidate.predicate());
      }
      for (var proposal : result.retirements()) {
        var acceptance = judge.proposeRetirement(proposal, provenance);
        record(acceptance, accepted, notAccepted, "retirement");
      }
      outcome.set("missingInputs", Json.tree(result.missingInputs()));
      if (result.candidates().isEmpty() && result.retirements().isEmpty()) {
        outcome.put("noCandidates", true);
      }
    } else {
      var evaluated = judge.reevaluate();
      outcome.set("evaluated", Json.tree(evaluated));
    }
    outcome.set("accepted", accepted);
    outcome.set("notAccepted", notAccepted);
    var changed = new LinkedHashMap<String, BuildTask>();
    Effects effects;
    if (commit.empty()) {
      outcome.set("commits", Json.array());
      effects = new Effects(null, List.of());
    } else {
      commit.close();
      outcome.set("commits", Json.array().add(commit.number));
      effects = new Effects(Urns.snapshot(domain, commit.number), createTasks(commit, changed));
    }
    var succeeded =
        running.finished(
            BuildTask.State.SUCCEEDED,
            generator == null ? policyVersion : null,
            data.read(),
            outcome,
            null,
            Instant.now().toString());
    tasks.put(succeeded.id(), succeeded);
    changed.put(succeeded.id(), succeeded);
    write(commit.empty() ? null : commit, changed.values(), List.of(), null, null);
    return new TaskRun(succeeded.id(), succeeded.state(), outcome, null, null, effects);
  }

  private static void record(
      Judge.Acceptance acceptance,
      com.fasterxml.jackson.databind.node.ArrayNode accepted,
      com.fasterxml.jackson.databind.node.ArrayNode notAccepted,
      String predicate) {
    if (acceptance.recorded()) {
      accepted.add(acceptance.fact());
    } else {
      notAccepted.add(
          Json.object()
              .put("fact", acceptance.fact())
              .put("predicate", predicate)
              .put("reason", acceptance.reason()));
    }
  }

  private final class Context implements GeneratorContext {
    private final BuildTask task;
    private final DataAccess data;

    Context(BuildTask task, DataAccess data) {
      this.task = task;
      this.data = data;
    }

    @Override
    public CatalogView catalog() {
      return state;
    }

    @Override
    public String taskId() {
      return task.id();
    }

    @Override
    public List<String> triggers() {
      return task.triggers();
    }

    @Override
    public DataAccess data() {
      return data;
    }

    @Override
    public LanguageModel model() {
      return model;
    }
  }

  /**
   * Runs queries for a Generator and prepares their data observations, which are appended first in
   * the run's commit so their identifiers are known while the Generator runs.
   */
  private final class RecordingDataAccess implements DataAccess {
    private final long commitNumber;
    private final List<LogEntry.Observe> pending = new ArrayList<>();
    private final List<String> read = new ArrayList<>();

    RecordingDataAccess(long commitNumber) {
      this.commitNumber = commitNumber;
    }

    @Override
    public DataObservation query(String check, Object target, String sqlText, List<String> tables)
        throws DataAccessException {
      var result = sql.execute(sqlText);
      var resource = Urns.dataResource(domain, check, target);
      var content =
          Json.object()
              .put("check", check)
              .put("sql", sqlText)
              .put("executionId", result.executionId());
      content.set("target", Json.tree(target));
      content.set("tables", Json.tree(tables));
      content.set("columns", Json.tree(result.columns()));
      content.set("rows", Json.tree(result.rows()));
      var schemas = Json.array();
      for (var table : tables) {
        state
            .latestObservation(Urns.tableResource(domain, table))
            .ifPresent(observation -> schemas.add(observation.urn()));
      }
      content.set("physicalSchemaObservations", schemas);
      var digestInput = Json.object().put("sql", sqlText);
      digestInput.set("rows", Json.tree(result.rows()));
      var digest = Json.digest(digestInput);
      String urn = null;
      for (var observation : pending) {
        if (observation.inputResource().equals(resource) && observation.digest().equals(digest)) {
          urn = observation.urn();
        }
      }
      if (urn == null) {
        var latest = state.latestObservation(resource);
        if (latest.isPresent() && latest.get().digest().equals(digest)) {
          urn = latest.get().urn();
        }
      }
      if (urn == null) {
        urn = Urns.observation(domain, "c" + commitNumber + "." + (pending.size() + 1));
        pending.add(
            new LogEntry.Observe(urn, resource, ObservationKind.DATA, digest, content, null, null));
      }
      read.add(urn);
      return new DataObservation(resource, urn, digest, result.columns(), result.rows());
    }

    List<LogEntry.Observe> pending() {
      return pending;
    }

    List<String> read() {
      return read;
    }
  }

  // Invariant check (section 6.5)

  /**
   * Replays the whole log from an empty state and compares the result with the materialized state
   * in storage.
   */
  public Verification verify() {
    var replayed = replay(storage, domain);
    var expected = MaterializedRows.all(replayed);
    var actual = storage.materializedRows();
    var differences = new ArrayList<String>();
    for (var row : expected) {
      if (!actual.contains(row)) {
        differences.add("missing: " + row);
      }
    }
    for (var row : actual) {
      if (!expected.contains(row)) {
        differences.add("unexpected: " + row);
      }
    }
    var inMemory = MaterializedRows.all(state);
    if (!inMemory.equals(expected)) {
      differences.add("The in-memory state differs from a replay of the log.");
    }
    return new Verification(differences.isEmpty(), replayed.commit(), differences);
  }

  /** Whether a Fact exists, for callers validating input before an operation. */
  public boolean factExists(String fact) {
    return state.factRecord(fact) != null;
  }

  /** The element kind of a Fact's element. */
  @Nullable ElementKind kindOf(FactRecord fact) {
    var element = state.elementRecords().get(fact.element);
    return element == null ? null : element.descriptor.kind();
  }

  /** Whether a Fact is a candidate, for tests and callers. */
  public @Nullable FactState factState(String fact) {
    var record = state.factRecord(fact);
    return record == null ? null : record.state;
  }

  @Override
  public void close() {
    storage.close();
  }
}
