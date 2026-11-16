/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.construction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scalar.db.analytics.labs.semantic.ConstructionSql.ConstructionSqlException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * The table references of one statement, read from DuckDB's serialized parse tree ({@code
 * json_serialize_sql}). The tree holds every reference, including those in subqueries and common
 * table expressions, so a check over it sees everything the statement reads.
 *
 * <p>A table reference in the tree is an object with a {@code type} and no {@code class}, which
 * only expressions have. Base tables name their catalog, schema, and table; a reference without a
 * catalog and schema may name a common table expression. Common table expression names are resolved
 * by scope, as DuckDB binds them: a query node's expressions are visible in that node, including
 * its subqueries, and each one's body sees those defined before it and itself. A reference outside
 * that scope is not one, even when another part of the statement defines the name, because DuckDB
 * then binds it to a relation of the same name, such as a system view.
 */
final class StatementTables {
  private final JsonNode tree;
  private final List<ObjectNode> baseTables = new ArrayList<>();
  private final Set<ObjectNode> commonTableExpressionReferences =
      Collections.newSetFromMap(new IdentityHashMap<>());

  private StatementTables(JsonNode tree) {
    this.tree = tree;
  }

  /**
   * Reads a serialized parse tree.
   *
   * @throws ConstructionSqlException when the statement did not parse, is not exactly one
   *     statement, or reads through a table function
   */
  static StatementTables of(JsonNode tree) {
    if (tree.path("error").asBoolean(true)) {
      throw new ConstructionSqlException(
          "The statement does not parse: " + tree.path("error_message").asText("unknown error"));
    }
    var statements = tree.path("statements");
    if (!statements.isArray() || statements.size() != 1) {
      throw new ConstructionSqlException("A construction query is exactly one SELECT statement.");
    }
    var tables = new StatementTables(tree);
    tables.collect(statements.get(0), Set.of());
    return tables;
  }

  /** The whole tree, whose base-table references may be changed before it is turned back to SQL. */
  JsonNode tree() {
    return tree;
  }

  /** Every base-table reference, as the mutable nodes of the tree. */
  List<ObjectNode> baseTables() {
    return baseTables;
  }

  /** Whether a reference names a common table expression visible where it stands. */
  boolean isCommonTableExpression(ObjectNode reference) {
    return commonTableExpressionReferences.contains(reference);
  }

  static String catalog(ObjectNode reference) {
    return reference.path("catalog_name").asText("");
  }

  static String schema(ObjectNode reference) {
    return reference.path("schema_name").asText("");
  }

  static String table(ObjectNode reference) {
    return reference.path("table_name").asText("");
  }

  /**
   * Collects the table references under a node.
   *
   * @param visible the common table expression names in scope at the node
   */
  private void collect(JsonNode node, Set<String> visible) {
    if (node.isArray()) {
      node.forEach(element -> collect(element, visible));
      return;
    }
    if (!node.isObject()) {
      return;
    }
    var object = (ObjectNode) node;
    if (object.has("type") && !object.has("class")) {
      switch (object.get("type").asText()) {
        case "BASE_TABLE" -> {
          baseTables.add(object);
          if (catalog(object).isEmpty()
              && schema(object).isEmpty()
              && visible.contains(table(object))) {
            commonTableExpressionReferences.add(object);
          }
        }
        case "TABLE_FUNCTION", "SHOW_REF" ->
            throw new ConstructionSqlException(
                "A construction query reads only its declared tables, not table functions.");
        default -> {}
      }
    }
    var inner = visible;
    var map = object.path("cte_map").path("map");
    if (map.isArray()) {
      // Each body sees the outer names, the expressions defined before it, and itself.
      var defined = new HashSet<>(visible);
      for (var entry : map) {
        defined.add(entry.path("key").asText());
        collect(entry.path("value"), Set.copyOf(defined));
      }
      inner = Set.copyOf(defined);
    }
    for (var property : object.properties()) {
      if (!property.getKey().equals("cte_map")) {
        collect(property.getValue(), inner);
      }
    }
  }
}
