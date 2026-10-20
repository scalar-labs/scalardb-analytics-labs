/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.duckdb;

import com.scalar.db.analytics.labs.query.QueryEngineException;
import com.scalar.db.analytics.labs.query.QueryError;
import com.scalar.db.analytics.labs.query.QueryResult;
import java.sql.ResultSet;
import java.sql.Statement;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.jspecify.annotations.Nullable;

/** Owns the Arrow reader and the JDBC resources that produce its batches. */
final class DuckDbQueryResult implements QueryResult {
  private final String executionId;
  private final ArrowReader reader;
  private final ResultSet resultSet;
  private final Statement statement;
  private boolean closed;

  DuckDbQueryResult(
      String executionId, ArrowReader reader, ResultSet resultSet, Statement statement) {
    this.executionId = executionId;
    this.reader = reader;
    this.resultSet = resultSet;
    this.statement = statement;
  }

  @Override
  public String executionId() {
    return executionId;
  }

  @Override
  public ArrowReader reader() {
    if (closed) {
      throw new IllegalStateException("The query result is closed");
    }
    return reader;
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    Throwable failure = null;
    try {
      reader.close();
    } catch (Exception e) {
      failure = e;
    }
    try {
      resultSet.close();
    } catch (Exception e) {
      failure = addFailure(failure, e);
    }
    try {
      statement.close();
    } catch (Exception e) {
      failure = addFailure(failure, e);
    }
    if (failure != null) {
      throw new QueryEngineException(
          executionId, new QueryError.ShutdownFailed("Could not close the query result."), failure);
    }
  }

  private static Throwable addFailure(@Nullable Throwable failure, Throwable additional) {
    if (failure == null) {
      return additional;
    }
    failure.addSuppressed(additional);
    return failure;
  }
}
