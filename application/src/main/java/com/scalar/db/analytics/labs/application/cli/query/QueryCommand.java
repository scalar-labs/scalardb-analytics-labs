/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.cli.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalar.db.analytics.labs.application.ApplicationFailure;
import com.scalar.db.analytics.labs.application.ArrowJson;
import com.scalar.db.analytics.labs.application.cli.CliOutput;
import com.scalar.db.analytics.labs.application.workspace.Workspace;
import com.scalar.db.analytics.labs.query.QueryEngineException;
import com.scalar.db.analytics.labs.query.QueryError;
import com.scalar.db.analytics.labs.query.QueryResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.apache.arrow.vector.types.pojo.Field;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "query", description = "Executes SQL and prints a machine-readable result.")
public final class QueryCommand implements Callable<Integer>, AutoCloseable {
  private static final int MAX_JSON_ROWS = 1_000;
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final InputStream standardInput;
  private final Map<String, String> environment;
  private final Function<Workspace, QueryApplicationService> queryServiceFactory;
  private final AtomicBoolean active = new AtomicBoolean();
  private final AtomicBoolean cancellationRequested = new AtomicBoolean();
  private volatile @Nullable QueryApplicationService queryService;

  @Option(names = "--workspace", required = true, paramLabel = "DIR")
  private @Nullable Path workspaceDirectory;

  @Option(names = "--time-limit-seconds", paramLabel = "SECONDS")
  private @Nullable Long timeLimitSeconds;

  @ArgGroup(exclusive = true, multiplicity = "1")
  private @Nullable QueryInput queryInput;

  @Spec private @Nullable CommandSpec commandSpec;

  public QueryCommand(InputStream standardInput, Map<String, String> environment) {
    this(standardInput, environment, QueryApplicationService::create);
  }

  QueryCommand(
      InputStream standardInput,
      Map<String, String> environment,
      Function<Workspace, QueryApplicationService> queryServiceFactory) {
    this.standardInput = standardInput;
    this.environment = environment;
    this.queryServiceFactory = queryServiceFactory;
  }

  @Override
  public Integer call() throws JsonProcessingException {
    try {
      var workspace =
          Workspace.load(
              Objects.requireNonNull(workspaceDirectory, "workspaceDirectory"), environment);
      var sql = readSql();
      var limit = timeLimit();
      active.set(true);
      var service = queryServiceFactory.apply(workspace);
      queryService = service;
      if (cancellationRequested.get()) {
        service.cancel();
      }
      QueryOutput output;
      try (service;
          var result = service.execute(sql, limit)) {
        try {
          output = toJsonOutput(result);
        } catch (IOException e) {
          throw new QueryEngineException(
              result.executionId(),
              new QueryError.ExecutionFailed("Could not read the query result."),
              e);
        }
      }
      commandSpec().commandLine().getOut().println(OBJECT_MAPPER.writeValueAsString(output));
      return 0;
    } catch (QueryEngineException e) {
      writeError(e.executionId(), e.error());
      return 1;
    } catch (com.scalar.db.analytics.labs.physical.PhysicalCatalogException e) {
      return CliOutput.writeFailure(
          commandSpec().commandLine().getOut(), ApplicationFailure.from(e));
    } catch (ApplicationFailure e) {
      return CliOutput.writeFailure(commandSpec().commandLine().getOut(), e);
    } catch (IllegalArgumentException | IOException e) {
      writeError(
          UUID.randomUUID().toString(),
          new QueryError.InvalidQuery(
              Objects.requireNonNullElse(e.getMessage(), "Invalid query.")));
      return 1;
    } finally {
      queryService = null;
      cancellationRequested.set(false);
      active.set(false);
    }
  }

  public boolean cancel() {
    if (!active.get()) {
      return false;
    }
    cancellationRequested.set(true);
    var service = queryService;
    if (service != null) {
      service.cancel();
    }
    return true;
  }

  @Override
  public void close() {
    var service = queryService;
    if (service != null) {
      service.close();
    }
  }

  private Optional<Duration> timeLimit() {
    if (timeLimitSeconds == null) {
      return Optional.empty();
    }
    if (timeLimitSeconds <= 0) {
      throw new ApplicationFailure("INVALID_REQUEST", "The query time limit must be positive.");
    }
    return Optional.of(Duration.ofSeconds(timeLimitSeconds));
  }

  private QueryOutput toJsonOutput(QueryResult result) throws IOException {
    var reader = result.reader();
    var fields =
        reader.getVectorSchemaRoot().getSchema().getFields().stream()
            .map(QueryCommand::toOutputField)
            .toList();
    var rows = new ArrayList<List<@Nullable Object>>();
    var truncated = false;
    while (reader.loadNextBatch()) {
      var root = reader.getVectorSchemaRoot();
      for (var rowIndex = 0; rowIndex < root.getRowCount(); rowIndex++) {
        if (rows.size() == MAX_JSON_ROWS) {
          truncated = true;
          break;
        }
        var row = new ArrayList<@Nullable Object>(root.getFieldVectors().size());
        for (var vector : root.getFieldVectors()) {
          row.add(ArrowJson.value(vector, rowIndex));
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
        "scalardb-analytics/query-result", "1", result.executionId(), fields, rows, truncated);
  }

  private static QueryFieldOutput toOutputField(Field field) {
    return new QueryFieldOutput(
        field.getName(),
        field.getType().toString(),
        field.isNullable(),
        field.getChildren().stream().map(QueryCommand::toOutputField).toList());
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
    CliOutput.writeError(
        commandSpec().commandLine().getOut(),
        executionId,
        errorCode(queryError),
        queryError.message());
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
      List<List<@Nullable Object>> rows,
      boolean truncated) {}

  private record QueryFieldOutput(
      String name, String type, boolean nullable, List<QueryFieldOutput> children) {}

  private static final class QueryInput {
    @Option(names = "--sql", description = "SQL to execute.")
    private @Nullable String sql;

    @Option(names = "--file", paramLabel = "PATH", description = "UTF-8 SQL file to execute.")
    private @Nullable Path file;

    @Option(names = "--stdin", description = "Read UTF-8 SQL from standard input.")
    private boolean stdin;
  }
}
