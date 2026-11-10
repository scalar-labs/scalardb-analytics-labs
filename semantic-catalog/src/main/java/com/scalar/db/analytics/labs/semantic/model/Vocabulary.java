/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

/**
 * The closed vocabularies of the record model that the store itself relies on (see {@code
 * docs/semantic-catalog/record-model.md}, "Elements and predicates"). The rest of the closed core,
 * such as the predicates of each element kind, is interpreted by Generators and judgement.
 */
public final class Vocabulary {
  /**
   * The existence predicate, which every element has: it states that the element is a target of the
   * catalog, and every other Fact about the element depends on it.
   */
  public static final String EXISTENCE = "existence";

  /**
   * The technical-reference predicate, by which a Dataset or a Field refers to the physical object
   * its data comes from. It decides whether two Datasets or two Fields are the same element.
   */
  public static final String TECHNICAL_REFERENCE = "technical-reference";

  private Vocabulary() {}

  /** The kinds of element: the node kinds and the edge kinds of the closed core. */
  public enum ElementKind {
    DATASET(false),
    FIELD(false),
    CONCEPT(false),
    MEASURE(false),
    DIMENSION(false),
    BUSINESS_TERM(false),
    JOIN(true),
    IDENTIFIER_CORRESPONDENCE(true),
    CONCEPT_RELATIONSHIP(true);

    private final boolean edge;

    ElementKind(boolean edge) {
      this.edge = edge;
    }

    public boolean edge() {
      return edge;
    }

    /** Whether elements of this kind are identified by the physical object they refer to. */
    public boolean technical() {
      return this == DATASET || this == FIELD;
    }
  }
}
