/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.scalar.db.analytics.labs.semantic.model.Vocabulary.AnswerType;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Transition;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The input and output of {@linkplain SemanticCatalogStore#submitAnswers submitting answers} (see
 * {@code docs/semantic-catalog/public-api.md}, "Submitting answers").
 */
public final class Answers {
  private Answers() {}

  /**
   * One deciding party's answers, recorded in one commit.
   *
   * @param decidedBy the deciding party
   * @param authorityCheck the reference to the check of the deciding party's authority, issued by
   *     whoever checked; recorded without interpretation and required
   * @param answers the answers, in submission order
   */
  public record Submission(String decidedBy, String authorityCheck, List<Answer> answers) {
    public Submission {
      Objects.requireNonNull(decidedBy, "decidedBy");
      Objects.requireNonNull(authorityCheck, "authorityCheck");
      answers = List.copyOf(answers);
      if (decidedBy.isBlank()) {
        throw new IllegalArgumentException("A submission names its deciding party");
      }
      if (answers.isEmpty()) {
        throw new IllegalArgumentException("A submission holds at least one answer");
      }
    }
  }

  /**
   * One answer.
   *
   * @param fact the Fact answered
   * @param transition what is answered: the confirmation of a candidate or a retirement proposal
   * @param wording the deciding party's own wording; for a correction, the given value or condition
   * @param contextSummary for a correction, and only for one, a summary of what was presented, kept
   *     with the usage feedback the correction registers
   * @param reviewPresentation the review presentation answered, if any
   */
  public record Answer(
      String fact,
      Transition transition,
      AnswerType type,
      String wording,
      @Nullable String contextSummary,
      @Nullable Long reviewPresentation) {
    public Answer {
      Objects.requireNonNull(fact, "fact");
      Objects.requireNonNull(wording, "wording");
      if ((type == AnswerType.CORRECTION) != (contextSummary != null)) {
        throw new IllegalArgumentException(
            "A correction, and only a correction, carries a summary of what was presented");
      }
      if (type == AnswerType.CORRECTION && wording.isBlank()) {
        throw new IllegalArgumentException("A correction states the given value or condition");
      }
    }
  }

  /** What one answer caused. */
  public enum Result {
    CONFIRMED,
    REJECTED,
    RETIRED,
    /** No transition: the answer is unknown. */
    UNKNOWN,
    /** No transition: a rejection or correction of a retirement proposal keeps the Fact. */
    RETIREMENT_DECLINED,
    /** No transition yet: the consent takes effect once every premise is confirmed. */
    UNCONFIRMED_DEPENDENCIES,
    /** No transition: another path judged the Fact already. */
    ALREADY_JUDGED
  }

  /**
   * The outcome of one answer.
   *
   * @param answer the log record of the answer
   * @param feedback for a correction, the usage-feedback Input Resource it registered
   */
  public record Outcome(String fact, String answer, Result result, @Nullable String feedback) {}

  /**
   * The outcome of a submission.
   *
   * @param catalogSnapshot the Catalog Snapshot of the commit written
   * @param outcomes one per answer, in submission order
   * @param cascaded the Facts the answers moved, other than the answered Facts themselves
   */
  public record Outcomes(String catalogSnapshot, List<Outcome> outcomes, Transitions cascaded) {
    public Outcomes {
      outcomes = List.copyOf(outcomes);
    }
  }
}
