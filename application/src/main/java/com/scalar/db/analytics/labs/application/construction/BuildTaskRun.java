/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.construction;

import com.scalar.db.analytics.labs.application.ApplicationFailure;
import com.scalar.db.analytics.labs.application.ApplicationRuntime;
import com.scalar.db.analytics.labs.application.SemanticCatalogDomains;
import com.scalar.db.analytics.labs.application.workspace.Workspace;
import com.scalar.db.analytics.labs.semantic.BuildRun;
import com.scalar.db.analytics.labs.semantic.Point;
import com.scalar.db.analytics.labs.semantic.SemanticCatalogStore;
import com.scalar.db.analytics.labs.semantic.llm.LanguageModel;
import com.scalar.db.analytics.labs.semantic.model.BuildRunInProgressException;
import com.scalar.db.analytics.labs.semantic.model.BuildTask;
import com.scalar.db.analytics.labs.semantic.model.Hierarchy;
import com.scalar.db.analytics.labs.semantic.model.Review;
import com.scalar.db.analytics.labs.semantic.model.SemanticCatalogException;
import com.scalar.db.analytics.labs.semantic.policy.LanguageModelPolicyEvaluation;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Running the workspace domain's Semantic Catalog build tasks (see {@code docs/application.md},
 * "Running Semantic Catalog build tasks"). The application supplies what the tasks run with: the
 * language model the workspace configuration names, as the policy evaluation model, and
 * construction-run SQL through the runtime's Query Engine.
 */
public final class BuildTaskRun {
  /** How long a watching run waits before checking again for created tasks. */
  static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

  private BuildTaskRun() {}

  /** When a run returns. */
  public sealed interface Mode {
    /** Once no created task remains, including tasks created while running. */
    record Drain() implements Mode {}

    /**
     * Once stopped, picking up tasks other processes create meanwhile.
     *
     * @param stopRequested checked between tasks and while waiting; the run returns once it holds
     */
    record Watch(BooleanSupplier stopRequested) implements Mode {}
  }

  /**
   * One finished build task.
   *
   * @param task the task, {@code succeeded} or {@code failed}
   * @param awaitingReview the domain's items awaiting review once the task finished
   */
  public record Event(BuildTask task, int awaitingReview) {}

  /**
   * Runs the domain's created build tasks one at a time, reporting each as it finishes. The domain
   * is created if no retrieval has created it yet, so a watching run can start first.
   *
   * @throws ApplicationFailure classified when the workspace configuration names no language model,
   *     another process runs the domain's build tasks, or the Semantic Catalog fails; a task that
   *     fails is reported, not thrown
   */
  public static BuildRun.Summary run(
      Workspace workspace, ApplicationRuntime runtime, Mode mode, Consumer<Event> listener) {
    var languageModel =
        workspace
            .languageModelSettings()
            .orElseThrow(
                () ->
                    new ApplicationFailure(
                        "LANGUAGE_MODEL_NOT_CONFIGURED",
                        "Build tasks need a language model; set semanticCatalog.languageModel in"
                            + " the workspace configuration."));
    var environment =
        new BuildRun.Environment(
            new LanguageModelPolicyEvaluation(LanguageModel.open(languageModel)),
            new EngineConstructionSql(
                runtime, workspace.catalogName(), workspace.constructionSqlSettings()));
    var runMode =
        switch (mode) {
          case Mode.Drain ignored -> BuildRun.drain();
          case Mode.Watch watch -> BuildRun.keepWaiting(POLL_INTERVAL, watch.stopRequested());
        };
    var domain = workspace.semanticCatalogDomain();
    try (var semanticCatalog = SemanticCatalogStore.open(workspace.semanticCatalogSettings())) {
      SemanticCatalogDomains.ensure(semanticCatalog, domain);
      return semanticCatalog.runBuildTasks(
          domain,
          environment,
          runMode,
          task -> listener.accept(new Event(task, awaitingReview(semanticCatalog, domain))));
    } catch (BuildRunInProgressException e) {
      throw new ApplicationFailure(
          "BUILD_RUN_IN_PROGRESS",
          "Another process is running the workspace's build tasks; only one runs them at a time.");
    } catch (SemanticCatalogException e) {
      throw new ApplicationFailure(
          "BUILD_RUN_FAILED", "The Semantic Catalog could not run the build tasks.");
    }
  }

  private static int awaitingReview(SemanticCatalogStore semanticCatalog, String domain) {
    return semanticCatalog.read(
        domain,
        Point.LATEST,
        snapshot ->
            snapshot
                .read(Review.Position.ROOT, Hierarchy.Depth.CHILDREN)
                .counts()
                .awaitingReview());
  }
}
