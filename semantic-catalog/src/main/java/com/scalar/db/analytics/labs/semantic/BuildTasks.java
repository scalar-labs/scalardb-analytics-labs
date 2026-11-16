/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.scalar.db.analytics.labs.semantic.generator.Generator;
import com.scalar.db.analytics.labs.semantic.generator.Generators;
import com.scalar.db.analytics.labs.semantic.model.BuildTask;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.InputKind;
import com.scalar.db.analytics.labs.semantic.projection.PositionedRecord;
import com.scalar.db.analytics.labs.semantic.projection.Projection;
import com.scalar.db.analytics.labs.semantic.storage.StoreTransaction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
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

  /**
   * Creates or triggers the Generator runs a commit calls for (see {@code
   * docs/semantic-catalog/generators.md}, "The Generator contract"): each new physical schema
   * observation triggers the Generators that read physical schemas, each confirmation triggers the
   * Generators that read confirmed Facts of its predicate and element kind, and each acceptance
   * written triggers those that read accepted ones. The trigger is the log record of the
   * observation, the confirmation, or the acceptance. A data observation triggers nothing, because
   * it is written with the results that read it.
   *
   * @param records the commit's log records
   * @return the numbers of the tasks, each once
   */
  static List<Long> generatorRunsFor(
      StoreTransaction transaction,
      String domain,
      Projection projection,
      List<PositionedRecord> records) {
    var tasks = new LinkedHashSet<Long>();
    for (var positioned : records) {
      var trigger = Urns.logRecord(domain, positioned.position());
      var generators =
          switch (positioned.record()) {
            case LogRecord.InputObservation observation ->
                projection
                        .inputResource(observation.inputResource().urnValue())
                        .filter(row -> row.kind() == InputKind.PHYSICAL_SCHEMA)
                        .isPresent()
                    ? Generators.readingPhysicalSchema()
                    : List.<Generator>of();
            case LogRecord.Confirm confirm -> confirmedReaders(projection, confirm);
            case LogRecord.Accept accept -> acceptedReaders(projection, accept);
            default -> List.<Generator>of();
          };
      for (var generator : generators) {
        tasks.add(
            trigger(
                transaction, domain, new BuildTask.Work.GeneratorRun(generator.name()), trigger));
      }
    }
    return List.copyOf(tasks);
  }

  private static List<Generator> confirmedReaders(
      Projection projection, LogRecord.Confirm confirm) {
    var address = projection.factAddress(confirm.fact().urnValue()).flatMap(projection::address);
    if (address.isEmpty()) {
      return List.of();
    }
    return projection
        .element(address.get().row().element())
        .map(row -> Generators.readingConfirmed(row.kind(), address.get().row().predicate()))
        .orElse(List.of());
  }

  private static List<Generator> acceptedReaders(Projection projection, LogRecord.Accept accept) {
    var address = projection.factAddress(accept.fact().urnValue()).flatMap(projection::address);
    if (address.isEmpty()) {
      return List.of();
    }
    return projection
        .element(address.get().row().element())
        .map(row -> Generators.readingAccepted(row.kind(), address.get().row().predicate()))
        .orElse(List.of());
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
