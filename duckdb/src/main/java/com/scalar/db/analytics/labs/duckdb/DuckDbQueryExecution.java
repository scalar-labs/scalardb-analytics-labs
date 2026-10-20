/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.duckdb;

import com.scalar.db.analytics.labs.query.QueryEngineException;
import com.scalar.db.analytics.labs.query.QueryError;
import com.scalar.db.analytics.labs.query.QueryResult;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/** DuckDB-backed lifecycle for one cancellable query execution. */
public final class DuckDbQueryExecution implements AutoCloseable {
  private static final long CANCELLATION_RETRY_MILLIS = 10;

  enum Termination {
    ACTIVE,
    CANCELED,
    ROW_LIMIT,
    BYTE_LIMIT,
    TIME_LIMIT,
    MEMORY_LIMIT,
    COMPLETED,
    FAILED
  }

  private final DuckDbQueryEngine engine;
  private final String sql;
  private final DuckDbQueryExecutionOptions options;
  private final String executionId = UUID.randomUUID().toString();
  private final CompletableFuture<QueryResult> completion = new CompletableFuture<>();
  private final AtomicReference<@Nullable Statement> statement = new AtomicReference<>();
  private final AtomicReference<@Nullable ScheduledFuture<?>> deadline = new AtomicReference<>();
  private final AtomicReference<@Nullable ScheduledFuture<?>> cancellationRetry =
      new AtomicReference<>();
  private final AtomicReference<@Nullable Throwable> cleanupFailure = new AtomicReference<>();
  private final Object lifecycleLock = new Object();
  private volatile Termination termination = Termination.ACTIVE;
  private volatile @Nullable DuckDbQueryResult result;
  private volatile @Nullable ScheduledExecutorService controlExecutor;
  private volatile boolean finished;

  DuckDbQueryExecution(DuckDbQueryEngine engine, String sql, DuckDbQueryExecutionOptions options) {
    this.engine = engine;
    this.sql = sql;
    this.options = options;
  }

  void start(ExecutorService queryExecutor, ScheduledExecutorService deadlineExecutor) {
    controlExecutor = deadlineExecutor;
    options
        .timeLimit()
        .ifPresent(
            limit ->
                deadline.set(
                    deadlineExecutor.schedule(
                        () -> resourceLimit(Termination.TIME_LIMIT),
                        limit.toNanos(),
                        TimeUnit.NANOSECONDS)));
    try {
      queryExecutor.execute(() -> engine.run(this, sql, options));
    } catch (RuntimeException e) {
      fail(executionFailed("Could not start query execution."));
    }
  }

  public String executionId() {
    return executionId;
  }

  DuckDbQueryEngine engine() {
    return engine;
  }

  public QueryResult awaitResult() {
    final QueryResult completedResult;
    try {
      completedResult = completion.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      cancel();
      throw terminatedException(Termination.CANCELED);
    } catch (ExecutionException e) {
      var cause = e.getCause();
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw executionFailed("Query execution failed.");
    }
    synchronized (lifecycleLock) {
      throwIfAbnormallyTerminated();
      return completedResult;
    }
  }

  public void cancel() {
    terminate(Termination.CANCELED);
  }

  void resourceLimit(Termination limit) {
    if (limit != Termination.ROW_LIMIT
        && limit != Termination.BYTE_LIMIT
        && limit != Termination.TIME_LIMIT
        && limit != Termination.MEMORY_LIMIT) {
      throw new IllegalArgumentException("Not a resource limit termination: " + limit);
    }
    terminate(limit);
  }

  private void terminate(Termination newTermination) {
    final DuckDbQueryResult currentResult;
    synchronized (lifecycleLock) {
      if (termination != Termination.ACTIVE) {
        return;
      }
      termination = newTermination;
      currentResult = result;
    }
    cancelTrackedStatement();
    scheduleCancellationRetry();
    if (currentResult != null) {
      currentResult.closeAfterTermination();
      completion.completeExceptionally(terminatedException(newTermination));
    }
  }

  void track(Statement currentStatement) throws SQLException {
    statement.set(currentStatement);
    if (termination != Termination.ACTIVE) {
      currentStatement.cancel();
      throwIfTerminated();
    }
  }

  void untrack(Statement currentStatement) {
    statement.compareAndSet(currentStatement, null);
  }

  void complete(DuckDbQueryResult queryResult) {
    var publishResult = false;
    final Termination completedTermination;
    synchronized (lifecycleLock) {
      result = queryResult;
      if (termination == Termination.ACTIVE) {
        completion.complete(queryResult);
        publishResult = true;
      }
      completedTermination = termination;
    }
    if (!publishResult) {
      queryResult.closeAfterTermination();
      completion.completeExceptionally(terminatedException(completedTermination));
    }
  }

  void fail(Throwable failure) {
    final RuntimeException exposedFailure;
    synchronized (lifecycleLock) {
      if (termination == Termination.ACTIVE) {
        termination = Termination.FAILED;
        exposedFailure =
            failure instanceof QueryEngineException queryEngineException
                ? queryEngineException
                : executionFailed("Query execution failed.");
      } else {
        exposedFailure = terminatedException(termination);
      }
    }
    engine.release(this);
    completion.completeExceptionally(exposedFailure);
  }

  QueryEngineException streamingFailure() {
    final DuckDbQueryResult currentResult;
    synchronized (lifecycleLock) {
      if (termination == Termination.ACTIVE) {
        termination = Termination.FAILED;
      }
      if (isAbnormalTermination(termination)) {
        return terminatedException(termination);
      }
      currentResult = result;
    }
    if (currentResult != null) {
      currentResult.closeAfterTermination();
    }
    return executionFailed("Could not read the query result.");
  }

  void throwIfTerminated() {
    var state = termination;
    if (isAbnormalTermination(state)) {
      throw terminatedException(state);
    }
  }

  private void throwIfAbnormallyTerminated() {
    if (isAbnormalTermination(termination)) {
      throw terminatedException(termination);
    }
  }

  QueryEngineException terminatedException() {
    return terminatedException(termination);
  }

  private QueryEngineException terminatedException(Termination state) {
    QueryError error =
        switch (state) {
          case CANCELED -> new QueryError.Canceled("Query execution was canceled.");
          case ROW_LIMIT -> new QueryError.ResourceLimitExceeded("Query row limit exceeded.");
          case BYTE_LIMIT -> new QueryError.ResourceLimitExceeded("Query byte limit exceeded.");
          case TIME_LIMIT -> new QueryError.ResourceLimitExceeded("Query time limit exceeded.");
          case MEMORY_LIMIT -> new QueryError.ResourceLimitExceeded("Query memory limit exceeded.");
          case ACTIVE, COMPLETED, FAILED ->
              new QueryError.ExecutionFailed("Query execution failed.");
        };
    var exception = new QueryEngineException(executionId, error);
    if (cleanupFailure.get() != null) {
      exception.addSuppressed(new IllegalStateException("Additional resource cleanup failed."));
    }
    return exception;
  }

  private QueryEngineException executionFailed(String message) {
    var exception = new QueryEngineException(executionId, new QueryError.ExecutionFailed(message));
    if (cleanupFailure.get() != null) {
      exception.addSuppressed(new IllegalStateException("Additional resource cleanup failed."));
    }
    return exception;
  }

  private static boolean isAbnormalTermination(Termination state) {
    return state == Termination.CANCELED
        || state == Termination.ROW_LIMIT
        || state == Termination.BYTE_LIMIT
        || state == Termination.TIME_LIMIT
        || state == Termination.MEMORY_LIMIT;
  }

  void recordCleanupFailure(Throwable failure) {
    if (!cleanupFailure.compareAndSet(null, failure)) {
      var recorded = cleanupFailure.get();
      if (recorded != null && recorded != failure) {
        recorded.addSuppressed(failure);
      }
    }
  }

  void dataComplete() {
    synchronized (lifecycleLock) {
      if (termination == Termination.ACTIVE) {
        termination = Termination.COMPLETED;
      }
    }
    cancelDeadline();
  }

  void finish() {
    synchronized (lifecycleLock) {
      if (termination == Termination.ACTIVE) {
        termination = Termination.COMPLETED;
      }
    }
    finished = true;
    cancelDeadline();
    cancelCancellationRetry();
    statement.set(null);
  }

  private void cancelTrackedStatement() {
    var currentStatement = statement.get();
    if (currentStatement == null) {
      return;
    }
    try {
      currentStatement.cancel();
    } catch (SQLException e) {
      recordCleanupFailure(e);
    }
  }

  private void scheduleCancellationRetry() {
    var executor = controlExecutor;
    if (executor == null || finished || cancellationRetry.get() != null) {
      return;
    }
    final ScheduledFuture<?> scheduled;
    try {
      scheduled =
          executor.scheduleWithFixedDelay(
              this::retryCancellation,
              CANCELLATION_RETRY_MILLIS,
              CANCELLATION_RETRY_MILLIS,
              TimeUnit.MILLISECONDS);
    } catch (RuntimeException e) {
      recordCleanupFailure(e);
      return;
    }
    if (!cancellationRetry.compareAndSet(null, scheduled)) {
      scheduled.cancel(false);
    }
  }

  private void retryCancellation() {
    if (finished) {
      cancelCancellationRetry();
      return;
    }
    cancelTrackedStatement();
  }

  private void cancelDeadline() {
    var currentDeadline = deadline.getAndSet(null);
    if (currentDeadline != null) {
      currentDeadline.cancel(false);
    }
  }

  private void cancelCancellationRetry() {
    var currentRetry = cancellationRetry.getAndSet(null);
    if (currentRetry != null) {
      currentRetry.cancel(false);
    }
  }

  @Override
  public void close() {
    cancel();
    QueryResult completedResult = null;
    try {
      completedResult = completion.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException ignored) {
      // The execution failure has already been exposed by awaitResult(), or cancellation caused it.
    }
    if (completedResult != null) {
      completedResult.close();
    }
  }
}
