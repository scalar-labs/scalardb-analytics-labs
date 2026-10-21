/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.duckdb;

import com.scalar.db.analytics.labs.query.QueryEngine;
import com.scalar.db.analytics.labs.query.QueryEngineException;
import com.scalar.db.analytics.labs.query.QueryError;
import com.scalar.db.analytics.labs.query.QueryResult;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
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

  private final RootAllocator allocator;
  private final Connection connection;
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
    allocator = new RootAllocator();
    try {
      var opened = openConnection(catalogProvider);
      connection = opened.connection();
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
      Connection connection, @Nullable CatalogHostRegistration catalogHost) {}

  private static OpenedConnection openConnection(@Nullable NativeCatalogProvider catalogProvider) {
    Connection openedConnection = null;
    CatalogHostRegistration registration = null;
    try {
      var properties = new Properties();
      properties.setProperty("allow_unsigned_extensions", "true");
      properties.setProperty("jdbc_stream_results", "true");
      openedConnection = DriverManager.getConnection("jdbc:duckdb:", properties);
      registration = PackagedDuckDbExtension.load(openedConnection, catalogProvider);
      configureConnection(openedConnection);
      return new OpenedConnection(openedConnection, registration);
    } catch (SQLException e) {
      closeAfterFailedInitialization(openedConnection, e);
      closeCatalogHost(registration, e);
      throw new QueryEngineException(
          UUID.randomUUID().toString(),
          new QueryError.InitializationFailed("Could not initialize the Query Engine."));
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
      @Nullable Connection connection, SQLException failure) {
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
      if (options.memoryLimitBytes().isPresent()) {
        setMemoryLimit(options.memoryLimitBytes().getAsLong());
        memoryLimitConfigured = true;
      }
      execution.throwIfTerminated();
      validateReadOnlyQuery(sql, execution);
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
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT scalardb_analytics_is_read_only_query(?) AS allowed")) {
      execution.track(statement);
      statement.setString(1, sql);
      try (var resultSet = statement.executeQuery()) {
        if (!resultSet.next() || !resultSet.getBoolean("allowed")) {
          throw invalidQuery(execution.executionId());
        }
      } finally {
        execution.untrack(statement);
      }
    } catch (SQLException e) {
      execution.throwIfTerminated();
      throw invalidQuery(execution.executionId());
    }
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

  void resetMemoryLimit() throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute("RESET memory_limit");
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
    try (Statement statement = connection.createStatement()) {
      statement.execute("SET enable_external_access = false");
      statement.execute("SET autoinstall_known_extensions = false");
      statement.execute("SET autoload_known_extensions = false");
    }
  }
}
