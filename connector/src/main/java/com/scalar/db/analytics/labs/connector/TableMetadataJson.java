/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The JSON form of the table metadata and retrieval information a Connector returned, for receivers
 * that keep it as a document, such as the Semantic Catalog.
 *
 * <p>The form holds every item of the connector API structures and adds nothing. What the Connector
 * derived for a column, its Analytics type or the reason it has none, is the column's {@code
 * analyticsType} member, so a receiver can separate it from what the source declares.
 */
public final class TableMetadataJson {
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

  private TableMetadataJson() {}

  public static ObjectNode of(TableMetadata metadata) {
    var node = JSON.objectNode();
    node.set("table", strings(metadata.table()));
    switch (metadata.kind()) {
      case TableKind.Table ignored -> node.put("kind", "table");
      case TableKind.View ignored -> node.put("kind", "view");
      case TableKind.External ignored -> node.put("kind", "external");
      case TableKind.Other other -> {
        node.put("kind", "other");
        node.put("otherKind", other.name());
      }
    }
    node.set("sourceId", reported(metadata.sourceId(), JSON::textNode));
    var columns = node.putArray("columns");
    for (var column : metadata.columns()) {
      columns.add(column(column));
    }
    node.put("columnsMayBeHidden", metadata.columnsMayBeHidden());
    node.set("constraints", reported(metadata.constraints(), TableMetadataJson::constraints));
    return node;
  }

  public static ObjectNode of(Retrieval retrieval) {
    var node = JSON.objectNode();
    node.set("connector", component(retrieval.connector()));
    retrieval.adbcDriver().ifPresent(driver -> node.set("adbcDriver", component(driver)));
    node.put("libraryVersion", retrieval.libraryVersion());
    return node;
  }

  public static ObjectNode of(AnalyticsType type) {
    var node = JSON.objectNode();
    switch (type) {
      case AnalyticsType.Scalar scalar -> node.put("kind", name(scalar.kind()));
      case AnalyticsType.Decimal decimal -> {
        node.put("kind", "decimal");
        node.put("precision", decimal.precision());
        node.put("scale", decimal.scale());
      }
      case AnalyticsType.Array array -> {
        node.put("kind", "array");
        node.set("element", of(array.element()));
        node.put("elementNullable", array.elementNullable());
      }
      case AnalyticsType.Map map -> {
        node.put("kind", "map");
        node.set("key", of(map.key()));
        node.set("value", of(map.value()));
        node.put("valueNullable", map.valueNullable());
      }
      case AnalyticsType.Struct struct -> {
        node.put("kind", "struct");
        var fields = node.putArray("fields");
        for (var field : struct.fields()) {
          var entry = fields.addObject();
          entry.put("name", field.name());
          entry.set("type", of(field.type()));
          entry.put("nullable", field.nullable());
        }
      }
      case AnalyticsType.Enum enumeration -> {
        node.put("kind", "enum");
        node.set("members", strings(enumeration.members()));
      }
    }
    return node;
  }

  private static ObjectNode column(Column column) {
    var node = JSON.objectNode();
    node.put("name", column.name());
    node.put("ordinal", column.ordinal());
    node.put("nullable", column.nullable());
    node.set("sourceId", reported(column.sourceId(), JSON::textNode));
    switch (column.type()) {
      case Column.Type.Mapped mapped -> {
        node.put("dataSourceType", mapped.dataSourceType());
        node.set("analyticsType", of(mapped.analyticsType()));
      }
      case Column.Type.Unsupported unsupported -> {
        node.put("dataSourceType", unsupported.dataSourceType());
        node.set("analyticsType", JSON.objectNode().put("unsupported", name(unsupported.reason())));
      }
      case Column.Type.Unread unread -> node.put("unreadReason", unread.reason());
    }
    return node;
  }

  private static ArrayNode constraints(List<Constraint> constraints) {
    var array = JSON.arrayNode();
    for (var constraint : constraints) {
      var node = array.addObject();
      node.put("name", constraint.name());
      node.put("enforced", constraint.enforced());
      switch (constraint) {
        case Constraint.PrimaryKey key -> {
          node.put("kind", "primary-key");
          node.set("columns", strings(key.columns()));
        }
        case Constraint.Unique unique -> {
          node.put("kind", "unique");
          node.set("columns", strings(unique.columns()));
          node.put("uniqueKind", name(unique.uniqueKind()));
          unique.condition().ifPresent(condition -> node.put("condition", condition));
        }
        case Constraint.ForeignKey key -> {
          node.put("kind", "foreign-key");
          node.set("columns", strings(key.columns()));
          node.set("referencedTable", strings(key.referencedTable()));
          node.set("referencedColumns", strings(key.referencedColumns()));
        }
        case Constraint.Check check -> {
          node.put("kind", "check");
          node.put("expression", check.expression());
        }
      }
    }
    return array;
  }

  private static <T> ObjectNode reported(Reported<T> reported, Function<T, JsonNode> value) {
    var node = JSON.objectNode();
    switch (reported) {
      case Reported.Value<T> present -> {
        node.put("state", "value");
        node.set("value", value.apply(present.value()));
      }
      case Reported.Missing<T> missing -> {
        node.put("state", "missing");
        node.put("reason", missing.reason());
      }
      case Reported.NotOffered<T> ignored -> node.put("state", "not-offered");
    }
    return node;
  }

  private static ObjectNode component(Retrieval.Component component) {
    return JSON.objectNode().put("name", component.name()).put("version", component.version());
  }

  private static ArrayNode strings(List<String> values) {
    var array = JSON.arrayNode();
    values.forEach(array::add);
    return array;
  }

  /** The kebab-case name of a constant, such as {@code small-int} for SMALL_INT. */
  private static String name(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
