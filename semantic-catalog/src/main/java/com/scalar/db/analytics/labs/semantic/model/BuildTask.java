/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A build task: one unit of construction work, created by the operation that needs it and run later
 * by a builder (see {@code docs/semantic-catalog/construction.md}, "Build tasks"). It is kept
 * outside the log.
 *
 * @param number the task's number, increasing within the domain
 * @param work what the task does
 * @param triggers the URNs of the log records that called for the task, oldest first; a trigger
 *     arriving while the task is {@code created} is added to it
 * @param status where the task is in its lifecycle
 */
public record BuildTask(long number, Work work, List<String> triggers, Status status) {
  public BuildTask {
    triggers = List.copyOf(triggers);
    if (triggers.isEmpty()) {
      throw new IllegalArgumentException("A build task has at least one trigger");
    }
  }

  /** The kinds of build task, as stored. */
  public enum Kind {
    POLICY_EVALUATION("policy-evaluation"),
    POLICY_RE_EVALUATION("policy-re-evaluation");

    private final String value;

    Kind(String value) {
      this.value = value;
    }

    /** The stored form. */
    public String value() {
      return value;
    }

    public static Kind of(String value) {
      for (var kind : values()) {
        if (kind.value.equals(value)) {
          return kind;
        }
      }
      throw new IllegalArgumentException("Unknown build task kind " + value);
    }
  }

  /** What a build task does. */
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = Work.PolicyEvaluation.class, name = "policy-evaluation"),
    @JsonSubTypes.Type(value = Work.PolicyReEvaluation.class, name = "policy-re-evaluation")
  })
  public sealed interface Work {
    Kind kind();

    /** The Fact the work concerns, by which a new trigger finds a created task; absent if none. */
    @Nullable String target();

    /**
     * An evaluation by the policy evaluation model of one candidate's confirmation, or of one
     * confirmed Fact's open retirement proposal, that inline evaluation left undecided.
     */
    record PolicyEvaluation(String fact) implements Work {
      @Override
      public Kind kind() {
        return Kind.POLICY_EVALUATION;
      }

      @Override
      public String target() {
        return fact;
      }
    }

    /** A re-evaluation of every evaluable candidate after a user policy version was set. */
    record PolicyReEvaluation() implements Work {
      @Override
      public Kind kind() {
        return Kind.POLICY_RE_EVALUATION;
      }

      @Override
      public @Nullable String target() {
        return null;
      }
    }
  }

  /** The states of a build task, as stored. */
  public enum State {
    CREATED("created"),
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed");

    private final String value;

    State(String value) {
      this.value = value;
    }

    /** The stored form. */
    public String value() {
      return value;
    }
  }

  /** Where a build task is in its lifecycle. */
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "state")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = Status.Created.class, name = "created"),
    @JsonSubTypes.Type(value = Status.Running.class, name = "running"),
    @JsonSubTypes.Type(value = Status.Succeeded.class, name = "succeeded"),
    @JsonSubTypes.Type(value = Status.Failed.class, name = "failed")
  })
  public sealed interface Status {
    State state();

    /** Not run yet. */
    record Created() implements Status {
      @Override
      public State state() {
        return State.CREATED;
      }
    }

    /** Being run; a run that stops leaves it so until the next run returns it to created. */
    record Running() implements Status {
      @Override
      public State state() {
        return State.RUNNING;
      }
    }

    /** Run, with its result recorded. */
    record Succeeded(Result result) implements Status {
      @Override
      public State state() {
        return State.SUCCEEDED;
      }
    }

    /** Failed with a reason; resuming returns it to created. */
    record Failed(String reason) implements Status {
      @Override
      public State state() {
        return State.FAILED;
      }
    }
  }

  /** What a succeeded build task did. */
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "outcome")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = Result.Evaluated.class, name = "evaluated"),
    @JsonSubTypes.Type(value = Result.EvaluatedInline.class, name = "evaluated-inline"),
    @JsonSubTypes.Type(value = Result.NoLongerAwaiting.class, name = "no-longer-awaiting"),
    @JsonSubTypes.Type(value = Result.Superseded.class, name = "superseded"),
    @JsonSubTypes.Type(value = Result.ReEvaluated.class, name = "re-evaluated")
  })
  public sealed interface Result {
    /**
     * The policy evaluation model's evaluation was recorded.
     *
     * @param commit the commit that recorded it with the transitions it caused
     * @param modelVersion the version of the policy evaluation model that made it
     */
    record Evaluated(long commit, JsonNode modelVersion) implements Result {}

    /**
     * Inline evaluation decided the Fact by the time the task ran, because the state it rests on
     * changed, so the model was not called.
     *
     * @param commit the commit of the inline evaluation and the transitions it caused
     */
    record EvaluatedInline(long commit) implements Result {}

    /**
     * Nothing was recorded: by the time the result was recorded the Fact no longer waited for the
     * policy evaluation model, because it was judged, answered, or no longer evaluable.
     */
    record NoLongerAwaiting() implements Result {}

    /**
     * Nothing was recorded: the Fact's latest acceptance, the policy version in force, or the
     * selection at its Fact Address changed while the model ran, so the evaluation was made on
     * stale material and a new evaluation task takes its place.
     *
     * @param followUp the number of the task that evaluates the Fact again
     */
    record Superseded(long followUp) implements Result {}

    /**
     * The evaluable candidates were evaluated inline under the policy version in force.
     *
     * @param policyVersion the log record of the version evaluated under
     * @param commit the commit of the evaluations and the transitions they caused; absent when
     *     inline evaluation decided none
     * @param evaluationTasks the evaluation tasks created or triggered for the candidates left to
     *     the policy evaluation model
     */
    record ReEvaluated(String policyVersion, @Nullable Long commit, List<Long> evaluationTasks)
        implements Result {
      public ReEvaluated {
        evaluationTasks = List.copyOf(evaluationTasks);
      }
    }
  }
}
