/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.scalar.db.analytics.labs.semantic.generator.DataGenerator;
import com.scalar.db.analytics.labs.semantic.generator.Generator;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext;
import com.scalar.db.analytics.labs.semantic.generator.Generators;
import com.scalar.db.analytics.labs.semantic.generator.ModelGenerator;
import com.scalar.db.analytics.labs.semantic.generator.Output;
import com.scalar.db.analytics.labs.semantic.generator.StoreGenerator;
import com.scalar.db.analytics.labs.semantic.llm.LanguageModelException;
import com.scalar.db.analytics.labs.semantic.model.BuildRunInProgressException;
import com.scalar.db.analytics.labs.semantic.model.BuildTask;
import com.scalar.db.analytics.labs.semantic.model.Languages;
import com.scalar.db.analytics.labs.semantic.model.LogPosition;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.SemanticCatalogException;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.PolicyResult;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Transition;
import com.scalar.db.analytics.labs.semantic.policy.PolicyEvaluationModel;
import com.scalar.db.analytics.labs.semantic.projection.ExpressionValues;
import com.scalar.db.analytics.labs.semantic.projection.Projection;
import com.scalar.db.analytics.labs.semantic.projection.TransitionResolver;
import com.scalar.db.analytics.labs.semantic.projection.TransitionResolver.ModelWait;
import com.scalar.db.analytics.labs.semantic.storage.JdbcStore;
import com.scalar.db.analytics.labs.semantic.storage.StoreTransaction;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * Runs one domain's build tasks under its builder semaphore (see {@code
 * docs/semantic-catalog/storage.md}, "Running build tasks").
 *
 * <p>Each task is claimed in one transaction, then run and recorded. Work that calls a model runs
 * outside any transaction and is recorded in one more, so a model call or a construction query
 * never holds a transaction open; a deterministic Generator that reads only the store runs inside
 * its recording transaction instead, so it reads the state it is recorded on. The semaphore makes
 * the reset at the start safe: a task left {@code running} belongs to a process that has stopped,
 * because a running one would still hold the semaphore.
 */
final class BuildRunner {
  /** The party recorded on evaluations by the policy evaluation model. */
  static final String POLICY_EVALUATION_MODEL = "policy-evaluation-model";

  private final JdbcStore store;
  private final ExpressionValues expressions;
  private final String domain;
  private final Languages languages;
  private final BuildRun.Environment environment;

  BuildRunner(
      JdbcStore store,
      ExpressionValues expressions,
      String domain,
      Languages languages,
      BuildRun.Environment environment) {
    this.store = store;
    this.expressions = expressions;
    this.domain = domain;
    this.languages = languages;
    this.environment = environment;
  }

  @SuppressWarnings("try") // The lock is held for the whole run and released on close.
  BuildRun.Summary run(BuildRun.Mode mode, BuildRun.Listener listener) {
    try (var lock =
        store.tryLockBuilder(domain).orElseThrow(() -> new BuildRunInProgressException(domain))) {
      var reset = resetAbandoned();
      var finished = new ArrayList<BuildTask>();
      while (!stopRequested(mode)) {
        lock.requireHeld();
        var next = store.read(transaction -> firstCreated(transaction));
        if (next.isEmpty()) {
          if (!(mode instanceof BuildRun.Mode.KeepWaiting waiting) || !pause(waiting)) {
            break;
          }
          continue;
        }
        var claimed = claim(next.get());
        if (claimed.isEmpty()) {
          continue;
        }
        var done = execute(claimed.get());
        finished.add(done);
        listener.finished(done);
      }
      return new BuildRun.Summary(reset, finished);
    }
  }

  private static boolean stopRequested(BuildRun.Mode mode) {
    return mode instanceof BuildRun.Mode.KeepWaiting waiting
        && waiting.stopRequested().getAsBoolean();
  }

  /** Waits for the poll interval; false when interrupted or asked to stop. */
  private static boolean pause(BuildRun.Mode.KeepWaiting waiting) {
    try {
      Thread.sleep(waiting.pollInterval());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
    return !waiting.stopRequested().getAsBoolean();
  }

  private Optional<BuildTask> firstCreated(StoreTransaction transaction) {
    return transaction.firstBuildTaskIn(domain, BuildTask.State.CREATED);
  }

  /** Returns every task left {@code running} to {@code created}; a stopped run wrote nothing. */
  private List<Long> resetAbandoned() {
    return store.write(
        transaction -> {
          var reset = new ArrayList<Long>();
          for (var task : transaction.buildTasksIn(domain, BuildTask.State.RUNNING)) {
            transaction.updateBuildTask(
                domain, withStatus(task, new BuildTask.Status.Created()), BuildTask.State.RUNNING);
            reset.add(task.number());
          }
          return reset;
        });
  }

  /**
   * Moves a task from created to running; empty when it is no longer created. The task is read
   * again in the claiming transaction, so a trigger added since the run found it is kept.
   */
  private Optional<BuildTask> claim(BuildTask found) {
    return store.write(
        transaction -> {
          var task =
              transaction
                  .buildTask(domain, found.number())
                  .filter(current -> current.status() instanceof BuildTask.Status.Created);
          if (task.isEmpty()) {
            return Optional.<BuildTask>empty();
          }
          var running = withStatus(task.get(), new BuildTask.Status.Running());
          return transaction.updateBuildTask(domain, running, BuildTask.State.CREATED)
              ? Optional.of(running)
              : Optional.<BuildTask>empty();
        });
  }

  /** Runs a claimed task and records its result; any failure fails the task with its reason. */
  private BuildTask execute(BuildTask task) {
    try {
      return switch (task.work()) {
        case BuildTask.Work.PolicyEvaluation evaluation -> evaluate(task, evaluation.fact());
        case BuildTask.Work.PolicyReEvaluation ignored -> reEvaluate(task);
        case BuildTask.Work.GeneratorRun run -> generate(task, run.generator());
      };
    } catch (RuntimeException e) {
      var reason = Objects.requireNonNullElse(e.getMessage(), e.getClass().getName());
      return store.write(
          transaction -> finish(transaction, task, new BuildTask.Status.Failed(reason)));
    }
  }

  // An evaluation by the policy evaluation model

  /**
   * What the model's evaluation was made on: the Fact's latest acceptance, its open retirement
   * proposal, the user policy version in force, and the Fact selected at its Fact Address. If any
   * changed before the result is recorded, the evaluation was made on stale material.
   */
  private record Basis(
      @Nullable LogPosition latestAcceptance,
      @Nullable LogPosition retirementProposal,
      String policyVersion,
      @Nullable String selected) {}

  /** What the model is to evaluate, and on what; both absent when nothing awaits it. */
  private record Prepared(@Nullable Basis basis, PolicyEvaluationModel.@Nullable Subject subject) {}

  private BuildTask evaluate(BuildTask task, String fact) {
    var prepared =
        store.read(
            transaction -> {
              var projection = new Projection(domain, transaction.projectionSource(domain));
              var wait = TransitionResolver.modelWait(projection, fact);
              if (wait != ModelWait.AWAITING) {
                return new Prepared(null, null);
              }
              var snapshot =
                  new CatalogSnapshot(
                      transaction, domain, languages, transaction.lastCommit(domain));
              var basis = basis(projection, fact);
              return new Prepared(basis, snapshot.policyEvaluationSubject(fact).orElseThrow());
            });
    PolicyEvaluationModel.Decision decision = null;
    JsonNode version = null;
    if (prepared.subject() != null) {
      var model = environment.policyEvaluationModel();
      version = model.version();
      decision = model.evaluate(prepared.subject());
    }
    var made = decision;
    var modelVersion = version;
    return store.write(
        transaction -> {
          var projection = new Projection(domain, transaction.projectionSource(domain));
          return switch (TransitionResolver.modelWait(projection, fact)) {
            case NOT_AWAITING ->
                finish(
                    transaction,
                    task,
                    new BuildTask.Status.Succeeded(new BuildTask.Result.NoLongerAwaiting()));
            case DECIDED_INLINE -> {
              var result =
                  SemanticCatalogStore.commitResolved(
                      transaction,
                      domain,
                      languages,
                      "evaluate-policy",
                      TransitionResolver.INLINE_EVALUATION,
                      task.triggers().getLast(),
                      (p, projector, commit) ->
                          TransitionResolver.settle(p, projector, commit, List.of(fact)));
              yield finish(
                  transaction,
                  task,
                  new BuildTask.Status.Succeeded(
                      new BuildTask.Result.EvaluatedInline(result.commit().orElseThrow())));
            }
            case AWAITING -> {
              if (made == null
                  || modelVersion == null
                  || !basis(projection, fact).equals(prepared.basis())) {
                yield supersede(transaction, task, fact);
              }
              yield recordEvaluation(
                  transaction,
                  task,
                  fact,
                  projection,
                  Objects.requireNonNull(prepared.basis()).policyVersion(),
                  made,
                  modelVersion);
            }
          };
        });
  }

  private BuildTask recordEvaluation(
      StoreTransaction transaction,
      BuildTask task,
      String fact,
      Projection projection,
      String policyVersion,
      PolicyEvaluationModel.Decision decision,
      JsonNode modelVersion) {
    var record = projection.fact(fact).orElseThrow().record();
    var transition = record.is(FactState.CANDIDATE) ? Transition.CONFIRM : Transition.RETIRE;
    var evaluation =
        new LogRecord.EvaluatePolicy(
            Ref.urn(fact),
            Ref.urn(policyVersion),
            transition,
            switch (decision) {
              case PolicyEvaluationModel.Decision.Adopt ignored -> PolicyResult.MACHINE_JUDGEMENT;
              case PolicyEvaluationModel.Decision.RequestConsent ignored ->
                  PolicyResult.REQUEST_CONSENT;
            },
            new LogRecord.Evaluator.Model(
                modelVersion,
                decision instanceof PolicyEvaluationModel.Decision.RequestConsent consent
                    ? consent.reviewMaterial()
                    : null),
            POLICY_EVALUATION_MODEL,
            decision.reason());
    var request = CommitRequest.builder("evaluate-policy", POLICY_EVALUATION_MODEL);
    request.add(evaluation);
    var result =
        SemanticCatalogStore.commit(
            transaction, expressions, domain, languages, request.build(), true);
    return finish(
        transaction,
        task,
        new BuildTask.Status.Succeeded(
            new BuildTask.Result.Evaluated(result.commit().orElseThrow(), modelVersion)));
  }

  /** Gives up an evaluation made on stale material, creating the task that evaluates again. */
  private BuildTask supersede(StoreTransaction transaction, BuildTask task, String fact) {
    var work = new BuildTask.Work.PolicyEvaluation(fact);
    var followUp = -1L;
    for (var trigger : task.triggers()) {
      followUp = BuildTasks.trigger(transaction, domain, work, trigger);
    }
    return finish(
        transaction,
        task,
        new BuildTask.Status.Succeeded(new BuildTask.Result.Superseded(followUp)));
  }

  private static Basis basis(Projection projection, String fact) {
    var slice = projection.fact(fact).orElseThrow();
    var selected =
        projection
            .address(slice.row().factAddress())
            .flatMap(address -> address.currentSelection())
            .map(selection -> selection.selectedFact())
            .orElse(null);
    var policy =
        projection
            .policyInForce()
            .orElseThrow(() -> new IllegalStateException("No policy version in force"));
    var record = slice.record();
    return new Basis(
        record.latestAcceptance().orElse(null),
        record.openRetirementProposal().orElse(null),
        policy.logRecord(),
        selected);
  }

  // A policy re-evaluation

  private BuildTask reEvaluate(BuildTask task) {
    return store.write(
        transaction -> {
          var facts = new ArrayList<String>();
          transaction.candidateRecords(domain).forEach(row -> facts.add(row.fact()));
          transaction.openRetirementRecords(domain).forEach(row -> facts.add(row.fact()));
          var policyVersion = new AtomicReference<String>();
          var result =
              SemanticCatalogStore.commitResolved(
                  transaction,
                  domain,
                  languages,
                  "re-evaluate-policy",
                  TransitionResolver.INLINE_EVALUATION,
                  task.triggers().getLast(),
                  (projection, projector, commit) -> {
                    policyVersion.set(
                        projection
                            .policyInForce()
                            .orElseThrow(() -> new IllegalStateException("No policy version"))
                            .logRecord());
                    return TransitionResolver.settle(projection, projector, commit, facts);
                  });
          var commit = result.commit();
          return finish(
              transaction,
              task,
              new BuildTask.Status.Succeeded(
                  new BuildTask.Result.ReEvaluated(
                      Objects.requireNonNull(policyVersion.get()),
                      commit.isPresent() ? commit.getAsLong() : null,
                      result.buildTasks().stream()
                          .filter(
                              number ->
                                  transaction
                                      .buildTask(domain, number)
                                      .filter(
                                          found ->
                                              found.work()
                                                  instanceof BuildTask.Work.PolicyEvaluation)
                                      .isPresent())
                          .toList())));
        });
  }

  // A Generator run

  private BuildTask generate(BuildTask task, String name) {
    var generator =
        Generators.named(name)
            .orElseThrow(() -> new SemanticCatalogException("Unknown Generator " + name));
    return switch (generator) {
      case StoreGenerator reading -> generateFromStore(task, reading);
      case DataGenerator reading -> generateFromData(task, reading);
      case ModelGenerator asking -> generateFromModel(task, asking);
      default ->
          throw new SemanticCatalogException("Generator " + name + " declares no way of running");
    };
  }

  /**
   * Runs a Generator that reads only the store and records its output in one commit. It runs inside
   * the recording transaction: a transaction retried after another writer's commit runs it again on
   * top of that commit, and a run on unchanged input records nothing.
   */
  private BuildTask generateFromStore(BuildTask task, StoreGenerator generator) {
    return store.write(
        transaction -> {
          var context = context(transaction, task, generator, generator.version());
          var output = new Output();
          generator.run(context, output);
          return recordRun(transaction, task, generator, generator.version(), output);
        });
  }

  /**
   * Runs a Generator that reads data values: it plans its queries at the latest commit, runs them
   * through construction-run SQL outside any transaction, and records what the reads show inside
   * the recording transaction, on the state it is recorded on. A collision repeats only the
   * recording, with the same reads.
   *
   * <p>A query whose statement is rejected or fails examines nothing of its target: the run records
   * it as missing input and still records the reads of the other targets.
   */
  private BuildTask generateFromData(BuildTask task, DataGenerator generator) {
    var version = generator.version();
    var plan =
        store.read(transaction -> generator.plan(context(transaction, task, generator, version)));
    var failed = new ArrayList<>(plan.unread());
    var reads = read(plan.queries(), failed);
    return store.write(
        transaction -> {
          var context = context(transaction, task, generator, version);
          var output = new Output();
          failed.forEach(output::missing);
          generator.record(context, reads, output);
          return recordRun(transaction, task, generator, version, output);
        });
  }

  /**
   * Runs a Generator that asks a language model: it plans its targets and the data reads the
   * domain's settings allow at the latest commit, runs the reads and asks the model outside any
   * transaction, and records what the answer proposes inside the recording transaction, for the
   * targets that still call for a candidate there. Its targets are split into model calls of a
   * bounded size; a run with no target asks nothing. The queries that check what the answers
   * propose run after the model answers, outside any transaction too.
   *
   * <p>An unavailable model, one that gives no answer in time, and an answer not in the form asked
   * for fail the task, which can be resumed.
   */
  private BuildTask generateFromModel(BuildTask task, ModelGenerator generator) {
    var model = environment.languageModel();
    var version = generator.version(model);
    var plan =
        store.read(
            transaction ->
                generator.plan(
                    context(transaction, task, generator, version), environment.modelInput()));
    var failed = new ArrayList<String>();
    List<DataGenerator.Read> reads =
        plan.asks().isEmpty() ? List.of() : read(plan.queries(), failed);
    var answers = new ArrayList<ModelGenerator.Answered>();
    for (var ask : plan.asks()) {
      answers.add(
          new ModelGenerator.Answered(
              ask,
              answerObject(model.complete(generator.instructions(), generator.input(ask, reads)))));
    }
    var checks = generator.checks(plan, answers);
    List<DataGenerator.Read> checked = checks.isEmpty() ? List.of() : read(checks, failed);
    return store.write(
        transaction -> {
          var context = context(transaction, task, generator, version);
          var output = new Output();
          plan.missing().forEach(output::missing);
          failed.forEach(output::missing);
          generator.record(context, plan, reads, answers, checked, output);
          return recordRun(transaction, task, generator, version, output);
        });
  }

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** Reads a model's answer as one JSON object, allowing a Markdown code fence around it. */
  static JsonNode answerObject(String answer) {
    var text = answer.strip();
    if (text.startsWith("```")) {
      var firstLine = text.indexOf('\n');
      var closing = text.lastIndexOf("```");
      if (firstLine >= 0 && closing > firstLine) {
        text = text.substring(firstLine + 1, closing).strip();
      }
    }
    try {
      var json = MAPPER.readTree(text);
      if (json != null && json.isObject()) {
        return json;
      }
    } catch (JsonProcessingException e) {
      // Reported below as an answer not in the form asked for.
    }
    throw new LanguageModelException("The model's answer is not one JSON object");
  }

  /**
   * Runs queries through construction-run SQL, outside any transaction. A query whose statement is
   * rejected or fails reads nothing; it is added to the failed reads, recorded as missing input.
   */
  private List<DataGenerator.Read> read(List<DataGenerator.Query> queries, List<String> failed) {
    var sql = environment.constructionSql();
    var reads = new ArrayList<DataGenerator.Read>();
    for (var query : queries) {
      try {
        var outcomes = new ArrayList<ConstructionSql.Outcome>();
        for (var statement : query.statements()) {
          outcomes.add(sql.execute(statement));
        }
        reads.add(new DataGenerator.Read(query, outcomes));
      } catch (ConstructionSql.ConstructionSqlException e) {
        failed.add(
            "the read of "
                + query.dataCheck()
                + ", which failed: "
                + Objects.requireNonNullElse(e.getMessage(), e.getClass().getName()));
      }
    }
    return reads;
  }

  private GeneratorContext context(
      StoreTransaction transaction, BuildTask task, Generator generator, String version) {
    var projection = new Projection(domain, transaction.projectionSource(domain));
    var provenance =
        JsonNodeFactory.instance
            .objectNode()
            .put("generator", generator.name())
            .put("version", version)
            .put("buildTask", task.number());
    return new GeneratorContext(
        transaction, domain, projection, task.triggers(), provenance, expressions, languages);
  }

  private BuildTask recordRun(
      StoreTransaction transaction,
      BuildTask task,
      Generator generator,
      String version,
      Output output) {
    Long commit = null;
    if (output.hasRecords()) {
      var result =
          SemanticCatalogStore.commit(
              transaction,
              expressions,
              domain,
              languages,
              output.request("generator-run", generator.name()),
              true);
      commit = result.commit().isPresent() ? result.commit().getAsLong() : null;
    }
    return finish(
        transaction,
        task,
        new BuildTask.Status.Succeeded(
            new BuildTask.Result.Generated(version, commit, output.missingInputs())));
  }

  // Task state

  private BuildTask finish(StoreTransaction transaction, BuildTask task, BuildTask.Status status) {
    var finished = withStatus(task, status);
    if (!transaction.updateBuildTask(domain, finished, BuildTask.State.RUNNING)) {
      throw new SemanticCatalogException(
          "Build task " + task.number() + " is no longer running; another run took it over");
    }
    return finished;
  }

  static BuildTask withStatus(BuildTask task, BuildTask.Status status) {
    return new BuildTask(task.number(), task.work(), task.triggers(), status);
  }
}
