/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One request for knowledge: a target and one of its kind's predicates, or all of them (see {@code
 * docs/semantic-catalog/public-api.md}, "Requesting knowledge"). Free-description predicates cannot
 * be requested by name, because they have no identity of their own; a request for all predicates
 * returns them.
 *
 * @param predicate the predicate requested; absent to request all predicates
 */
public record KnowledgeRequest(Target target, @Nullable String predicate) {
  /** What a request is about. */
  public sealed interface Target {
    /** An element named by its URN. */
    record Element(String element) implements Target {}

    /**
     * Every edge of a kind between two ends, whatever its identifying part, for a caller that does
     * not know the edge's URN. An end is named by the URNs of its elements in any order, such as
     * the Fields of an identifier correspondence's end.
     *
     * @param from the URNs of the end the edge starts from; for an undirected edge, either end
     * @param to the URNs of the other end
     */
    record Edge(ElementKind kind, List<String> from, List<String> to) implements Target {
      public Edge {
        from = List.copyOf(from);
        to = List.copyOf(to);
        if (from.isEmpty() || to.isEmpty()) {
          throw new IllegalArgumentException("An edge end names at least one element");
        }
      }
    }
  }

  public static KnowledgeRequest of(String element, String predicate) {
    return new KnowledgeRequest(new Target.Element(element), predicate);
  }

  /** A request for all predicates of an element. */
  public static KnowledgeRequest all(String element) {
    return new KnowledgeRequest(new Target.Element(element), null);
  }

  public static KnowledgeRequest edge(
      ElementKind kind, List<String> from, List<String> to, String predicate) {
    return new KnowledgeRequest(new Target.Edge(kind, from, to), predicate);
  }
}
