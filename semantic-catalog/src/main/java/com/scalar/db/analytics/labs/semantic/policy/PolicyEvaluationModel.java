/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.model.Knowledge;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.Review;
import java.util.List;

/**
 * The policy evaluation model: the model, kept separate from the Generators, that under {@code
 * auto} weighs the signals of a candidate or a retirement proposal that inline evaluation left
 * undecided, and either adopts it or sends it to the deciding party with review material (see
 * {@code docs/semantic-catalog/construction.md}, "Choosing the confirmation path").
 *
 * <p>It runs inside a build task, outside any transaction. A refuted candidate and a second Fact at
 * a selected Fact Address never reach it, because inline evaluation always sends those to a person.
 */
public interface PolicyEvaluationModel {
  /**
   * The model and the prompt, recorded on every evaluation it makes as a Generator's version is
   * recorded.
   */
  JsonNode version();

  /**
   * Evaluates one subject.
   *
   * @throws RuntimeException when the model is unavailable; the build task fails with the message
   *     and can be resumed
   */
  Decision evaluate(Subject subject);

  /** What the model evaluates: the material a review item shows, with content expanded. */
  sealed interface Subject {
    Review.Target target();

    /** The Fact's latest revision: value, dependency references, checks, and provenance. */
    Knowledge.RevisionContent fact();

    /**
     * The domain's recording language, in which the decision's reason and review material are
     * written (see {@code docs/semantic-catalog/languages.md}).
     */
    String recordingLanguage();

    /**
     * The confirmation of a candidate.
     *
     * @param inputsRead what the Generator read, from the candidate's latest acceptance
     * @param premises the content of every premise dependency, expanded
     */
    record Confirmation(
        Review.Target target,
        Knowledge.RevisionContent fact,
        JsonNode inputsRead,
        List<Knowledge.RevisionContent> premises,
        String recordingLanguage)
        implements Subject {
      public Confirmation {
        premises = List.copyOf(premises);
      }
    }

    /**
     * The retirement of a confirmed Fact on its open retirement proposal.
     *
     * @param proposal the log record of the open retirement proposal
     */
    record Retirement(
        Review.Target target,
        Knowledge.RevisionContent fact,
        String proposal,
        LogRecord.ProposeRetire proposalRecord,
        String recordingLanguage)
        implements Subject {}
  }

  /** What the model decided, with its reason. */
  sealed interface Decision {
    String reason();

    /** Adopt without consent. */
    record Adopt(String reason) implements Decision {}

    /**
     * Send to the deciding party.
     *
     * @param reviewMaterial what is to be decided, why it was not adopted, the evidence for and
     *     against, a suggested answer with reasons, and its grouping
     */
    record RequestConsent(String reason, JsonNode reviewMaterial) implements Decision {}
  }
}
