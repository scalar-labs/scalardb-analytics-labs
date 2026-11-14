/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.scalar.db.analytics.labs.semantic.model.BuildTask;
import com.scalar.db.analytics.labs.semantic.policy.PolicyEvaluationModel;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Running a domain's build tasks (see {@code docs/semantic-catalog/public-api.md}, "Running build
 * tasks and resuming a failed one").
 */
public final class BuildRun {
  private BuildRun() {}

  /**
   * What the build tasks run with: the components the Semantic Catalog calls while running them.
   *
   * @param policyEvaluationModel the policy evaluation model that evaluation tasks call
   */
  public record Environment(PolicyEvaluationModel policyEvaluationModel) {}

  /** When a run returns. */
  public sealed interface Mode {
    /** Return once no created task remains, including tasks created while running. */
    record Drain() implements Mode {}

    /**
     * Keep waiting for new tasks, checking for them at an interval, until stopped.
     *
     * @param pollInterval how long to wait before checking again when no created task remains
     * @param stopRequested checked between tasks and while waiting; the run returns once it holds
     */
    record KeepWaiting(Duration pollInterval, BooleanSupplier stopRequested) implements Mode {}
  }

  /** Returns once no created task remains. */
  public static Mode drain() {
    return new Mode.Drain();
  }

  /** Keeps waiting for new tasks until stopped. */
  public static Mode keepWaiting(Duration pollInterval, BooleanSupplier stopRequested) {
    return new Mode.KeepWaiting(pollInterval, stopRequested);
  }

  /** Receives each task as it finishes, {@code succeeded} or {@code failed}. */
  @FunctionalInterface
  public interface Listener {
    void finished(BuildTask task);
  }

  /**
   * What a run did.
   *
   * @param reset the tasks a stopped run had left {@code running}, returned to {@code created} at
   *     the start
   * @param finished the tasks the run finished, in the order finished
   */
  public record Summary(List<Long> reset, List<BuildTask> finished) {
    public Summary {
      reset = List.copyOf(reset);
      finished = List.copyOf(finished);
    }
  }
}
