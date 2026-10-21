/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scalar.db.analytics.labs.physical.DataSourceConnection;
import com.scalar.db.analytics.labs.physical.PhysicalCatalog;
import com.scalar.db.analytics.labs.physical.PhysicalTable;
import com.scalar.db.analytics.labs.semantic.model.Json;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Retrieves table metadata for the Semantic Catalog (02-catalog-contract.md chapter 1).
 *
 * <p>The Physical Catalog keeps names and Analytics types only, so on the interim scanner path the
 * application reads the source-side declarations itself: source types, primary keys, unique
 * constraints, and foreign keys, from each source's system catalog, with the credentials the
 * Physical Catalog resolves. A table's resource identifier is {@code source.namespace.table}, the
 * same name SQL uses. The metadata version is the digest of the whole metadata, since the Physical
 * Catalog keeps no versions.
 */
public final class SourceMetadataReader {
  /** The table metadata of one table, as passed to observation registration. */
  public record TableMetadata(String resourceIdentifier, String version, JsonNode metadata) {}

  private SourceMetadataReader() {}

  public static List<TableMetadata> read(
      PhysicalCatalog catalog, String catalogName, Optional<String> onlySource) {
    var result = new ArrayList<TableMetadata>();
    for (var source : catalog.listDataSources(catalogName)) {
      if (onlySource.isPresent() && !onlySource.get().equals(source.name())) {
        continue;
      }
      var tables = catalog.listTables(catalogName, source.name());
      var connection = catalog.resolveConnection(catalogName, source.name());
      Declarations declarations;
      try {
        declarations = connection.isPresent() ? read(connection.get()) : Declarations.EMPTY;
      } catch (SQLException e) {
        declarations = Declarations.EMPTY;
      }
      for (var table : tables) {
        result.add(metadata(source.name(), table, declarations));
      }
    }
    return result;
  }

  private static TableMetadata metadata(
      String source, PhysicalTable table, Declarations declarations) {
    var namespace = String.join(".", table.namespaceNames());
    var key = namespace + "." + table.name();
    var resource = source + "." + key;
    var metadata =
        Json.object()
            .put("resource", resource)
            .put("source", source)
            .put("table", table.name())
            .put("kind", "table");
    metadata.set("namespace", Json.tree(table.namespaceNames()));
    var columns = Json.array();
    var types = declarations.types.getOrDefault(key, Map.of());
    for (var column : table.columns()) {
      columns.add(
          Json.object()
              .put("name", column.name())
              .put("sourceType", types.getOrDefault(column.name(), column.type()))
              .put("analyticsType", column.type())
              .put("nullable", column.nullable())
              .put("ordinal", column.ordinalPosition()));
    }
    metadata.set("columns", columns);
    var primaryKey = declarations.primaryKeys.get(key);
    metadata.set("primaryKey", primaryKey == null ? Json.MAPPER.nullNode() : primaryKey);
    metadata.set("uniqueConstraints", declarations.uniques.getOrDefault(key, Json.array()));
    var foreignKeys = Json.array();
    for (var foreignKey : declarations.foreignKeys.getOrDefault(key, Json.array())) {
      var copy = foreignKey.deepCopy();
      var references = (ObjectNode) copy.path("references");
      references.put(
          "resource",
          source
              + "."
              + references.path("namespace").asText()
              + "."
              + references.path("table").asText());
      foreignKeys.add(copy);
    }
    metadata.set("foreignKeys", foreignKeys);
    metadata.set(
        "complete", Json.object().put("columns", true).put("constraints", declarations.complete));
    return new TableMetadata(resource, Json.digest(metadata).substring(0, 16), metadata);
  }

  /** The declarations of one source, keyed by {@code namespace.table}. */
  private record Declarations(
      Map<String, Map<String, String>> types,
      Map<String, ObjectNode> primaryKeys,
      Map<String, ArrayNode> uniques,
      Map<String, ArrayNode> foreignKeys,
      boolean complete) {
    static final Declarations EMPTY =
        new Declarations(Map.of(), Map.of(), Map.of(), Map.of(), false);
  }

  private static Declarations read(DataSourceConnection connection) throws SQLException {
    return switch (connection) {
      case DataSourceConnection.PostgreSql postgres -> {
        var url =
            "jdbc:postgresql://"
                + postgres.host()
                + ":"
                + postgres.port()
                + "/"
                + postgres.database();
        try (var jdbc =
            DriverManager.getConnection(url, postgres.username(), postgres.password().reveal())) {
          yield postgres(jdbc);
        }
      }
      case DataSourceConnection.MySql mysql -> {
        var url =
            "jdbc:mariadb://"
                + mysql.host()
                + ":"
                + mysql.port()
                + "/"
                + mysql.database().orElse("")
                + "?sslMode=trust";
        try (var jdbc =
            DriverManager.getConnection(url, mysql.username(), mysql.password().reveal())) {
          yield mysql(jdbc, mysql.database());
        }
      }
    };
  }

  private static final String POSTGRES_COLUMNS =
      "SELECT n.nspname, c.relname, a.attname, format_type(a.atttypid, a.atttypmod)"
          + " FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace"
          + " WHERE a.attnum > 0 AND NOT a.attisdropped AND c.relkind IN ('r', 'v', 'p', 'm')"
          + " AND n.nspname NOT IN ('pg_catalog', 'information_schema')";

  // pg_catalog rather than information_schema: information_schema hides the constraints of tables
  // a read-only account cannot write.
  private static final String POSTGRES_KEYS =
      "SELECT n.nspname, c.relname, con.conname, con.contype, a.attname, k.ord"
          + " FROM pg_constraint con JOIN pg_class c ON c.oid = con.conrelid JOIN pg_namespace n ON n.oid = c.relnamespace"
          + " CROSS JOIN LATERAL unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord)"
          + " JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum"
          + " WHERE con.contype IN ('p', 'u') ORDER BY n.nspname, c.relname, con.conname, k.ord";

  private static final String POSTGRES_FOREIGN_KEYS =
      "SELECT n.nspname, c.relname, con.conname, a.attname, k.ord, fn.nspname, fc.relname, fa.attname, con.convalidated"
          + " FROM pg_constraint con JOIN pg_class c ON c.oid = con.conrelid JOIN pg_namespace n ON n.oid = c.relnamespace"
          + " JOIN pg_class fc ON fc.oid = con.confrelid JOIN pg_namespace fn ON fn.oid = fc.relnamespace"
          + " CROSS JOIN LATERAL unnest(con.conkey, con.confkey) WITH ORDINALITY AS k(attnum, fattnum, ord)"
          + " JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum"
          + " JOIN pg_attribute fa ON fa.attrelid = con.confrelid AND fa.attnum = k.fattnum"
          + " WHERE con.contype = 'f' ORDER BY n.nspname, c.relname, con.conname, k.ord";

  private static Declarations postgres(Connection jdbc) throws SQLException {
    var types = new HashMap<String, Map<String, String>>();
    var primaryKeys = new HashMap<String, ObjectNode>();
    var uniques = new HashMap<String, ArrayNode>();
    var foreignKeys = new HashMap<String, ArrayNode>();
    try (var statement = jdbc.createStatement()) {
      var rows = statement.executeQuery(POSTGRES_COLUMNS);
      while (rows.next()) {
        types
            .computeIfAbsent(rows.getString(1) + "." + rows.getString(2), k -> new HashMap<>())
            .put(rows.getString(3), rows.getString(4));
      }
      var keys = new LinkedHashMap<String, ObjectNode>();
      rows = statement.executeQuery(POSTGRES_KEYS);
      while (rows.next()) {
        var table = rows.getString(1) + "." + rows.getString(2);
        var name = rows.getString(3);
        var primary = "p".equals(rows.getString(4));
        var constraint = keys.get(table + "|" + name);
        if (constraint == null) {
          constraint = Json.object().put("name", name).put("enforced", true);
          constraint.set("columns", Json.array());
          keys.put(table + "|" + name, constraint);
          if (primary) {
            primaryKeys.put(table, constraint);
          } else {
            constraint.put("kind", "constraint");
            uniques.computeIfAbsent(table, k -> Json.array()).add(constraint);
          }
        }
        ((ArrayNode) constraint.path("columns")).add(rows.getString(5));
      }
      var fks = new LinkedHashMap<String, ObjectNode>();
      rows = statement.executeQuery(POSTGRES_FOREIGN_KEYS);
      while (rows.next()) {
        var table = rows.getString(1) + "." + rows.getString(2);
        var name = rows.getString(3);
        var enforced = rows.getBoolean(9);
        var referencedNamespace = rows.getString(6);
        var referencedTable = rows.getString(7);
        var foreignKey =
            fks.computeIfAbsent(
                table + "|" + name,
                k -> {
                  var node = Json.object().put("name", name).put("enforced", enforced);
                  node.set("columns", Json.array());
                  var references =
                      Json.object()
                          .put("namespace", referencedNamespace)
                          .put("table", referencedTable);
                  references.set("columns", Json.array());
                  node.set("references", references);
                  foreignKeys.computeIfAbsent(table, t -> Json.array()).add(node);
                  return node;
                });
        ((ArrayNode) foreignKey.path("columns")).add(rows.getString(4));
        ((ArrayNode) foreignKey.path("references").path("columns")).add(rows.getString(8));
      }
    }
    return new Declarations(types, primaryKeys, uniques, foreignKeys, true);
  }

  private static Declarations mysql(Connection jdbc, Optional<String> database)
      throws SQLException {
    var types = new HashMap<String, Map<String, String>>();
    var primaryKeys = new HashMap<String, ObjectNode>();
    var uniques = new HashMap<String, ArrayNode>();
    var foreignKeys = new HashMap<String, ArrayNode>();
    var filter =
        database.isPresent()
            ? " = ?"
            : " NOT IN ('mysql', 'sys', 'performance_schema', 'information_schema')";
    try (var columns =
        jdbc.prepareStatement(
            "SELECT TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA"
                + filter)) {
      if (database.isPresent()) {
        columns.setString(1, database.get());
      }
      var rows = columns.executeQuery();
      while (rows.next()) {
        types
            .computeIfAbsent(rows.getString(1) + "." + rows.getString(2), k -> new HashMap<>())
            .put(rows.getString(3), rows.getString(4));
      }
    }
    try (var constraints =
        jdbc.prepareStatement(
            "SELECT tc.TABLE_SCHEMA, tc.TABLE_NAME, tc.CONSTRAINT_NAME, tc.CONSTRAINT_TYPE, k.COLUMN_NAME,"
                + " k.REFERENCED_TABLE_SCHEMA, k.REFERENCED_TABLE_NAME, k.REFERENCED_COLUMN_NAME"
                + " FROM information_schema.TABLE_CONSTRAINTS tc JOIN information_schema.KEY_COLUMN_USAGE k"
                + " ON tc.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA AND tc.CONSTRAINT_NAME = k.CONSTRAINT_NAME AND tc.TABLE_NAME = k.TABLE_NAME"
                + " WHERE tc.CONSTRAINT_TYPE IN ('PRIMARY KEY', 'UNIQUE', 'FOREIGN KEY') AND tc.TABLE_SCHEMA"
                + filter
                + " ORDER BY tc.TABLE_SCHEMA, tc.TABLE_NAME, tc.CONSTRAINT_NAME, k.ORDINAL_POSITION")) {
      if (database.isPresent()) {
        constraints.setString(1, database.get());
      }
      var rows = constraints.executeQuery();
      var nodes = new LinkedHashMap<String, ObjectNode>();
      while (rows.next()) {
        var table = rows.getString(1) + "." + rows.getString(2);
        var name = rows.getString(3);
        var type = rows.getString(4);
        var node = nodes.get(table + "|" + name);
        if (node == null) {
          node = Json.object().put("name", name).put("enforced", true);
          node.set("columns", Json.array());
          switch (type) {
            case "PRIMARY KEY" -> primaryKeys.put(table, node);
            case "UNIQUE" -> {
              node.put("kind", "constraint");
              uniques.computeIfAbsent(table, k -> Json.array()).add(node);
            }
            default -> {
              var references =
                  Json.object().put("namespace", rows.getString(6)).put("table", rows.getString(7));
              references.set("columns", Json.array());
              node.set("references", references);
              foreignKeys.computeIfAbsent(table, k -> Json.array()).add(node);
            }
          }
          nodes.put(table + "|" + name, node);
        }
        ((ArrayNode) node.path("columns")).add(rows.getString(5));
        if ("FOREIGN KEY".equals(type)) {
          ((ArrayNode) node.path("references").path("columns")).add(rows.getString(8));
        }
      }
    }
    return new Declarations(types, primaryKeys, uniques, foreignKeys, true);
  }
}
