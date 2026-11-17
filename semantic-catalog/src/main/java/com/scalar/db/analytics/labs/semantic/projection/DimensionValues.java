/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.scalar.db.analytics.labs.semantic.model.IdentityKey;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.DimensionKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/**
 * The value forms of a Dimension's predicates and of a Measure's default time granularity (see
 * {@code docs/semantic-catalog/record-model.md}, "Elements and predicates").
 *
 * <p>A calendar's rule is the wording that defines it, because real calendars, such as months
 * closing on the 20th, boundaries moved to the next business day, or 4-4-5 retail calendars, do not
 * fit one closed structure. Its hierarchy names its levels coarse to fine, each with an optional
 * SQL expression that computes the level's period from a time value, written only when the rule
 * fits one expression. A Measure's default time granularity refers to a calendar by URN, which is
 * what cascade follows, and names the level in words.
 */
public final class DimensionValues {
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

  /**
   * The column a level's expression reads: the time a Measure's reference time Field records, in
   * that Field's time zone.
   */
  public static final String TIME_VALUE = "time_value";

  private DimensionValues() {}

  /**
   * Reads a calendar rule's value: the wording that defines the calendar.
   *
   * @throws IllegalArgumentException when the value is not a non-empty text
   */
  public static String calendarRule(JsonNode value) {
    if (!value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException(
          "A calendar rule is the non-empty wording that defines it");
    }
    return value.asText();
  }

  /**
   * One level of a hierarchy.
   *
   * @param expression a SQL value expression over {@link #TIME_VALUE} that gives the key of the
   *     level's period a time falls in; empty when the rule does not fit one expression
   */
  public record Level(String name, Optional<String> expression) {
    public Level {
      if (name.isBlank()) {
        throw new IllegalArgumentException("A level has a name");
      }
      if (expression.isPresent() && expression.get().isBlank()) {
        throw new IllegalArgumentException("A level's expression is not empty");
      }
    }

    /** The level's expression in the form expression values are parsed in. */
    public Optional<ExpressionValues.Expression> parsed() {
      return expression.map(
          text -> new ExpressionValues.Expression(ExpressionValues.Form.VALUE, text));
    }
  }

  /**
   * A Dimension's hierarchy: {@code {"levels": [{"name": ..., "expression": ...}, ...]}}, its
   * levels coarse to fine, each name once.
   */
  public record Hierarchy(List<Level> levels) {
    public Hierarchy {
      levels = List.copyOf(levels);
      if (levels.isEmpty()) {
        throw new IllegalArgumentException("A hierarchy has at least one level");
      }
      var names = new HashSet<String>();
      for (var level : levels) {
        if (!names.add(level.name())) {
          throw new IllegalArgumentException("A hierarchy names level " + level.name() + " twice");
        }
      }
    }

    /**
     * Reads a hierarchy's value.
     *
     * @throws IllegalArgumentException when the value is not in this form
     */
    public static Hierarchy of(JsonNode value) {
      var list = value.path("levels");
      if (!value.isObject() || value.size() != 1 || !list.isArray()) {
        throw new IllegalArgumentException("A hierarchy is {\"levels\": [...]}");
      }
      var levels = new ArrayList<Level>();
      for (var item : list) {
        var name = item.path("name");
        var expression = item.path("expression");
        var known = item.has("expression") ? 2 : 1;
        if (!item.isObject()
            || item.size() != known
            || !name.isTextual()
            || !(expression.isMissingNode() || expression.isTextual())) {
          throw new IllegalArgumentException(
              "A hierarchy's level is {\"name\": <text>} with an optional \"expression\": <text>");
        }
        levels.add(
            new Level(
                name.asText(),
                expression.isTextual() ? Optional.of(expression.asText()) : Optional.empty()));
      }
      return new Hierarchy(levels);
    }

    public JsonNode value() {
      var node = JSON.objectNode();
      var list = node.putArray("levels");
      for (var level : levels) {
        var item = list.addObject().put("name", level.name());
        level.expression().ifPresent(text -> item.put("expression", text));
      }
      return node;
    }
  }

  /**
   * A Measure's default time granularity: {@code {"dimension": <Dimension URN>, "level": <name>}},
   * a level of a calendar named in words.
   */
  public record Granularity(String dimension, String level) {
    public Granularity {
      if (level.isBlank()) {
        throw new IllegalArgumentException("A default time granularity names its level");
      }
    }

    /**
     * Reads a default time granularity's value.
     *
     * @throws IllegalArgumentException when the value is not in this form
     */
    public static Granularity of(JsonNode value) {
      var dimension = value.path("dimension");
      var level = value.path("level");
      if (!value.isObject() || value.size() != 2 || !dimension.isTextual() || !level.isTextual()) {
        throw new IllegalArgumentException(
            "A default time granularity is {\"dimension\": <Dimension URN>, \"level\": <name>}");
      }
      return new Granularity(dimension.asText(), level.asText());
    }

    public JsonNode value() {
      return JSON.objectNode().put("dimension", dimension).put("level", level);
    }

    /**
     * The identity key of a granularity at a level of a Dimension, which may be a record of the
     * same request: a granularity proposed with a null value takes its value from this key once the
     * reference is resolved (see {@link #fromKey}).
     */
    public static IdentityKey identityKey(Ref dimension, String level) {
      return IdentityKey.of(
          new IdentityKey.Part.Reference(dimension), new IdentityKey.Part.Choice(level));
    }

    /**
     * The granularity an identity key of {@link #identityKey(Ref, String)} with its reference
     * resolved to a URN states.
     *
     * @throws IllegalArgumentException when the key is not in that form
     */
    public static Granularity fromKey(IdentityKey key) {
      var parts = key.parts();
      if (parts.size() != 2
          || !(parts.get(0) instanceof IdentityKey.Part.Reference dimension)
          || !(parts.get(1) instanceof IdentityKey.Part.Choice level)) {
        throw new IllegalArgumentException(
            "A default time granularity's identity key is its Dimension and its level");
      }
      return new Granularity(dimension.record().urnValue(), level.value());
    }
  }

  /**
   * Checks the value of a predicate of a Dimension of a kind, one the kind defines or the hierarchy
   * every Dimension has. A calendar's level expressions are parsed, and may read only {@link
   * #TIME_VALUE}.
   *
   * @throws IllegalArgumentException when the predicate is not one of the kind's or the value is
   *     not in the kind's form
   */
  public static void check(
      DimensionKind kind, String predicate, JsonNode value, ExpressionValues expressions) {
    // A switch expression, so a new kind does not compile until its forms are checked here.
    Runnable checked =
        switch (kind) {
          case CALENDAR ->
              switch (predicate) {
                case Vocabulary.CALENDAR_RULE -> () -> calendarRule(value);
                case Vocabulary.HIERARCHY -> () -> checkLevels(Hierarchy.of(value), expressions);
                default ->
                    throw new IllegalArgumentException(
                        "A " + kind.value() + " Dimension has no predicate " + predicate);
              };
        };
    checked.run();
  }

  /**
   * Parses each level expression of a calendar's hierarchy and requires it to read only {@link
   * #TIME_VALUE}.
   *
   * @throws IllegalArgumentException when an expression does not parse, is not one expression, or
   *     reads another column
   */
  public static void checkLevels(Hierarchy hierarchy, ExpressionValues expressions) {
    for (var level : hierarchy.levels()) {
      var parsed = level.parsed();
      if (parsed.isEmpty()) {
        continue;
      }
      for (var column : expressions.columns(parsed.get())) {
        if (!column.equals(TIME_VALUE)) {
          throw new IllegalArgumentException(
              "The expression of level "
                  + level.name()
                  + " reads "
                  + column
                  + "; a calendar's level reads only "
                  + TIME_VALUE);
        }
      }
    }
  }

  /**
   * Whether a predicate of a Dimension depends on its kind: the hierarchy every Dimension has, and
   * the predicates some kind defines. Such a value is read in the form of the kind its Dimension's
   * dimension-kind Fact states, so its acceptance depends on that Fact.
   */
  public static boolean kindSpecific(String predicate) {
    if (predicate.equals(Vocabulary.HIERARCHY)) {
      return true;
    }
    for (var kind : DimensionKind.values()) {
      if (kind.predicates().contains(predicate)) {
        return true;
      }
    }
    return false;
  }
}
