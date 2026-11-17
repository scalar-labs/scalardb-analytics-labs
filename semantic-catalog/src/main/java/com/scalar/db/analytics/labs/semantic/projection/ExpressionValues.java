/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Expression values: the value expression of a Measure's aggregation rule and a Measure's filter
 * conditions, SQL in the Query Engine's dialect over the column names of the base Dataset's table
 * (see {@code docs/semantic-catalog/record-model.md}, "Elements and predicates", and {@code
 * docs/design/semantic-catalog-expression-values.md}).
 *
 * <p>An expression is parsed, never bound or run, by placing it in a statement over a stand-in
 * table and reading the Query Engine's serialized parse tree. Only an expression that the statement
 * holds whole, as one select item or as the whole WHERE condition over the stand-in table alone, is
 * one: text that ends the expression early and adds a clause, another table, or a subquery changes
 * the tree's shape and is refused. Each column it references must be named by its column name
 * alone.
 */
public final class ExpressionValues {
  /** The aggregate functions of an aggregation rule, the closed list it chooses from. */
  public static final List<String> AGGREGATE_FUNCTIONS =
      List.of("sum", "count", "count-distinct", "avg", "min", "max");

  /** The value expression of a rule that counts rows, which only {@code count} takes. */
  public static final String ROWS = "*";

  /** The stand-in table an expression is parsed over. */
  private static final String TABLE = "expression_values_table";

  /**
   * The names the Query Engine's parser reads as a column but its binder resolves to a value of the
   * session, such as {@code current_date}, when the table has no column of that name.
   */
  private static final Set<String> SESSION_VALUES =
      Set.of("current_date", "current_time", "current_timestamp", "localtime", "localtimestamp");

  /**
   * Whether a column an expression references may be a value of the session instead, such as {@code
   * current_date}: it is one when the table has no column of that name, as the Query Engine binds
   * it. Callers that know the table's Fields read such a name as a column only when one of them has
   * it.
   */
  public static boolean sessionValue(String column) {
    return SESSION_VALUES.contains(column.toLowerCase(Locale.ROOT));
  }

  private final Function<String, JsonNode> parser;

  /**
   * @param parser the Query Engine's parser: one SQL text to its serialized parse tree
   */
  public ExpressionValues(Function<String, JsonNode> parser) {
    this.parser = Objects.requireNonNull(parser, "parser");
  }

  /** Where an expression stands in a query. */
  public enum Form {
    /** A value computed per row, such as an aggregation rule's value expression. */
    VALUE,
    /** A condition a row meets, such as a Measure's filter conditions. */
    CONDITION
  }

  /** One expression a value holds. */
  public record Expression(Form form, String text) {
    public Expression {
      Objects.requireNonNull(form, "form");
      Objects.requireNonNull(text, "text");
    }
  }

  /**
   * An aggregation rule's value: its aggregate function and its value expression.
   *
   * @throws IllegalArgumentException when the value is not in that form
   */
  public record AggregationRule(String function, String expression) {
    public AggregationRule {
      if (!AGGREGATE_FUNCTIONS.contains(function)) {
        throw new IllegalArgumentException(
            "An aggregate function is one of "
                + String.join(", ", AGGREGATE_FUNCTIONS)
                + ", not "
                + function);
      }
      if (expression.isBlank()) {
        throw new IllegalArgumentException("An aggregation rule has a value expression");
      }
      if (countsRows(expression) && !function.equals("count")) {
        throw new IllegalArgumentException("Only count takes * as its value expression");
      }
    }

    /** Whether the rule counts rows: {@code count} over {@code *}, which references no column. */
    public boolean countsRows() {
      return countsRows(expression);
    }

    private static boolean countsRows(String expression) {
      return expression.strip().equals(ROWS);
    }

    /** Reads an aggregation rule's stored value, {@code {"function": ..., "expression": ...}}. */
    public static AggregationRule of(JsonNode value) {
      var function = value.path("function");
      var expression = value.path("expression");
      if (!value.isObject() || !function.isTextual() || !expression.isTextual()) {
        throw new IllegalArgumentException(
            "An aggregation rule is an object with a function and an expression");
      }
      return new AggregationRule(function.asText(), expression.asText());
    }

    /** The aggregate as the Query Engine runs it, such as {@code count(DISTINCT customer_id)}. */
    public String sql() {
      return function.equals("count-distinct")
          ? "count(DISTINCT " + expression + ")"
          : function + "(" + expression + ")";
    }
  }

  /**
   * Whether a Measure's predicate holds expressions: its aggregation rule and its filter
   * conditions.
   */
  public static boolean holdsExpressions(String predicate) {
    return predicate.equals(Vocabulary.AGGREGATION_RULE) || predicate.equals(Vocabulary.FILTER);
  }

  /**
   * The expressions a Measure's value at a predicate holds: an aggregation rule's value expression
   * and the text of filter conditions; none at any other predicate.
   *
   * @throws IllegalArgumentException when the value is not in the predicate's form
   */
  public static List<Expression> of(String predicate, JsonNode value) {
    if (predicate.equals(Vocabulary.AGGREGATION_RULE)) {
      var rule = AggregationRule.of(value);
      return rule.countsRows() ? List.of() : List.of(new Expression(Form.VALUE, rule.expression()));
    }
    if (predicate.equals(Vocabulary.FILTER)) {
      if (!value.isTextual() || value.asText().isBlank()) {
        throw new IllegalArgumentException("Filter conditions are a non-empty SQL condition");
      }
      return List.of(new Expression(Form.CONDITION, value.asText()));
    }
    return List.of();
  }

  /**
   * The columns an expression references, each once, in the order first referenced. A name that may
   * be a value of the session instead is among them (see {@link #sessionValue}).
   *
   * @throws IllegalArgumentException when it does not parse, is not one whole expression over its
   *     table, holds a subquery or a star, or names a column with a qualifier
   */
  public List<String> columns(Expression expression) {
    var columns = new LinkedHashSet<String>();
    collect(expression, held(expression), columns);
    return List.copyOf(columns);
  }

  /**
   * Whether an expression is only a reference to one column, such as {@code prefecture}, which
   * computes nothing from it.
   *
   * @throws IllegalArgumentException when it is not one expression, as {@link #columns} refuses
   */
  public boolean onlyColumn(Expression expression) {
    var held = held(expression);
    var columns = new LinkedHashSet<String>();
    collect(expression, held, columns);
    return held.path("class").asText("").equals("COLUMN_REF")
        && columns.stream().noneMatch(ExpressionValues::sessionValue);
  }

  /** The node of the parse tree that holds the expression whole. */
  private JsonNode held(Expression expression) {
    var tree =
        parser.apply(
            switch (expression.form()) {
              case VALUE -> "SELECT " + expression.text() + "\nFROM " + TABLE;
              case CONDITION -> "SELECT 1 FROM " + TABLE + " WHERE " + expression.text() + "\n";
            });
    if (tree.path("error").asBoolean(true)) {
      throw new IllegalArgumentException(
          "The expression "
              + expression.text()
              + " does not parse: "
              + tree.path("error_message").asText("unknown error"));
    }
    var statements = tree.path("statements");
    var node = statements.path(0).path("node");
    if (statements.size() != 1 || !node.path("type").asText().equals("SELECT_NODE")) {
      throw notOneExpression(expression);
    }
    var from = node.path("from_table");
    var selectList = node.path("select_list");
    if (!from.path("type").asText().equals("BASE_TABLE")
        || !from.path("table_name").asText().equals(TABLE)
        || !from.path("schema_name").asText("").isEmpty()
        || !from.path("catalog_name").asText("").isEmpty()
        || !absent(from.path("sample"))
        || selectList.size() != 1
        || !empty(node.path("modifiers"))
        || !empty(node.path("group_expressions"))
        || !empty(node.path("group_sets"))
        || !absent(node.path("having"))
        || !absent(node.path("qualify"))
        || !absent(node.path("sample"))
        || !empty(node.path("cte_map").path("map"))) {
      throw notOneExpression(expression);
    }
    var where = node.path("where_clause");
    return switch (expression.form()) {
      case VALUE -> {
        if (!absent(where)) {
          throw notOneExpression(expression);
        }
        yield selectList.get(0);
      }
      case CONDITION -> {
        if (absent(where) || !selectList.get(0).path("class").asText().equals("CONSTANT")) {
          throw notOneExpression(expression);
        }
        yield where;
      }
    };
  }

  private static void collect(Expression expression, JsonNode node, LinkedHashSet<String> columns) {
    if (node.isObject()) {
      var kind = node.path("class").asText("");
      switch (kind) {
        case "SUBQUERY" ->
            throw new IllegalArgumentException(
                "The expression " + expression.text() + " holds a subquery");
        case "STAR" ->
            throw new IllegalArgumentException(
                "The expression " + expression.text() + " references every column with a star");
        case "COLUMN_REF" -> {
          var names = node.path("column_names");
          if (names.size() != 1 || !names.get(0).isTextual()) {
            throw new IllegalArgumentException(
                "The expression "
                    + expression.text()
                    + " names a column with a qualifier; it names columns by their names alone");
          }
          columns.add(names.get(0).asText());
          return;
        }
        default -> {}
      }
    }
    var children = new ArrayList<JsonNode>();
    node.forEach(children::add);
    for (var child : children) {
      collect(expression, child, columns);
    }
  }

  private static boolean absent(JsonNode node) {
    return node.isMissingNode() || node.isNull();
  }

  private static boolean empty(JsonNode node) {
    return absent(node) || node.isEmpty();
  }

  private static IllegalArgumentException notOneExpression(Expression expression) {
    return new IllegalArgumentException(
        "The text "
            + expression.text()
            + " is not one "
            + switch (expression.form()) {
              case VALUE -> "value expression";
              case CONDITION -> "condition";
            }
            + " over the base Dataset's table");
  }
}
