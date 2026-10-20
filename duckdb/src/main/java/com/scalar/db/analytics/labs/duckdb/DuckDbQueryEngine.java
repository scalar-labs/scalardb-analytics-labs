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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.duckdb.DuckDBResultSet;
import org.jspecify.annotations.Nullable;

/** Owns one embedded DuckDB connection and exports query results as Arrow streams. */
public final class DuckDbQueryEngine implements QueryEngine {
  static final int DEFAULT_ARROW_BATCH_SIZE = 1_024;

  private final RootAllocator allocator;
  private final Connection connection;

  public DuckDbQueryEngine() {
    allocator = new RootAllocator();
    try {
      connection = openConnection();
    } catch (RuntimeException e) {
      try {
        allocator.close();
      } catch (RuntimeException closeFailure) {
        e.addSuppressed(closeFailure);
      }
      throw e;
    }
  }

  private static Connection openConnection() {
    Connection openedConnection = null;
    try {
      var properties = new Properties();
      properties.setProperty("allow_unsigned_extensions", "true");
      properties.setProperty("jdbc_stream_results", "true");
      openedConnection = DriverManager.getConnection("jdbc:duckdb:", properties);
      PackagedDuckDbExtension.load(openedConnection);
      configureConnection(openedConnection);
      return openedConnection;
    } catch (SQLException e) {
      closeAfterFailedInitialization(openedConnection, e);
      throw new QueryEngineException(
          UUID.randomUUID().toString(),
          new QueryError.InitializationFailed("Could not initialize the Query Engine."),
          e);
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
    var executionId = UUID.randomUUID().toString();
    if (sql.isBlank()) {
      throw new IllegalArgumentException("sql must not be blank");
    }

    Statement statement = null;
    ResultSet resultSet = null;
    try {
      statement = connection.createStatement();
      if (!statement.execute(sql)) {
        throw new QueryEngineException(
            executionId,
            new QueryError.InvalidQuery(
                "The query must return a result and must not modify state."));
      }
      resultSet = statement.getResultSet();
      var duckDbResultSet = resultSet.unwrap(DuckDBResultSet.class);
      var reader =
          (ArrowReader) duckDbResultSet.arrowExportStream(allocator, DEFAULT_ARROW_BATCH_SIZE);
      return new DuckDbQueryResult(executionId, reader, resultSet, statement);
    } catch (SQLException | RuntimeException e) {
      closeAfterFailedExecution(resultSet, statement, e);
      if (e instanceof QueryEngineException queryEngineException) {
        throw queryEngineException;
      }
      throw new QueryEngineException(
          executionId, new QueryError.ExecutionFailed("Query execution failed."), e);
    }
  }

  private static void closeAfterFailedExecution(
      @Nullable ResultSet resultSet, @Nullable Statement statement, Throwable failure) {
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
  }

  @Override
  public void close() {
    Throwable failure = null;
    try {
      connection.close();
    } catch (SQLException e) {
      failure = e;
    }
    try {
      allocator.close();
    } catch (RuntimeException e) {
      if (failure == null) {
        failure = e;
      } else {
        failure.addSuppressed(e);
      }
    }
    if (failure != null) {
      throw new QueryEngineException(
          UUID.randomUUID().toString(),
          new QueryError.ShutdownFailed("Could not close the Query Engine."),
          failure);
    }
  }

  private static void configureConnection(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("SET enable_external_access = false");
      statement.execute("SET autoinstall_known_extensions = false");
      statement.execute("SET autoload_known_extensions = false");
    }
  }
}
