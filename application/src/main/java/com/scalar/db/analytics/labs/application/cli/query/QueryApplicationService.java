/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.cli.query;

import com.scalar.db.analytics.labs.application.ApplicationRuntime;
import com.scalar.db.analytics.labs.application.workspace.Workspace;
import com.scalar.db.analytics.labs.duckdb.DuckDbQueryExecution;
import com.scalar.db.analytics.labs.duckdb.DuckDbQueryExecutionOptions;
import com.scalar.db.analytics.labs.query.QueryResult;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** Coordinates one non-interactive, cancellable SQL execution use case. */
final class QueryApplicationService implements AutoCloseable {
  interface Runner extends AutoCloseable {
    QueryResult execute(String sql, Optional<Duration> timeLimit);

    void cancel();

    @Override
    void close();
  }

  private final Supplier<Runner> runnerFactory;
  private final AtomicBoolean cancellationRequested = new AtomicBoolean();
  private volatile @Nullable Runner runner;

  static QueryApplicationService create(Workspace workspace) {
    return new QueryApplicationService(() -> new WorkspaceRunner(workspace));
  }

  QueryApplicationService(Supplier<Runner> runnerFactory) {
    this.runnerFactory = runnerFactory;
  }

  QueryResult execute(String sql, Optional<Duration> timeLimit) {
    return runner().execute(sql, timeLimit);
  }

  void cancel() {
    cancellationRequested.set(true);
    var current = runner;
    if (current != null) {
      current.cancel();
    }
  }

  @Override
  public void close() {
    var current = runner;
    if (current != null) {
      current.close();
    }
  }

  private synchronized Runner runner() {
    var current = runner;
    if (current == null) {
      current = runnerFactory.get();
      runner = current;
      if (cancellationRequested.get()) {
        current.cancel();
      }
    }
    return current;
  }

  private static final class WorkspaceRunner implements Runner {
    private final ApplicationRuntime runtime;
    private final com.scalar.db.analytics.labs.duckdb.DuckDbQueryEngine queryEngine;
    private final AtomicBoolean cancellationRequested = new AtomicBoolean();
    private volatile @Nullable DuckDbQueryExecution execution;

    private WorkspaceRunner(Workspace workspace) {
      runtime = workspace.openRuntime();
      try {
        queryEngine = runtime.startQueryEngine(workspace.catalogName());
      } catch (RuntimeException e) {
        runtime.close();
        throw e;
      }
    }

    @Override
    public QueryResult execute(String sql, Optional<Duration> timeLimit) {
      var options =
          new DuckDbQueryExecutionOptions(
              OptionalLong.empty(), OptionalLong.empty(), timeLimit, OptionalLong.empty());
      var started = queryEngine.start(sql, options);
      execution = started;
      if (cancellationRequested.get()) {
        started.cancel();
      }
      return started.awaitResult();
    }

    @Override
    public void cancel() {
      cancellationRequested.set(true);
      var current = execution;
      if (current != null) {
        current.cancel();
      }
    }

    @Override
    public void close() {
      runtime.close();
    }
  }
}
