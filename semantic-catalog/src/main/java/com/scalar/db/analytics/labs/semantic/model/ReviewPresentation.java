/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import java.util.List;

/**
 * A review presentation record: what was presented in review, to whom, and when (see {@code
 * docs/semantic-catalog/construction.md}, "Review"). It is kept outside the log, because presenting
 * changes no Fact's state.
 *
 * @param kind what the deciding party was asked to judge
 * @param targets the Facts presented, with their Fact Addresses; several Fact Addresses may be
 *     presented together
 * @param recipient the person asked, who need not be the party registering the presentation
 * @param presentedAt when it was presented, as an ISO-8601 instant
 * @param context a reference to the screen or dialogue shown
 * @param resultingEvents the log records of the answers and conflict resolutions given in it, which
 *     the Semantic Catalog adds when they are recorded
 */
public record ReviewPresentation(
    Kind kind,
    List<Target> targets,
    String recipient,
    String presentedAt,
    String context,
    List<String> resultingEvents) {
  public ReviewPresentation {
    targets = List.copyOf(targets);
    resultingEvents = resultingEvents == null ? List.of() : List.copyOf(resultingEvents);
    if (targets.isEmpty()) {
      throw new IllegalArgumentException("A review presentation presents at least one Fact");
    }
    if (recipient.isBlank()) {
      throw new IllegalArgumentException("A review presentation names the person asked");
    }
  }

  /** What a presentation asks the deciding party to judge. */
  public enum Kind {
    /** Whether candidates are confirmed. */
    CONFIRMATION,
    /** Whether confirmed Facts are retired on their open retirement proposals. */
    RETIREMENT,
    /** Which of the eligible Facts at a conflicting Fact Address is presented. */
    CONFLICT
  }

  /** One Fact presented, at its Fact Address. */
  public record Target(String fact, String factAddress) {}
}
