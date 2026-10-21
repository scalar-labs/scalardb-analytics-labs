/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/** Predicate names, and the validation items each predicate is examined by. */
public final class Predicates {
  public static final String EXISTS = "exists";
  public static final String TECHNICAL_REFERENCE = "technical-reference";
  public static final String ROW_CONCEPT = "row-concept";
  public static final String ROW_KIND = "row-kind";
  public static final String ROW_GRAIN = "row-grain";
  public static final String ROW_INCLUSION = "row-inclusion";
  public static final String KEY = "key";
  public static final String UNIQUENESS_PREFIX = "uniqueness:";
  public static final String MEANING = "meaning";
  public static final String VALUE_KIND = "value-kind";
  public static final String UNIT = "unit";
  public static final String NULL_MEANING = "null-meaning";
  public static final String REPRESENTED_CONCEPT = "represented-concept";
  public static final String TIME_MEANING = "time-meaning";
  public static final String BASE_DATASET = "base-dataset";
  public static final String AGGREGATION_RULE = "aggregation-rule";
  public static final String FILTER = "filter";
  public static final String REFERENCE_TIME_FIELD = "reference-time-field";
  public static final String DEFAULT_TIME_GRANULARITY = "default-time-granularity";
  public static final String DIMENSION_KIND = "dimension-kind";
  public static final String CLASSIFICATION = "classification";
  public static final String SOURCE_TIME_FIELD = "source-time-field";
  public static final String HIERARCHY = "hierarchy";
  public static final String CALENDAR_RULE = "calendar-rule";
  public static final String WRITTEN_FORM = "written-form";
  public static final String REFERENCED_ELEMENT = "referenced-element";
  public static final String MULTIPLICITY = "multiplicity";
  public static final String PARTICIPATION_FROM = "participation-from";
  public static final String PARTICIPATION_TO = "participation-to";
  public static final String SET_RELATIONSHIP = "set-relationship";
  public static final String BUSINESS_DEFINITION = "business-definition";
  public static final String EXTENSION_PREFIX = "x:";

  /** The single validation item of predicates whose value is not made of parts. */
  public static final String VALUE_ITEM = "value";

  private Predicates() {}

  /** The uniqueness predicate of a set of columns, which holds the set as part of the predicate. */
  public static String uniqueness(List<String> columns) {
    return UNIQUENESS_PREFIX + String.join(",", columns.stream().sorted().toList());
  }

  public static boolean isUniqueness(String predicate) {
    return predicate.startsWith(UNIQUENESS_PREFIX);
  }

  public static boolean isExtension(String predicate) {
    return predicate.startsWith(EXTENSION_PREFIX) && predicate.length() > EXTENSION_PREFIX.length();
  }

  /**
   * The validation items of a Fact: the corresponding Field pairs of a join's existence, the steps
   * of an identifier correspondence, the parts of an aggregation rule, and otherwise the value.
   */
  public static List<String> validationItems(
      ElementKind kind, String predicate, JsonNode identifying) {
    if (predicate.equals(EXISTS) && kind == ElementKind.JOIN) {
      var items = new ArrayList<String>();
      identifying.path("on").forEach(pair -> items.add(joinPairItem(pair)));
      return items.isEmpty() ? List.of(VALUE_ITEM) : items;
    }
    if (predicate.equals(EXISTS) && kind == ElementKind.IDENTIFIER_CORRESPONDENCE) {
      var steps = identifying.path("steps");
      if (steps.isArray() && steps.size() > 1) {
        var items = new ArrayList<String>();
        for (var i = 0; i < steps.size(); i++) {
          items.add("step:" + i);
        }
        return items;
      }
      return List.of(VALUE_ITEM);
    }
    if (predicate.equals(AGGREGATION_RULE)) {
      return List.of("function", "expression");
    }
    return List.of(VALUE_ITEM);
  }

  /** The validation item of one corresponding pair in a join condition. */
  public static String joinPairItem(JsonNode pair) {
    return "pair:" + pair.path(0).asText() + "=" + pair.path(1).asText();
  }
}
