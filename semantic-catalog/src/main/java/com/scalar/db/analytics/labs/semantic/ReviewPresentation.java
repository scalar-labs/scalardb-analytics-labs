/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.scalar.db.analytics.labs.semantic.model.Vocabulary.PresentationKind;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A review presentation record: that a judgement was requested from a deciding party. It is kept
 * outside the log.
 *
 * @param context a reference to the screen or dialogue presented
 * @param results the answer or selection-in-a-conflict Fact Events that referenced this record
 */
public record ReviewPresentation(
    String id,
    PresentationKind kind,
    List<Target> targets,
    String presentedTo,
    String presentedAt,
    String context,
    @Nullable String question,
    List<String> results) {
  public ReviewPresentation {
    targets = List.copyOf(targets);
    results = List.copyOf(results);
  }

  public record Target(String fact, String address) {}

  ReviewPresentation withResult(String event) {
    var next = new ArrayList<>(results);
    next.add(event);
    return new ReviewPresentation(
        id, kind, targets, presentedTo, presentedAt, context, question, next);
  }
}
