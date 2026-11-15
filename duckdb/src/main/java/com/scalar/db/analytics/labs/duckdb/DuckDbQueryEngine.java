/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.duckdb;

import com.scalar.db.analytics.labs.connector.ConnectorPackage;
import com.scalar.db.analytics.labs.duckdb.PackagedDuckDbExtensions.IncompatibleExtensionsException;
import com.scalar.db.analytics.labs.duckdb.SourceAttachments.AttachFailedException;
import com.scalar.db.analytics.labs.query.QueryEngine;
import com.scalar.db.analytics.labs.query.QueryEngineException;
import com.scalar.db.analytics.labs.query.QueryError;
import com.scalar.db.analytics.labs.query.QueryResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.OutOfMemoryException;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.duckdb.DuckDBResultSet;
import org.jspecify.annotations.Nullable;

/** Owns one embedded DuckDB connection and exports query results as Arrow streams. */
public final class DuckDbQueryEngine implements QueryEngine {
  static final int DEFAULT_ARROW_BATCH_SIZE = 1_024;

  /** Core functions that run SQL supplied as a value, which validation could not inspect. */
  private static final List<String> DYNAMIC_SQL_FUNCTIONS =
      List.of("query", "query_table", "json_execute_serialized_sql");

  /**
   * Requires exactly one SELECT statement that calls no function able to bypass the attached
   * sources: neither a dynamic-SQL core function nor a function a packaged extension denies.
   * Function names are read from DuckDB's serialized parse tree, so the check sees every subquery
   * and table function, and the query is never bound or run.
   */
  private static final String VALIDATION_QUERY =
      """
      SELECT
        scalardb_analytics_is_read_only_query(?) AS read_only,
        EXISTS (
          SELECT 1
          FROM (
            SELECT json_extract_string(node.value, '$') AS name
            FROM json_tree(json_serialize_sql(CAST(? AS VARCHAR))) AS node
            WHERE node.key = 'function_name'
          ) AS called
          WHERE %s
        ) AS bypasses_sources
      """
          .formatted(deniedFunctionPredicate());

  private final RootAllocator allocator;
  private final Connection connection;
  // The memory limit the engine runs with outside a query that sets its own.
  private final String engineMemoryLimit;
  private final @Nullable CatalogHostRegistration catalogHost;
  private final ExecutorService queryExecutor;
  private final ScheduledExecutorService deadlineExecutor;
  private final AtomicReference<@Nullable DuckDbQueryExecution> activeExecution =
      new AtomicReference<>();
  private final AtomicBoolean closed = new AtomicBoolean();

  public DuckDbQueryEngine() {
    this(null);
  }

  /**
   * Creates an engine whose product extension calls the given catalog provider.
   *
   * <p>The provider is registered into the extension after it is loaded and unregistered after the
   * connection is closed, so no DuckDB thread can call it outside the engine's lifetime.
   */
  public DuckDbQueryEngine(@Nullable NativeCatalogProvider catalogProvider) {
    this(catalogProvider, List.of());
  }

  /**
   * Creates an engine that also attaches the given data sources.
   *
   * <p>Each source is attached through its own read path while the engine starts, and is queryable
   * as {@code name.schema.table}. External access is disabled afterwards, so SQL can reach no other
   * source, file, or network location, and sources cannot be added to a running engine.
   * Initialization fails with a typed error that names only the source if any source cannot be
   * attached, including when two source names differ only in letter case or a name is reserved by
   * the engine.
   */
  public DuckDbQueryEngine(
      @Nullable NativeCatalogProvider catalogProvider, List<SourceAttachment> sources) {
    var attachedSources = List.copyOf(sources);
    allocator = new RootAllocator();
    try {
      var opened = openConnection(catalogProvider, attachedSources);
      connection = opened.connection();
      engineMemoryLimit = opened.memoryLimit();
      catalogHost = opened.catalogHost();

    } catch (RuntimeException e) {
      try {
        allocator.close();
      } catch (RuntimeException closeFailure) {
        e.addSuppressed(closeFailure);
      }
      throw e;
    }
    queryExecutor = Executors.newVirtualThreadPerTaskExecutor();
    deadlineExecutor =
        Executors.newSingleThreadScheduledExecutor(
            runnable ->
                Thread.ofPlatform().daemon().name("duckdb-query-deadline").unstarted(runnable));
  }

  private record OpenedConnection(
      Connection connection, @Nullable CatalogHostRegistration catalogHost, String memoryLimit) {}

  private static OpenedConnection openConnection(
      @Nullable NativeCatalogProvider catalogProvider, List<SourceAttachment> sources) {
    Connection openedConnection = null;
    CatalogHostRegistration registration = null;
    Path extensionDirectory = null;
    try {
      SourceAttachments.validate(sources);
      // DuckDB reads extensions only from this application-owned directory, never from the
      // per-user default. The packaged extensions are staged there just for loading.
      extensionDirectory = Files.createTempDirectory("scalardb-analytics-duckdb-");
      var properties = PackagedDuckDbExtensions.connectionProperties(extensionDirectory);
      properties.setProperty("jdbc_stream_results", "true");
      openedConnection = DriverManager.getConnection("jdbc:duckdb:", properties);
      registration =
          PackagedDuckDbExtensions.load(openedConnection, extensionDirectory, catalogProvider);
      if (sources.stream().anyMatch(SourceAttachment::usesConnectorsExtension)) {
        // The extension loads the package's library and, when a Data Source opens, its ADBC
        // drivers relative to itself, from the package staged once for the process.
        PackagedDuckDbExtensions.loadConnectors(openedConnection, ConnectorPackage.forProcess());
      }
      SourceAttachments.attach(openedConnection, sources);
      configureConnection(openedConnection);
      return new OpenedConnection(openedConnection, registration, memoryLimit(openedConnection));
    } catch (IncompatibleExtensionsException | AttachFailedException e) {
      closeAfterFailedInitialization(openedConnection, e);
      closeCatalogHost(registration, e);
      throw new QueryEngineException(
          UUID.randomUUID().toString(),
          new QueryError.InitializationFailed(Objects.requireNonNull(e.getMessage())));
    } catch (IOException | SQLException e) {
      closeAfterFailedInitialization(openedConnection, e);
      closeCatalogHost(registration, e);
      throw new QueryEngineException(
          UUID.randomUUID().toString(),
          new QueryError.InitializationFailed("Could not initialize the Query Engine."));
    } finally {
      deleteStagedExtensions(extensionDirectory);
    }
  }

  private static void deleteStagedExtensions(@Nullable Path extensionDirectory) {
    if (extensionDirectory == null) {
      return;
    }
    try {
      for (var extension : PackagedDuckDbExtensions.EXTENSIONS) {
        Files.deleteIfExists(extensionDirectory.resolve(extension + ".duckdb_extension"));
      }
      Files.deleteIfExists(extensionDirectory);
    } catch (IOException ignored) {
      // The operating system can retain the loaded libraries until process exit.
    }
  }

  private static void closeCatalogHost(
      @Nullable CatalogHostRegistration registration, Throwable failure) {
    if (registration == null) {
      return;
    }
    try {
      registration.close();
    } catch (RuntimeException closeFailure) {
      failure.addSuppressed(closeFailure);
    }
  }

  private static void closeAfterFailedInitialization(
      @Nullable Connection connection, Exception failure) {
    if (connection == null) {
      return;
    }
    try {
      connection.close();
    } catch (SQLException closeFailure) {
      failure.addSuppressed(closeFailure);
    }
  }

  @Override
  public QueryResult execute(String sql) {
    return execute(sql, DuckDbQueryExecutionOptions.unlimited());
  }

  public QueryResult execute(String sql, DuckDbQueryExecutionOptions options) {
    return start(sql, options).awaitResult();
  }

  public DuckDbQueryExecution start(String sql, DuckDbQueryExecutionOptions options) {
    if (sql.isBlank()) {
      throw new IllegalArgumentException("sql must not be blank");
    }
    if (closed.get()) {
      throw new IllegalStateException("The Query Engine is closed");
    }
    var execution = new DuckDbQueryExecution(this, sql, options);
    if (!activeExecution.compareAndSet(null, execution)) {
      throw new IllegalStateException("Another query execution is active");
    }
    execution.start(queryExecutor, deadlineExecutor);
    return execution;
  }

  void run(DuckDbQueryExecution execution, String sql, DuckDbQueryExecutionOptions options) {
    BufferAllocator queryAllocator = null;
    Statement statement = null;
    ResultSet resultSet = null;
    var memoryLimitConfigured = false;
    try {
      execution.throwIfTerminated();
      // Validation only parses the submitted text, so it runs before the query's memory limit,
      // which bounds the query and not the engine's own checks.
      validateReadOnlyQuery(sql, execution);
      execution.throwIfTerminated();
      if (options.memoryLimitBytes().isPresent()) {
        setMemoryLimit(options.memoryLimitBytes().getAsLong());
        memoryLimitConfigured = true;
      }
      execution.throwIfTerminated();

      var allocatorLimit = options.memoryLimitBytes().orElse(Long.MAX_VALUE);
      queryAllocator =
          allocator.newChildAllocator("query-" + execution.executionId(), 0, allocatorLimit);
      statement = connection.createStatement();
      execution.track(statement);
      if (!statement.execute(sql)) {
        throw new QueryEngineException(
            execution.executionId(),
            new QueryError.InvalidQuery(
                "The query must return a result and must not modify state."));
      }
      execution.throwIfTerminated();
      resultSet = statement.getResultSet();
      var duckDbResultSet = resultSet.unwrap(DuckDBResultSet.class);
      var reader =
          (ArrowReader) duckDbResultSet.arrowExportStream(queryAllocator, DEFAULT_ARROW_BATCH_SIZE);
      var result =
          new DuckDbQueryResult(
              execution,
              options,
              reader,
              queryAllocator,
              resultSet,
              statement,
              memoryLimitConfigured);
      queryAllocator = null;
      resultSet = null;
      statement = null;
      execution.complete(result);
    } catch (Throwable failure) {
      closeAfterFailedExecution(resultSet, statement, queryAllocator, failure);
      if (memoryLimitConfigured) {
        try {
          resetMemoryLimit();
        } catch (SQLException closeFailure) {
          failure.addSuppressed(closeFailure);
        }
      }
      if (options.memoryLimitBytes().isPresent() && isMemoryLimitFailure(failure)) {
        execution.resourceLimit(DuckDbQueryExecution.Termination.MEMORY_LIMIT);
      }
      execution.fail(failure);
    }
  }

  private void validateReadOnlyQuery(String sql, DuckDbQueryExecution execution) {
    try (PreparedStatement statement = connection.prepareStatement(VALIDATION_QUERY)) {
      execution.track(statement);
      statement.setString(1, sql);
      statement.setString(2, sql);
      try (var resultSet = statement.executeQuery()) {
        if (!resultSet.next() || !resultSet.getBoolean("read_only")) {
          throw invalidQuery(execution.executionId());
        }
        if (resultSet.getBoolean("bypasses_sources")) {
          throw new QueryEngineException(
              execution.executionId(),
              new QueryError.InvalidQuery(
                  "Sources must be queried through their attached databases."));
        }
      } finally {
        execution.untrack(statement);
      }
    } catch (SQLException e) {
      execution.throwIfTerminated();
      throw invalidQuery(execution.executionId());
    }
  }

  private static String deniedFunctionPredicate() {
    var conditions = new ArrayList<String>();
    var names = new ArrayList<>(DYNAMIC_SQL_FUNCTIONS);
    for (var extension : PackagedDuckDbExtensions.FUNCTION_POLICIES) {
      for (var prefix : extension.deniedFunctionPrefixes()) {
        conditions.add("starts_with(called.name, " + sqlLiteral(prefix) + ")");
      }
      names.addAll(extension.deniedFunctions());
    }
    conditions.add(
        "called.name IN ("
            + String.join(", ", names.stream().map(DuckDbQueryEngine::sqlLiteral).toList())
            + ")");
    return String.join(" OR ", conditions);
  }

  private static String sqlLiteral(String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  private static QueryEngineException invalidQuery(String executionId) {
    var error = new QueryError.InvalidQuery("Only a single read-only SELECT query is allowed.");
    return new QueryEngineException(executionId, error);
  }

  private static void closeAfterFailedExecution(
      @Nullable ResultSet resultSet,
      @Nullable Statement statement,
      @Nullable BufferAllocator queryAllocator,
      Throwable failure) {
    if (resultSet != null) {
      try {
        resultSet.close();
      } catch (SQLException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
    }
    if (statement != null) {
      try {
        statement.close();
      } catch (SQLException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
    }
    if (queryAllocator != null) {
      try {
        queryAllocator.close();
      } catch (RuntimeException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
    }
  }

  private void setMemoryLimit(long bytes) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute("SET memory_limit = '" + bytes + " bytes'");
    }
  }

  /**
   * Restores the engine's memory limit after a query that set its own. {@code RESET memory_limit}
   * restores only the setting's value in DuckDB 1.5.6, not the limit its buffer pool enforces, so
   * the engine's own value is set again.
   */
  void resetMemoryLimit() throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute("SET memory_limit = " + sqlLiteral(engineMemoryLimit));
    }
  }

  private static String memoryLimit(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        var resultSet = statement.executeQuery("SELECT current_setting('memory_limit')")) {
      if (!resultSet.next()) {
        throw new SQLException("DuckDB reported no memory limit");
      }
      return resultSet.getString(1);
    }
  }

  static boolean isMemoryLimitFailure(Throwable failure) {
    for (var current = failure; current != null; current = current.getCause()) {
      var message = current.getMessage();
      if (current instanceof OutOfMemoryError
          || current instanceof OutOfMemoryException
          || (message != null
              && (message.contains("Out of Memory Error")
                  || message.contains("Memory limit exceeded")
                  || message.contains("memory limit")))) {
        return true;
      }
    }
    return false;
  }

  void release(DuckDbQueryExecution execution) {
    execution.finish();
    activeExecution.compareAndSet(execution, null);
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    Throwable failure = null;
    var execution = activeExecution.get();
    if (execution != null) {
      try {
        execution.close();
      } catch (RuntimeException e) {
        failure = e;
      }
    }
    deadlineExecutor.shutdownNow();
    queryExecutor.shutdownNow();
    try {
      connection.close();
    } catch (SQLException e) {
      failure = addFailure(failure, e);
    }
    // DuckDB threads stop with the connection, so the extension holds no open catalog scope here.
    if (catalogHost != null) {
      try {
        catalogHost.close();
      } catch (RuntimeException e) {
        failure = addFailure(failure, e);
      }
    }
    try {
      allocator.close();
    } catch (RuntimeException e) {
      failure = addFailure(failure, e);
    }
    if (failure != null) {
      throw new QueryEngineException(
          UUID.randomUUID().toString(),
          new QueryError.ShutdownFailed("Could not close the Query Engine."));
    }
  }

  private static Throwable addFailure(@Nullable Throwable failure, Throwable additional) {
    if (failure == null) {
      return additional;
    }
    failure.addSuppressed(additional);
    return failure;
  }

  private static void configureConnection(Connection connection) throws SQLException {
    // Sources must be attached before this point: DuckDB refuses scanner attachments without
    // external access, while sources attached earlier stay queryable.
    try (Statement statement = connection.createStatement()) {
      statement.execute("SET enable_external_access = false");
    }
  }
}
