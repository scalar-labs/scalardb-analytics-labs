/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.application.ApplicationRuntime;
import com.scalar.db.analytics.labs.application.ArrowValues;
import com.scalar.db.analytics.labs.duckdb.DuckDbQueryEngine;
import com.scalar.db.analytics.labs.duckdb.DuckDbQueryExecutionOptions;
import com.scalar.db.analytics.labs.query.QueryEngineException;
import com.scalar.db.analytics.labs.query.QueryError;
import com.scalar.db.analytics.labs.semantic.SqlExecutor;
import com.scalar.db.analytics.labs.semantic.generator.DataAccess.DataAccessException;
import com.scalar.db.analytics.labs.semantic.model.Json;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;

/**
 * SQL execution for construction runs (application README chapter 5). Queries run on the embedded
 * Query Engine over the registered sources, as the construction run rather than as a user, with
 * row, byte, and time limits; a query over a limit fails as undecidable rather than returning part
 * of a result. The engine starts on the first query.
 */
final class ConstructionSql implements SqlExecutor {
  static final long ROW_LIMIT = 10_000;
  static final long BYTE_LIMIT = 64L * 1024 * 1024;
  static final Duration TIME_LIMIT = Duration.ofSeconds(60);

  private final ApplicationRuntime runtime;
  private final String catalogName;
  private @Nullable DuckDbQueryEngine engine;

  ConstructionSql(ApplicationRuntime runtime, String catalogName) {
    this.runtime = runtime;
    this.catalogName = catalogName;
  }

  @Override
  public Result execute(String sql) throws DataAccessException {
    try {
      var options =
          new DuckDbQueryExecutionOptions(
              OptionalLong.of(ROW_LIMIT),
              OptionalLong.of(BYTE_LIMIT),
              Optional.of(TIME_LIMIT),
              OptionalLong.empty());
      try (var result = engine().execute(sql, options)) {
        var reader = result.reader();
        var columns =
            reader.getVectorSchemaRoot().getSchema().getFields().stream()
                .map(field -> field.getName())
                .toList();
        var rows = new ArrayList<List<JsonNode>>();
        while (reader.loadNextBatch()) {
          var root = reader.getVectorSchemaRoot();
          for (var index = 0; index < root.getRowCount(); index++) {
            var row = new ArrayList<JsonNode>();
            for (var vector : root.getFieldVectors()) {
              var value = ArrowValues.toJson(vector.getObject(index));
              row.add(value == null ? Json.MAPPER.nullNode() : Json.tree(value));
            }
            rows.add(row);
          }
        }
        return new Result(columns, rows, "construction-run:" + result.executionId());
      }
    } catch (QueryEngineException e) {
      throw new DataAccessException(
          e.error().message(), e.error() instanceof QueryError.ResourceLimitExceeded);
    } catch (IOException | RuntimeException e) {
      throw new DataAccessException(
          java.util.Objects.requireNonNullElse(e.getMessage(), "The query failed."), false);
    }
  }

  private synchronized DuckDbQueryEngine engine() {
    if (engine == null) {
      engine = runtime.startQueryEngine(catalogName);
    }
    return engine;
  }
}
