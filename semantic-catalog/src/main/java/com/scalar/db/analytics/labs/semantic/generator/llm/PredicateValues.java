/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.scalar.db.analytics.labs.semantic.generator.SetRelationships;
import com.scalar.db.analytics.labs.semantic.model.IdentityKey;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.projection.ExpressionValues;
import com.scalar.db.analytics.labs.semantic.projection.ExpressionValues.AggregationRule;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The value rules of the predicates a model reads or writes values of (see {@code
 * docs/semantic-catalog/record-model.md}, "Elements and predicates"): the form a value takes, how a
 * value a model gives is read into the value stored and its identity key, and which values name
 * other elements.
 *
 * <p>The model-backed Generators that propose a predicate and correction interpretation, which
 * revises any of them, read values through the same rule, so a value is read alike whichever of
 * them reads it. Each Generator still decides which names a value may use, since only it knows the
 * elements its call passed or created. A key, uniqueness, and a set relationship, which schema
 * interpretation and data checks build without a model, have rules here for correction
 * interpretation; their stored forms are the ones those Generators write.
 */
final class PredicateValues {
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

  static final List<String> ROW_KINDS =
      List.of("current-state", "event", "state-per-point", "pre-aggregated");

  static final List<String> VALUE_KINDS =
      List.of(
          "identifier",
          "classification",
          "amount",
          "quantity",
          "time-or-date",
          "string",
          "boolean");

  static final List<String> SET_RELATIONSHIPS =
      List.of(
          SetRelationships.EQUAL,
          SetRelationships.SUBSET,
          SetRelationships.SUPERSET,
          SetRelationships.PARTIAL_OVERLAP,
          SetRelationships.DISJOINT);

  static final List<String> UNIQUENESS = List.of(Vocabulary.DOES_NOT_REPEAT, Vocabulary.REPEATS);

  private PredicateValues() {}

  /** A predicate value read from what a model gave, or why it is not one. */
  sealed interface Valued {
    /**
     * @param value the value stored; null JSON for a value the commit takes from its one reference
     * @param key the identity key of a closed-range value; absent for a free description
     */
    record Value(JsonNode value, @Nullable IdentityKey key) implements Valued {}

    record Invalid(String why) implements Valued {}
  }

  /** The rule of one predicate. */
  sealed interface Rule {
    /** The form a value takes, as a model is told it. */
    String form();
  }

  /** A value read by itself: a closed choice, a description, or a structured value. */
  record Plain(String form, Function<JsonNode, Valued> reader) implements Rule {
    Valued read(JsonNode given) {
      return reader.apply(given);
    }
  }

  /**
   * A value that names other elements. The reader resolves the names to the elements it may name,
   * then builds the value with {@link #naming} or {@link #key}.
   */
  record Naming(String form, Names names) implements Rule {}

  /**
   * A Measure's expression value: read in its form, then parsed over its base Dataset's Fields and
   * run through the Query Engine before it is recorded.
   */
  record Expressed(String form, Function<JsonNode, Valued> reader) implements Rule {
    Valued read(JsonNode given) {
      return reader.apply(given);
    }
  }

  /** What a naming value names. */
  enum Names {
    /** One Concept. */
    CONCEPT,
    /** A set of Fields of the Dataset the value is about. */
    OWN_FIELDS,
    /** One Dataset. */
    DATASET,
    /** One Field of the base Dataset of the Measure the value is about. */
    BASE_FIELD,
    /** One Measure, Dataset, or Field. */
    ELEMENT
  }

  /** The rule of a predicate of an element kind, if model-backed Generators propose it. */
  static Optional<Rule> of(ElementKind kind, String predicate) {
    return Optional.ofNullable(
        switch (kind) {
          case DATASET ->
              predicate.startsWith(Vocabulary.UNIQUENESS_PREFIX)
                  ? choices(UNIQUENESS)
                  : switch (predicate) {
                    case Vocabulary.ROW_KIND -> choices(ROW_KINDS);
                    case Vocabulary.ROW_GRAIN, Vocabulary.ROW_INCLUSION -> TEXT;
                    case Vocabulary.ROW_CONCEPT -> CONCEPT;
                    case Vocabulary.KEY ->
                        new Naming(
                            "a list of the names of the table's columns that together identify a"
                                + " row, from \"columns\" of the choices",
                            Names.OWN_FIELDS);
                    default -> null;
                  };
          case FIELD ->
              switch (predicate) {
                case Vocabulary.MEANING, Vocabulary.UNIT, Vocabulary.NULL_MEANING -> TEXT;
                case Vocabulary.VALUE_KIND -> choices(VALUE_KINDS);
                case Vocabulary.TIME_MEANING ->
                    new Plain(
                        "an object {\"meaning\": <which moment, as text>, \"timeZone\": <IANA zone"
                            + " or null>}",
                        PredicateValues::timeMeaning);
                case Vocabulary.REPRESENTED_CONCEPT -> CONCEPT;
                default -> null;
              };
          case CONCEPT -> predicate.equals(Vocabulary.MEANING) ? TEXT : null;
          case MEASURE ->
              switch (predicate) {
                case Vocabulary.MEANING -> TEXT;
                case Vocabulary.BASE_DATASET ->
                    new Naming(
                        "the \"ref\" of one of the tables in \"tables\" of the choices",
                        Names.DATASET);
                case Vocabulary.AGGREGATION_RULE ->
                    new Expressed(
                        "an object {\"function\": <one of "
                            + quoted(ExpressionValues.AGGREGATE_FUNCTIONS)
                            + ">, \"expression\": <a DuckDB SQL expression over the base table's"
                            + " columns, or \"*\" with \"count\" to count rows>}",
                        PredicateValues::aggregationRule);
                case Vocabulary.FILTER ->
                    new Expressed(
                        "a DuckDB SQL condition over the base table's columns",
                        PredicateValues::filter);
                case Vocabulary.REFERENCE_TIME_FIELD ->
                    new Naming(
                        "the name of one of the base table's columns, from \"columns\" of the"
                            + " choices",
                        Names.BASE_FIELD);
                default -> null;
              };
          case BUSINESS_TERM ->
              switch (predicate) {
                case Vocabulary.WRITTEN_FORM -> TEXT;
                case Vocabulary.REFERENCED_ELEMENT ->
                    new Naming(
                        "\"measure:<slug>\" for a Measure in \"measures\", a table's \"ref\", or"
                            + " \"<table ref>.<column>\" for a column, from the choices",
                        Names.ELEMENT);
                default -> null;
              };
          case IDENTIFIER_CORRESPONDENCE ->
              switch (predicate) {
                case Vocabulary.SET_RELATIONSHIP -> choices(SET_RELATIONSHIPS);
                case Vocabulary.BUSINESS_DEFINITION -> TEXT;
                default -> null;
              };
          case CONCEPT_RELATIONSHIP ->
              predicate.equals(Vocabulary.MULTIPLICITY)
                  ? new Plain(
                      "an object {\"from\": <bound>, \"to\": <bound>}, each bound one of "
                          + quoted(Vocabulary.MULTIPLICITY_BOUNDS)
                          + ": \"to\" is how many instances of the end the relationship goes to"
                          + " one instance of the end it starts from relates to, and \"from\" the"
                          + " reverse",
                      PredicateValues::multiplicity)
                  : null;
          default -> null;
        });
  }

  /**
   * Why a correction of a predicate is never read as a revised value, for the predicates whose
   * value a correction cannot revise: an element's existence, which a correction rejects or
   * replaces with another element, and a Physical Catalog reference, which follows from what the
   * source declares.
   */
  static Optional<String> unrevisable(String predicate) {
    return switch (predicate) {
      case Vocabulary.EXISTENCE ->
          Optional.of(
              "a correction of an element's existence rejects it or names another element, which"
                  + " a revised value cannot state");
      case Vocabulary.PHYSICAL_CATALOG_REFERENCE ->
          Optional.of("a Physical Catalog reference follows from what the source declares");
      default -> Optional.empty();
    };
  }

  /** The items a check of a predicate's value covers. */
  static JsonNode items(String predicate) {
    return predicate.equals(Vocabulary.AGGREGATION_RULE)
        ? JSON.arrayNode().add("function").add("expression")
        : JSON.textNode(Vocabulary.VALUE_ITEM);
  }

  // Plain values

  private static final Plain TEXT = new Plain("a non-empty text", PredicateValues::text);

  private static final Naming CONCEPT =
      new Naming(
          "the \"ref\" of one of the concepts in \"concepts\" of the choices", Names.CONCEPT);

  private static Plain choices(List<String> choices) {
    return new Plain("one of the texts " + quoted(choices), given -> choice(given, choices));
  }

  private static String quoted(List<String> choices) {
    return String.join(", ", choices.stream().map(choice -> "\"" + choice + "\"").toList());
  }

  /** A description: a non-empty text, stripped. */
  static Valued text(JsonNode given) {
    return given.isTextual() && !given.asText().isBlank()
        ? new Valued.Value(JSON.textNode(given.asText().strip()), null)
        : new Valued.Invalid("it is not a non-empty text");
  }

  /** A closed choice, compared as itself. */
  static Valued choice(JsonNode given, List<String> choices) {
    return given.isTextual() && choices.contains(given.asText())
        ? new Valued.Value(JSON.textNode(given.asText()), IdentityKey.choice(given.asText()))
        : new Valued.Invalid("it is not one of " + String.join(", ", choices));
  }

  /** A Field's time meaning: {@code {"meaning": ..., "timeZone": ...}}, the zone or null. */
  static Valued timeMeaning(JsonNode given) {
    var meaning = given.path("meaning");
    var zone = given.path("timeZone");
    if (!given.isObject() || !meaning.isTextual() || meaning.asText().isBlank()) {
      return new Valued.Invalid("it is not an object with a meaning");
    }
    if (!zone.isNull()
        && !zone.isMissingNode()
        && !(zone.isTextual() && !zone.asText().isBlank())) {
      return new Valued.Invalid("its time zone is neither a name nor null");
    }
    var value = JSON.objectNode().put("meaning", meaning.asText().strip());
    if (zone.isTextual()) {
      value.put("timeZone", zone.asText().strip());
    } else {
      value.putNull("timeZone");
    }
    return new Valued.Value(value, null);
  }

  /** A concept relationship's multiplicity: {@code {"from": <bound>, "to": <bound>}}. */
  static Valued multiplicity(JsonNode given) {
    var from = given.path("from");
    var to = given.path("to");
    if (!given.isObject()
        || !from.isTextual()
        || !to.isTextual()
        || !Vocabulary.MULTIPLICITY_BOUNDS.contains(from.asText())
        || !Vocabulary.MULTIPLICITY_BOUNDS.contains(to.asText())) {
      return new Valued.Invalid(
          "it is not an object with a from and a to bound, each one of "
              + String.join(", ", Vocabulary.MULTIPLICITY_BOUNDS));
    }
    return multiplicity(from.asText(), to.asText());
  }

  /** A multiplicity from its bounds, compared by both. */
  static Valued.Value multiplicity(String from, String to) {
    return new Valued.Value(
        JSON.objectNode().put("from", from).put("to", to),
        IdentityKey.of(new IdentityKey.Part.Choice(from), new IdentityKey.Part.Choice(to)));
  }

  // Expression values

  /** A Measure's aggregation rule, {@code {"function": ..., "expression": ...}}, in its form. */
  static Valued aggregationRule(JsonNode given) {
    try {
      var rule = AggregationRule.of(given);
      return new Valued.Value(
          JSON.objectNode().put("function", rule.function()).put("expression", rule.expression()),
          null);
    } catch (IllegalArgumentException e) {
      return new Valued.Invalid(String.valueOf(e.getMessage()));
    }
  }

  /** A Measure's filter conditions: one SQL condition in text. */
  static Valued filter(JsonNode given) {
    try {
      ExpressionValues.of(Vocabulary.FILTER, given);
      return new Valued.Value(JSON.textNode(given.asText()), null);
    } catch (IllegalArgumentException e) {
      return new Valued.Invalid(String.valueOf(e.getMessage()));
    }
  }

  // Naming values

  /**
   * The value of a predicate that names one element, compared as that reference. The Concept a row
   * or a Field represents and a business term's referenced element take no value of their own: the
   * commit writes the URN the reference resolves to, so they can name an element proposed in the
   * same request. A Measure's base Dataset and reference time Field hold the element's URN.
   */
  static Valued.Value naming(String predicate, Ref element) {
    var key = IdentityKey.of(new IdentityKey.Part.Reference(element));
    return switch (predicate) {
      case Vocabulary.BASE_DATASET, Vocabulary.REFERENCE_TIME_FIELD ->
          new Valued.Value(JSON.textNode(((Ref.Urn) element).value()), key);
      default -> new Valued.Value(JSON.nullNode(), key);
    };
  }

  /** A key: {@code {"fields": [...]}}, the URNs of its Fields sorted, compared as a set. */
  static Valued.Value key(List<String> fields) {
    var value = JSON.objectNode();
    var urns = value.putArray("fields");
    fields.stream().sorted().distinct().forEach(urns::add);
    return new Valued.Value(
        value,
        IdentityKey.of(new IdentityKey.Part.References(fields.stream().map(Ref::urn).toList())));
  }
}
