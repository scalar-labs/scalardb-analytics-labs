/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;

/**
 * One request for knowledge: a target and one of its kind's predicates (see {@code
 * docs/semantic-catalog/public-api.md}, "Requesting knowledge"). Free-description predicates cannot
 * be requested by name, because they have no identity of their own.
 */
public record KnowledgeRequest(Target target, String predicate) {
  /** What a request is about. */
  public sealed interface Target {
    /** An element named by its URN. */
    record Element(String element) implements Target {}

    /**
     * Every edge of a kind between two elements, whatever its identifying part, for a caller that
     * does not know the edge's URN.
     *
     * @param from the end the edge starts from; for an undirected edge, either end
     * @param to the other end
     */
    record Edge(ElementKind kind, String from, String to) implements Target {}
  }

  public static KnowledgeRequest of(String element, String predicate) {
    return new KnowledgeRequest(new Target.Element(element), predicate);
  }

  public static KnowledgeRequest edge(ElementKind kind, String from, String to, String predicate) {
    return new KnowledgeRequest(new Target.Edge(kind, from, to), predicate);
  }
}
