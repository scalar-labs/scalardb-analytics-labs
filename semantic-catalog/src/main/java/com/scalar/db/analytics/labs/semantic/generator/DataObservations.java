/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scalar.db.analytics.labs.semantic.ConstructionSql;
import com.scalar.db.analytics.labs.semantic.model.Dependency;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.LogRecordJson;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import java.util.Comparator;
import java.util.Locale;

/**
 * Recording what a Generator read of data values as data observations (see {@code
 * docs/semantic-catalog/generators.md}, "Data observations"), shared by every Generator that reads
 * data through construction-run SQL.
 *
 * <p>What one query read is one observation of the Input Resource of its check and target, in the
 * commit of the results that read it. An observation equal to the latest one of the same Input
 * Resource, apart from when each table was read, is not recorded again; the results refer to the
 * latest one instead.
 */
public final class DataObservations {
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

  private DataObservations() {}

  /** A data observation as the run's records refer to it. */
  public record Observed(Ref resource, Ref observation) {
    /** The dependency on the observation. */
    public Dependency dependency() {
      return new Dependency.OnInput(resource, observation);
    }
  }

  /**
   * Records what a query read as a data observation, unless it equals the latest observation of the
   * same Input Resource apart from when it was read, which is referred to instead.
   */
  public static Observed observe(GeneratorContext context, Output output, DataGenerator.Read read) {
    var content = stored(content(read));
    var dataCheck = read.query().dataCheck();
    var existing = context.dataObservationResource(dataCheck);
    if (existing.isPresent()) {
      var latest = context.latestObservation(existing.get());
      if (latest.isPresent()
          && context
              .observationContent(latest.get())
              .map(previous -> comparable(previous).equals(SAME_NUMBER, comparable(content)))
              .orElse(false)) {
        return new Observed(Ref.urn(existing.get()), Ref.urn(latest.get()));
      }
    }
    var resource =
        existing.isPresent()
            ? Ref.urn(existing.get())
            : output.add(LogRecord.InputResource.dataObservation(dataCheck));
    var observation = output.add(new LogRecord.InputObservation(resource, null, content));
    output.add(new LogRecord.ObservationEvent(observation, context.provenance()));
    return new Observed(resource, observation);
  }

  /**
   * JSON as the store gives it back, with each number in the node type its value calls for, so a
   * check or observation produced again from the same input equals the one written before.
   */
  public static JsonNode stored(JsonNode node) {
    return LogRecordJson.readNode(LogRecordJson.writeNode(node));
  }

  /** What an observation records: the check, its target, and each statement with its outcome. */
  private static JsonNode content(DataGenerator.Read read) {
    var query = read.query();
    var content = JSON.objectNode();
    content.put("check", query.check());
    content.set("target", query.target());
    var schemas = content.putArray("physicalSchemas");
    query.physicalSchemas().forEach(schemas::add);
    var statements = content.putArray("statements");
    for (var index = 0; index < query.statements().size(); index++) {
      var request = query.statements().get(index);
      var statement = statements.addObject();
      statement.put("sql", request.sql());
      var inputs = statement.putArray("inputs");
      for (var input : request.inputs()) {
        inputs
            .addObject()
            .put("table", input.table().canonical())
            .put("reading", input.reading().name().toLowerCase(Locale.ROOT));
      }
      statement.set("outcome", outcome(read.outcomes().get(index)));
    }
    return content;
  }

  private static JsonNode outcome(ConstructionSql.Outcome outcome) {
    var node = JSON.objectNode();
    switch (outcome) {
      case ConstructionSql.Outcome.Read read -> {
        node.put("state", "read");
        var columns = node.putArray("columns");
        read.columns().forEach(columns::add);
        var rows = node.putArray("rows");
        for (var row : read.rows()) {
          var values = rows.addArray();
          row.forEach(values::add);
        }
        var ranges = node.putArray("ranges");
        for (var range : read.ranges()) {
          ranges
              .addObject()
              .put("table", range.table().canonical())
              .put(
                  "extent",
                  switch (range.extent()) {
                    case SAMPLE -> "sample";
                    case WHOLE_TABLE -> "whole-table";
                  })
              .put("rowsRead", range.rowsRead())
              .put("readAt", range.readAt().toString());
        }
        var sources = node.putArray("sources");
        for (var source : read.sources()) {
          sources
              .addObject()
              .put("registration", source.registration())
              .put("extension", source.extension())
              .put("extensionVersion", source.extensionVersion());
        }
      }
      case ConstructionSql.Outcome.LimitExceeded exceeded ->
          node.put("state", "limit-exceeded").put("reason", exceeded.reason());
    }
    return node;
  }

  /**
   * Compares numbers by value: a count written as a long is read back from the store as whichever
   * numeric node its size calls for.
   */
  private static final Comparator<JsonNode> SAME_NUMBER =
      (left, right) -> {
        if (left.isNumber() && right.isNumber()) {
          return left.decimalValue().compareTo(right.decimalValue());
        }
        return left.equals(right) ? 0 : 1;
      };

  /** An observation's content without when each table was read, which every read changes. */
  private static JsonNode comparable(JsonNode content) {
    var copy = content.deepCopy();
    for (var statement : copy.path("statements")) {
      for (var range : statement.path("outcome").path("ranges")) {
        if (range instanceof ObjectNode object) {
          object.remove("readAt");
        }
      }
    }
    return copy;
  }
}
