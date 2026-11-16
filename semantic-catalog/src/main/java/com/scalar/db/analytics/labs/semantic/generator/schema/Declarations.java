/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator.schema;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What a table's metadata declares, read from the source-independent table metadata a connector
 * derived (see {@code docs/physical-catalog.md}, "Table metadata"). Schema interpretation reads
 * only this form, never a connector's own source metadata, so it works for every connector.
 *
 * @param columns the columns, in ordinal order
 * @param columnsComplete whether the columns are known to be all of the table's; when some may be
 *     hidden, a column that is not listed has not disappeared
 * @param constraints the constraints; empty when the table metadata reports them missing or does
 *     not offer them, so nothing is concluded from their absence
 */
record Declarations(
    List<Column> columns, boolean columnsComplete, Optional<List<Constraint>> constraints) {
  Declarations {
    columns = List.copyOf(columns);
    constraints = constraints.map(List::copyOf);
  }

  /** One column. */
  record Column(String name, boolean nullable, Type type) {
    /** The kind of its Analytics type, such as {@code date}; absent unless the type is mapped. */
    @Nullable String analyticsKind() {
      return type instanceof Type.Mapped mapped ? mapped.analyticsKind() : null;
    }

    /**
     * Whether its values are known to compare as the source compares them in a key: a mapped type
     * other than text. Text compares under a collation the table metadata does not state, and an
     * unmapped or unread type could be text.
     */
    boolean comparesExactly() {
      return type instanceof Type.Mapped mapped
          && !"text".equals(mapped.analyticsKind())
          && !"enum".equals(mapped.analyticsKind());
    }
  }

  /** What the table metadata states about a column's type. */
  sealed interface Type {
    /** The type maps to an Analytics type of this kind. */
    record Mapped(String analyticsKind) implements Type {}

    /** The type was read but maps to no Analytics type. */
    record Unmapped() implements Type {}

    /** The type could not be read: a missing item, from which nothing is concluded. */
    record Unread() implements Type {}
  }

  /** One declared constraint. */
  sealed interface Constraint {
    String name();

    boolean enforced();

    record PrimaryKey(String name, boolean enforced, List<String> columns) implements Constraint {}

    /**
     * Uniqueness from a unique constraint or a unique index.
     *
     * @param index whether it is a unique index, which the source always enforces
     * @param partial whether it holds only for rows meeting a condition
     */
    record Unique(
        String name, boolean enforced, List<String> columns, boolean index, boolean partial)
        implements Constraint {}

    /**
     * A foreign key.
     *
     * @param referencedTable the referenced table's name path in the same source
     */
    record ForeignKey(
        String name,
        boolean enforced,
        List<String> columns,
        List<String> referencedTable,
        List<String> referencedColumns)
        implements Constraint {}
  }

  /** Reads the declarations of table metadata in the connectors' JSON form. */
  static Declarations of(JsonNode tableMetadata) {
    var columns = new ArrayList<Column>();
    for (var column : tableMetadata.path("columns")) {
      var kind = column.path("analyticsType").path("kind");
      Type type =
          kind.isTextual()
              ? new Type.Mapped(kind.asText())
              : column.has("unreadReason") ? new Type.Unread() : new Type.Unmapped();
      columns.add(
          new Column(column.path("name").asText(), column.path("nullable").asBoolean(true), type));
    }
    var reported = tableMetadata.path("constraints");
    Optional<List<Constraint>> constraints = Optional.empty();
    if ("value".equals(reported.path("state").asText())) {
      var list = new ArrayList<Constraint>();
      for (var constraint : reported.path("value")) {
        constraint(constraint).ifPresent(list::add);
      }
      constraints = Optional.of(list);
    }
    return new Declarations(
        columns, !tableMetadata.path("columnsMayBeHidden").asBoolean(false), constraints);
  }

  private static Optional<Constraint> constraint(JsonNode node) {
    var name = node.path("name").asText();
    var enforced = node.path("enforced").asBoolean(false);
    var columns = strings(node.path("columns"));
    return switch (node.path("kind").asText()) {
      case "primary-key" -> Optional.of(new Constraint.PrimaryKey(name, enforced, columns));
      case "unique" ->
          Optional.of(
              new Constraint.Unique(
                  name,
                  enforced,
                  columns,
                  "index".equals(node.path("uniqueKind").asText()),
                  node.hasNonNull("condition")));
      case "foreign-key" ->
          Optional.of(
              new Constraint.ForeignKey(
                  name,
                  enforced,
                  columns,
                  strings(node.path("referencedTable")),
                  strings(node.path("referencedColumns"))));
      default -> Optional.empty();
    };
  }

  private static List<String> strings(JsonNode array) {
    var values = new ArrayList<String>();
    array.forEach(value -> values.add(value.asText()));
    return values;
  }

  Optional<Column> column(String name) {
    return columns.stream().filter(column -> column.name().equals(name)).findFirst();
  }
}
