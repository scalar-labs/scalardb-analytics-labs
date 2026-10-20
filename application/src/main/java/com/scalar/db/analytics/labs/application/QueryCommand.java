/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalar.db.analytics.labs.query.QueryEngineException;
import com.scalar.db.analytics.labs.query.QueryError;
import com.scalar.db.analytics.labs.query.QueryResult;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.util.Text;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "query", description = "Executes SQL and prints a machine-readable result.")
final class QueryCommand implements Callable<Integer> {
  private static final int MAX_JSON_ROWS = 1_000;
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final QueryApplicationService queryService;
  private final InputStream standardInput;

  @ArgGroup(exclusive = true, multiplicity = "1")
  private @Nullable QueryInput queryInput;

  @Spec private @Nullable CommandSpec commandSpec;

  QueryCommand(QueryApplicationService queryService, InputStream standardInput) {
    this.queryService = queryService;
    this.standardInput = standardInput;
  }

  @Override
  public Integer call() throws JsonProcessingException {
    try (var result = queryService.execute(readSql())) {
      QueryOutput output;
      try {
        output = toJsonOutput(result);
      } catch (IOException e) {
        throw new QueryEngineException(
            result.executionId(),
            new QueryError.ExecutionFailed("Could not read the query result."),
            e);
      }
      commandSpec().commandLine().getOut().println(OBJECT_MAPPER.writeValueAsString(output));
      return 0;
    } catch (QueryEngineException e) {
      writeError(e.executionId(), e.error());
      return 1;
    } catch (IllegalArgumentException | IOException e) {
      writeError(
          UUID.randomUUID().toString(),
          new QueryError.InvalidQuery(
              Objects.requireNonNullElse(e.getMessage(), "Invalid query.")));
      return 1;
    }
  }

  private QueryOutput toJsonOutput(QueryResult result) throws IOException {
    var reader = result.reader();
    var fields =
        reader.getVectorSchemaRoot().getSchema().getFields().stream()
            .map(QueryCommand::toOutputField)
            .toList();
    var rows = new ArrayList<List<Object>>();
    var truncated = false;
    while (reader.loadNextBatch()) {
      var root = reader.getVectorSchemaRoot();
      for (var rowIndex = 0; rowIndex < root.getRowCount(); rowIndex++) {
        if (rows.size() == MAX_JSON_ROWS) {
          truncated = true;
          break;
        }
        var row = new ArrayList<>(root.getFieldVectors().size());
        for (var vector : root.getFieldVectors()) {
          row.add(toJsonValue(vector.getObject(rowIndex)));
        }
        rows.add(row);
      }
      if (truncated) {
        break;
      }
      if (rows.size() == MAX_JSON_ROWS && reader.loadNextBatch()) {
        truncated = true;
        break;
      }
    }
    return new QueryOutput(
        "scalardb-analytics/query-result", "3", result.executionId(), fields, rows, truncated);
  }

  private static QueryFieldOutput toOutputField(Field field) {
    return new QueryFieldOutput(
        field.getName(),
        field.getType().toString(),
        field.isNullable(),
        field.getChildren().stream().map(QueryCommand::toOutputField).toList());
  }

  private static Object toJsonValue(Object value) {
    if (value == null || value instanceof Boolean || value instanceof String) {
      return value;
    }
    if (value instanceof Text text) {
      return text.toString();
    }
    if (value instanceof BigInteger integer) {
      return integer.toString();
    }
    if (value instanceof BigDecimal decimal) {
      return decimal.toPlainString();
    }
    if (value instanceof byte[] bytes) {
      return Base64.getEncoder().encodeToString(bytes);
    }
    if (value instanceof TemporalAccessor || value instanceof UUID) {
      return value.toString();
    }
    if (value instanceof List<?> list) {
      return list.stream().map(QueryCommand::toJsonValue).toList();
    }
    if (value instanceof Map<?, ?> map) {
      var result = new LinkedHashMap<String, Object>();
      map.forEach((key, item) -> result.put(String.valueOf(key), toJsonValue(item)));
      return result;
    }
    return value;
  }

  private String readSql() throws IOException {
    var input = Objects.requireNonNull(queryInput, "queryInput");
    if (input.sql != null) {
      return input.sql;
    }
    if (input.file != null) {
      return Files.readString(input.file, StandardCharsets.UTF_8);
    }
    if (!input.stdin) {
      throw new IllegalStateException("A query input is required.");
    }
    return new String(standardInput.readAllBytes(), StandardCharsets.UTF_8);
  }

  private void writeError(String executionId, QueryError queryError)
      throws JsonProcessingException {
    var output =
        new ErrorOutput(
            "scalardb-analytics/error",
            "3",
            executionId,
            errorCode(queryError),
            queryError.message());
    commandSpec().commandLine().getErr().println(OBJECT_MAPPER.writeValueAsString(output));
  }

  private CommandSpec commandSpec() {
    return Objects.requireNonNull(commandSpec, "commandSpec");
  }

  private static String errorCode(QueryError error) {
    return switch (error) {
      case QueryError.InitializationFailed ignored -> "INITIALIZATION_FAILED";
      case QueryError.InvalidQuery ignored -> "INVALID_QUERY";
      case QueryError.ExecutionFailed ignored -> "EXECUTION_FAILED";
      case QueryError.ResourceLimitExceeded ignored -> "RESOURCE_LIMIT_EXCEEDED";
      case QueryError.Canceled ignored -> "CANCELED";
      case QueryError.ShutdownFailed ignored -> "SHUTDOWN_FAILED";
    };
  }

  private record QueryOutput(
      String schema,
      String schemaVersion,
      String executionId,
      List<QueryFieldOutput> fields,
      List<List<Object>> rows,
      boolean truncated) {}

  private record QueryFieldOutput(
      String name, String type, boolean nullable, List<QueryFieldOutput> children) {}

  private record ErrorOutput(
      String schema, String schemaVersion, String executionId, String code, String message) {}

  private static final class QueryInput {
    @Option(names = "--sql", description = "SQL to execute.")
    private @Nullable String sql;

    @Option(names = "--file", paramLabel = "PATH", description = "UTF-8 SQL file to execute.")
    private @Nullable Path file;

    @Option(names = "--stdin", description = "Read UTF-8 SQL from standard input.")
    private boolean stdin;
  }
}
