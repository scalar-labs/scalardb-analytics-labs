/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;
import java.util.Locale;

/** The kinds of element and the closed-core predicates Generators produce candidates for. */
public enum ElementKind {
  DATASET(
      false,
      List.of(
          Predicates.TECHNICAL_REFERENCE,
          Predicates.ROW_CONCEPT,
          Predicates.ROW_KIND,
          Predicates.ROW_GRAIN,
          Predicates.ROW_INCLUSION,
          Predicates.KEY)),
  FIELD(
      false,
      List.of(
          Predicates.TECHNICAL_REFERENCE,
          Predicates.MEANING,
          Predicates.VALUE_KIND,
          Predicates.UNIT,
          Predicates.NULL_MEANING,
          Predicates.REPRESENTED_CONCEPT,
          Predicates.TIME_MEANING)),
  CONCEPT(false, List.of(Predicates.MEANING)),
  MEASURE(
      false,
      List.of(
          Predicates.MEANING,
          Predicates.BASE_DATASET,
          Predicates.AGGREGATION_RULE,
          Predicates.FILTER,
          Predicates.REFERENCE_TIME_FIELD,
          Predicates.DEFAULT_TIME_GRANULARITY)),
  DIMENSION(
      false,
      List.of(
          Predicates.MEANING,
          Predicates.DIMENSION_KIND,
          Predicates.CLASSIFICATION,
          Predicates.SOURCE_TIME_FIELD,
          Predicates.HIERARCHY,
          Predicates.CALENDAR_RULE)),
  BUSINESS_TERM(false, List.of(Predicates.WRITTEN_FORM, Predicates.REFERENCED_ELEMENT)),
  JOIN(
      true,
      List.of(Predicates.MULTIPLICITY, Predicates.PARTICIPATION_FROM, Predicates.PARTICIPATION_TO)),
  IDENTIFIER_CORRESPONDENCE(
      true, List.of(Predicates.SET_RELATIONSHIP, Predicates.BUSINESS_DEFINITION)),
  CONCEPT_RELATIONSHIP(true, List.of(Predicates.MULTIPLICITY));

  private final boolean edge;
  private final List<String> predicates;

  ElementKind(boolean edge, List<String> predicates) {
    this.edge = edge;
    this.predicates = predicates;
  }

  public boolean edge() {
    return edge;
  }

  /** The predicates candidates are produced for, besides existence. */
  public List<String> predicates() {
    return predicates;
  }

  /** Whether a predicate is valid for this kind: existence, a listed one, or an extension. */
  public boolean accepts(String predicate) {
    return predicate.equals(Predicates.EXISTS)
        || predicates.contains(predicate)
        || Predicates.isExtension(predicate)
        || (this == DATASET && Predicates.isUniqueness(predicate));
  }

  @JsonValue
  public String wireName() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  @JsonCreator
  public static ElementKind fromWireName(String name) {
    for (var kind : values()) {
      if (kind.wireName().equals(name)) {
        return kind;
      }
    }
    throw new IllegalArgumentException("Unknown element kind: " + name);
  }
}
