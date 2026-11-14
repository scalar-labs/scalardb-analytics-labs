/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.scalar.db.analytics.labs.semantic.model.Vocabulary.SessionPurpose;
import java.util.List;
import java.util.Objects;

/**
 * The content of one piece of usage feedback, the observation of a usage-feedback Input Resource
 * (see {@code docs/semantic-catalog/construction.md}, "Usage feedback"). The registering principal
 * is recorded on the commit, not here.
 *
 * @param content what the session found worth keeping: missing knowledge or a statement
 * @param purpose the purpose of the session it came from
 * @param contextSummary a summary of the session's context written by the registering party, so
 *     that a statement such as "that one is tax-exclusive" can be interpreted. It is a summary
 *     rather than the conversation, because the log cannot erase what it holds.
 * @param relatedFacts written Facts the feedback is about, if any; clues for interpretation, never
 *     commands
 */
public record UsageFeedback(
    String content, SessionPurpose purpose, String contextSummary, List<Ref> relatedFacts) {
  public UsageFeedback {
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(purpose, "purpose");
    Objects.requireNonNull(contextSummary, "contextSummary");
    relatedFacts = relatedFacts == null ? List.of() : List.copyOf(relatedFacts);
    if (content.isBlank()) {
      throw new IllegalArgumentException("Usage feedback has content");
    }
    if (contextSummary.isBlank()) {
      throw new IllegalArgumentException("Usage feedback has a summary of its session's context");
    }
    for (var fact : relatedFacts) {
      if (!(fact instanceof Ref.Urn)) {
        throw new IllegalArgumentException("Usage feedback relates only to written Facts, by URN");
      }
    }
  }
}
