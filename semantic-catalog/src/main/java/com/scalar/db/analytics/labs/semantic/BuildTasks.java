/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.scalar.db.analytics.labs.semantic.model.BuildTask;
import com.scalar.db.analytics.labs.semantic.storage.StoreTransaction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Creating build tasks inside the transaction of the operation that calls for them (see {@code
 * docs/semantic-catalog/construction.md}, "Build tasks").
 */
final class BuildTasks {
  private BuildTasks() {}

  /**
   * Adds a trigger to the created task of the same work, or creates one when there is none. A task
   * that has started running takes no more triggers, because its run did not read the new input.
   *
   * @param trigger the URN of the log record that calls for the work
   * @return the number of the task
   */
  static long trigger(
      StoreTransaction transaction, String domain, BuildTask.Work work, String trigger) {
    var created = transaction.createdBuildTask(domain, work.kind(), work.target());
    if (created.isEmpty()) {
      return transaction
          .insertBuildTask(domain, work, List.of(trigger), new BuildTask.Status.Created())
          .number();
    }
    var task = created.get();
    if (!task.triggers().contains(trigger)) {
      var triggers = new ArrayList<>(task.triggers());
      triggers.add(trigger);
      var updated = new BuildTask(task.number(), task.work(), triggers, task.status());
      if (!transaction.updateBuildTask(domain, updated, BuildTask.State.CREATED)) {
        throw new IllegalStateException("Build task " + task.number() + " changed under its read");
      }
    }
    return task.number();
  }

  /** Creates or triggers one evaluation task per Fact left to the policy evaluation model. */
  static List<Long> evaluationsFor(
      StoreTransaction transaction, String domain, Collection<String> facts, String trigger) {
    var tasks = new ArrayList<Long>();
    for (var fact : facts) {
      tasks.add(trigger(transaction, domain, new BuildTask.Work.PolicyEvaluation(fact), trigger));
    }
    return tasks;
  }
}
