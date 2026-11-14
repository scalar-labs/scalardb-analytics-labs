/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scalar.db.analytics.labs.semantic.model.LogRecordJson;
import java.time.Instant;

/**
 * One retrieved table's metadata, as the application passes it to {@link
 * SemanticCatalogStore#registerTableMetadata} (see {@code docs/semantic-catalog/public-api.md},
 * "Registering an observation").
 *
 * <p>The metadata is passed whole, exactly as the connector returned it. The Semantic Catalog
 * splits it: the source-declared part becomes an Input Observation of the table's physical-schema
 * Input Resource, and the connector-derived part becomes a derived record of the version.
 *
 * @param tableIdentifier the Physical Catalog's identifier of the table; opaque to the Semantic
 *     Catalog, which never parses it
 * @param version the table metadata version, increasing per table; a version no newer than one
 *     already received records nothing
 * @param table what the retrieval found for the table
 * @param retrieval the retrieval information: which connector, connector version, library version,
 *     and ADBC driver read the table; connector-derived
 * @param contentDigest the content digest of the version; connector-derived
 */
public record TableMetadataObservation(
    String tableIdentifier,
    long version,
    TableRecord table,
    JsonNode retrieval,
    String contentDigest) {
  /**
   * The member of a column in the table metadata that holds what the connector derived for it: its
   * Analytics type, or why it has none. A column whose type was not read has no such member.
   */
  public static final String ANALYTICS_TYPE = "analyticsType";

  public TableMetadataObservation {
    if (tableIdentifier.isBlank()) {
      throw new IllegalArgumentException("A table identifier is not blank");
    }
    if (!retrieval.isObject()) {
      throw new IllegalArgumentException("The retrieval information is a JSON object");
    }
    if (contentDigest.isBlank()) {
      throw new IllegalArgumentException("A content digest is not blank");
    }
  }

  /**
   * What a retrieval found for one table. A table that could not be read, or was not retrieved, is
   * never passed, so a missing privilege or a transient failure cannot look like a change.
   */
  public sealed interface TableRecord {
    /** The source-declared part, as the content of the table's Input Observation. */
    JsonNode sourceDeclared();

    /** The connector-derived part besides the retrieval information and the content digest. */
    JsonNode derived();
  }

  /**
   * A table that was read.
   *
   * @param complete whether every item of the table could be read; incomplete metadata states what
   *     is missing, and nothing is inferred from a missing item
   * @param tableMetadata the source-independent table metadata as the connector returned it: an
   *     object with the table's names, kind, source identifier, columns, and constraints, each
   *     column carrying its Analytics type or the reason it has none in its {@value
   *     #ANALYTICS_TYPE} member
   * @param sourceMetadata the connector's source metadata of the table
   * @param readAt when the connector read the table's catalog
   */
  public record Read(
      boolean complete, JsonNode tableMetadata, SourceMetadata sourceMetadata, Instant readAt)
      implements TableRecord {
    public Read {
      if (!tableMetadata.isObject()) {
        throw new IllegalArgumentException("Table metadata is a JSON object");
      }
      var columns = tableMetadata.get("columns");
      if (columns != null && !(columns.isArray() && allObjects(columns))) {
        throw new IllegalArgumentException("The columns of table metadata are JSON objects");
      }
    }

    @Override
    public JsonNode sourceDeclared() {
      var declared = (ObjectNode) LogRecordJson.toStored(tableMetadata);
      var columns = declared.get("columns");
      if (columns != null) {
        columns.forEach(column -> ((ObjectNode) column).remove(ANALYTICS_TYPE));
      }
      var content = JsonNodeFactory.instance.objectNode();
      content.put("state", "read");
      content.put("complete", complete);
      content.set("tableMetadata", declared);
      content.set("sourceMetadata", LogRecordJson.toStored(sourceMetadata));
      return content;
    }

    @Override
    public JsonNode derived() {
      var derived = JsonNodeFactory.instance.objectNode();
      var types = derived.putArray("analyticsTypes");
      var columns = tableMetadata.get("columns");
      if (columns != null) {
        for (var column : columns) {
          var type = column.get(ANALYTICS_TYPE);
          if (type != null) {
            var entry = types.addObject();
            entry.set("column", column.get("name"));
            entry.set(ANALYTICS_TYPE, type);
          }
        }
      }
      derived.put("readAt", readAt.toString());
      return LogRecordJson.toStored(derived);
    }

    private static boolean allObjects(JsonNode array) {
      for (var element : array) {
        if (!element.isObject()) {
          return false;
        }
      }
      return true;
    }
  }

  /** A table the source reports does not exist. */
  public record DoesNotExist() implements TableRecord {
    @Override
    public JsonNode sourceDeclared() {
      return JsonNodeFactory.instance.objectNode().put("state", "does-not-exist");
    }

    @Override
    public JsonNode derived() {
      return JsonNodeFactory.instance.objectNode();
    }
  }

  /**
   * A connector's source metadata of one table: what the connector read about it, in a shape only
   * that connector defines. It is the material the Semantic Catalog interprets for meaning.
   *
   * @param connector the name of the connector that produced it
   * @param document the JSON document
   */
  public record SourceMetadata(String connector, JsonNode document) {
    public SourceMetadata {
      if (connector.isBlank()) {
        throw new IllegalArgumentException("A connector name is not blank");
      }
    }
  }

  /** The derived record of the version: what the connector derived, kept outside the log. */
  JsonNode derivedRecord() {
    var record = (ObjectNode) table.derived().deepCopy();
    record.set("retrieval", LogRecordJson.toStored(retrieval));
    record.put("contentDigest", contentDigest);
    return record;
  }
}
