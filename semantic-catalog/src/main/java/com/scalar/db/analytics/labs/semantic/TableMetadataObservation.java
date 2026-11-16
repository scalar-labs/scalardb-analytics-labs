/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scalar.db.analytics.labs.semantic.model.LogRecordJson;
import com.scalar.db.analytics.labs.semantic.model.PhysicalCatalogReference;
import java.time.Instant;
import java.util.Optional;

/**
 * One retrieved table's metadata, as the application passes it to {@link
 * SemanticCatalogStore#registerTableMetadata} (see {@code docs/semantic-catalog/public-api.md},
 * "Registering an observation").
 *
 * <p>The record is passed whole, exactly as the connector returned it. The connector's source
 * metadata, what it read about the table, becomes an Input Observation of the table's
 * physical-schema Input Resource. The table metadata, which the connector derives from the source
 * metadata, is kept with the read time, retrieval information, and content digest as the version's
 * derived record. The table metadata is held only until the Physical Catalog keeps the table
 * metadata it is built from; the Semantic Catalog then reads it there.
 *
 * @param tableIdentifier the Physical Catalog's identifier of the table; opaque to the Semantic
 *     Catalog, which never parses it
 * @param reference the table's Physical Catalog reference: its data-source registration, namespace,
 *     and table name, by which schema interpretation names its Dataset and Fields. The first
 *     registration of a table records it on the table's Input Resource. Absent when the table's
 *     name path is not one namespace and a table name; its observations are still recorded, and
 *     schema interpretation records the reference as missing input
 * @param version the table metadata version, increasing per table; a version no newer than one
 *     already received records nothing
 * @param table what the retrieval found for the table
 * @param retrieval the retrieval information: which connector, connector version, library version,
 *     and ADBC driver read the table; connector-derived
 * @param contentDigest the content digest of the version; connector-derived
 */
public record TableMetadataObservation(
    String tableIdentifier,
    Optional<PhysicalCatalogReference.Table> reference,
    long version,
    TableRecord table,
    JsonNode retrieval,
    String contentDigest) {
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
    /** What the source declared, as the content of the table's Input Observation. */
    JsonNode observed();

    /** What the connector derived, besides the retrieval information and the content digest. */
    JsonNode derived();
  }

  /**
   * A table that was read.
   *
   * @param complete whether every item of the table could be read; incomplete metadata states what
   *     is missing, and nothing is inferred from a missing item
   * @param tableMetadata the source-independent table metadata as the connector returned it, a JSON
   *     object; the Semantic Catalog keeps it without reading it
   * @param sourceMetadata the connector's source metadata of the table, the observation
   * @param readAt when the connector read the table's catalog
   */
  public record Read(
      boolean complete, JsonNode tableMetadata, SourceMetadata sourceMetadata, Instant readAt)
      implements TableRecord {
    public Read {
      if (!tableMetadata.isObject()) {
        throw new IllegalArgumentException("Table metadata is a JSON object");
      }
    }

    @Override
    public JsonNode observed() {
      var content = JsonNodeFactory.instance.objectNode();
      content.put("state", "read");
      content.set("sourceMetadata", LogRecordJson.toStored(sourceMetadata));
      return content;
    }

    @Override
    public JsonNode derived() {
      var derived = JsonNodeFactory.instance.objectNode();
      derived.put("complete", complete);
      derived.set("tableMetadata", LogRecordJson.toStored(tableMetadata));
      derived.put("readAt", readAt.toString());
      return derived;
    }
  }

  /** A table the source reports does not exist. */
  public record DoesNotExist() implements TableRecord {
    @Override
    public JsonNode observed() {
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
