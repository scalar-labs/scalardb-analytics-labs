/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A build task: one Generator run, or the re-evaluation after the user policy version changed. It
 * is kept outside the log.
 *
 * @param triggers log record identifiers, or question URNs, that caused the task
 * @param inputsRead the Input Observations the run read, by URN
 * @param result the commits written, the candidates not accepted and why, and missing inputs
 */
public record BuildTask(
    String id,
    long sequence,
    Kind kind,
    @Nullable String generator,
    @Nullable String generatorVersion,
    State state,
    List<String> triggers,
    @Nullable String policyVersion,
    List<String> inputsRead,
    @Nullable JsonNode result,
    @Nullable String waitingReason,
    @Nullable String failureReason,
    String createdAt,
    String updatedAt) {
  public BuildTask {
    triggers = List.copyOf(triggers);
    inputsRead = List.copyOf(inputsRead);
  }

  public enum Kind {
    @JsonProperty("generator-run")
    GENERATOR_RUN,
    @JsonProperty("policy-reevaluation")
    POLICY_REEVALUATION
  }

  public enum State {
    @JsonProperty("created")
    CREATED,
    @JsonProperty("running")
    RUNNING,
    @JsonProperty("waiting")
    WAITING,
    @JsonProperty("succeeded")
    SUCCEEDED,
    @JsonProperty("failed")
    FAILED
  }

  BuildTask withTrigger(String trigger, String now) {
    if (triggers.contains(trigger)) {
      return this;
    }
    var next = new ArrayList<>(triggers);
    next.add(trigger);
    return new BuildTask(
        id,
        sequence,
        kind,
        generator,
        generatorVersion,
        state,
        next,
        policyVersion,
        inputsRead,
        result,
        waitingReason,
        failureReason,
        createdAt,
        now);
  }

  BuildTask running(@Nullable String version, String now) {
    return new BuildTask(
        id,
        sequence,
        kind,
        generator,
        version,
        State.RUNNING,
        triggers,
        policyVersion,
        inputsRead,
        result,
        null,
        null,
        createdAt,
        now);
  }

  BuildTask finished(
      State next,
      @Nullable String policy,
      List<String> read,
      @Nullable JsonNode outcome,
      @Nullable String failure,
      String now) {
    return new BuildTask(
        id,
        sequence,
        kind,
        generator,
        generatorVersion,
        next,
        triggers,
        policy,
        read,
        outcome,
        null,
        failure,
        createdAt,
        now);
  }
}
