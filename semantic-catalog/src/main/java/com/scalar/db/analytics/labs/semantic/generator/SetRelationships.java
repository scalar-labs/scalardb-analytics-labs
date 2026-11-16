/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;

/**
 * The values of an identifier correspondence's set relationship and the validation items each
 * claims (see {@code docs/semantic-catalog/record-model.md}, "Elements and predicates"). A value is
 * read from the end the correspondence starts from, so {@code subset} means that end's values are
 * among the other's.
 *
 * <p>Each value is a combination of claims about the two ends that the set relationship check
 * examines separately, and a foreign key on non-null columns declares one of them: every value of
 * the referencing end is matched on the referenced end.
 */
public final class SetRelationships {
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

  public static final String EQUAL = "equal";
  public static final String SUBSET = "subset";
  public static final String SUPERSET = "superset";
  public static final String PARTIAL_OVERLAP = "partial-overlap";
  public static final String DISJOINT = "disjoint";

  private SetRelationships() {}

  /** An end of a correspondence. */
  public enum End {
    FROM("from"),
    TO("to");

    private final String label;

    End(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** The item that every value of an end is matched on the other end. */
  public static JsonNode everyValueMatched(End end) {
    return JSON.objectNode().put("every-value-matched", end.label());
  }

  /** The item that some value of an end is not matched on the other end. */
  public static JsonNode someValueUnmatched(End end) {
    return JSON.objectNode().put("some-value-unmatched", end.label());
  }

  /** The item that some value is matched on both ends. */
  public static JsonNode someValueMatched() {
    return JSON.objectNode().put("some-value-matched", true);
  }

  /** The item that no value is matched on both ends. */
  public static JsonNode noValueMatched() {
    return JSON.objectNode().put("no-value-matched", true);
  }

  /** The items a value claims, or none for a value that is not a set relationship. */
  public static List<JsonNode> claims(String value) {
    return switch (value) {
      case EQUAL -> List.of(everyValueMatched(End.FROM), everyValueMatched(End.TO));
      case SUBSET -> List.of(everyValueMatched(End.FROM), someValueUnmatched(End.TO));
      case SUPERSET -> List.of(someValueUnmatched(End.FROM), everyValueMatched(End.TO));
      case PARTIAL_OVERLAP ->
          List.of(someValueUnmatched(End.FROM), someValueUnmatched(End.TO), someValueMatched());
      case DISJOINT -> List.of(noValueMatched());
      default -> List.of();
    };
  }

  /** The value whose claims are these: whether every value of each end is matched, and any is. */
  public static String of(boolean fromContained, boolean toContained, boolean anyMatched) {
    if (fromContained && toContained) {
      return EQUAL;
    }
    if (fromContained) {
      return SUBSET;
    }
    if (toContained) {
      return SUPERSET;
    }
    return anyMatched ? PARTIAL_OVERLAP : DISJOINT;
  }
}
