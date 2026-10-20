/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.duckdb;

import com.scalar.db.analytics.labs.query.QueryEngineException;
import com.scalar.db.analytics.labs.query.QueryError;
import com.scalar.db.analytics.labs.query.QueryResult;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.dictionary.Dictionary;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.types.pojo.Schema;
import org.jspecify.annotations.Nullable;

/** Owns the bounded Arrow reader and the DuckDB resources that produce its batches. */
final class DuckDbQueryResult implements QueryResult {
  private final DuckDbQueryExecution execution;
  private final DuckDbQueryExecutionOptions options;
  private final ArrowReader delegate;
  private final ArrowReader reader;
  private final BufferAllocator allocator;
  private final ResultSet resultSet;
  private final Statement statement;
  private final boolean memoryLimitConfigured;
  private long rowsRead;
  private long bytesRead;
  private boolean closed;

  DuckDbQueryResult(
      DuckDbQueryExecution execution,
      DuckDbQueryExecutionOptions options,
      ArrowReader reader,
      BufferAllocator allocator,
      ResultSet resultSet,
      Statement statement,
      boolean memoryLimitConfigured) {
    this.execution = execution;
    this.options = options;
    delegate = reader;
    this.reader = new BoundedArrowReader(this, reader, allocator);
    this.allocator = allocator;
    this.resultSet = resultSet;
    this.statement = statement;
    this.memoryLimitConfigured = memoryLimitConfigured;
  }

  @Override
  public String executionId() {
    return execution.executionId();
  }

  @Override
  public synchronized ArrowReader reader() {
    execution.throwIfTerminated();
    if (closed) {
      throw new IllegalStateException("The query result is closed");
    }
    return reader;
  }

  synchronized boolean loadNextBatch() throws IOException {
    execution.throwIfTerminated();
    if (closed) {
      throw new IllegalStateException("The query result is closed");
    }
    final boolean loaded;
    try {
      loaded = delegate.loadNextBatch();
    } catch (IOException | RuntimeException e) {
      if (options.memoryLimitBytes().isPresent() && DuckDbQueryEngine.isMemoryLimitFailure(e)) {
        execution.resourceLimit(DuckDbQueryExecution.Termination.MEMORY_LIMIT);
        throw execution.terminatedException();
      }
      execution.throwIfTerminated();
      throw execution.streamingFailure();
    }
    execution.throwIfTerminated();
    if (!loaded) {
      execution.dataComplete();
      return false;
    }

    var root = delegate.getVectorSchemaRoot();
    rowsRead = saturatedAdd(rowsRead, root.getRowCount());
    var batchBytes =
        root.getFieldVectors().stream()
            .mapToLong(vector -> vector.getBufferSize())
            .reduce(0, DuckDbQueryResult::saturatedAdd);
    bytesRead = saturatedAdd(bytesRead, batchBytes);
    if (options.rowLimit().isPresent() && rowsRead > options.rowLimit().getAsLong()) {
      execution.resourceLimit(DuckDbQueryExecution.Termination.ROW_LIMIT);
      throw execution.terminatedException();
    }
    if (options.byteLimit().isPresent() && bytesRead > options.byteLimit().getAsLong()) {
      execution.resourceLimit(DuckDbQueryExecution.Termination.BYTE_LIMIT);
      throw execution.terminatedException();
    }
    return true;
  }

  private static long saturatedAdd(long current, long additional) {
    try {
      return Math.addExact(current, additional);
    } catch (ArithmeticException ignored) {
      return Long.MAX_VALUE;
    }
  }

  synchronized void closeAfterTermination() {
    var failure = closeResources();
    if (failure != null) {
      execution.recordCleanupFailure(failure);
    }
  }

  @Override
  public synchronized void close() {
    var failure = closeResources();
    if (failure != null) {
      var exception =
          new QueryEngineException(
              executionId(), new QueryError.ShutdownFailed("Could not close the query result."));
      exception.addSuppressed(new IllegalStateException("Additional resource cleanup failed."));
      throw exception;
    }
  }

  private @Nullable Throwable closeResources() {
    if (closed) {
      return null;
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
    try {
      allocator.close();
    } catch (RuntimeException e) {
      failure = addFailure(failure, e);
    }
    if (memoryLimitConfigured) {
      try {
        executionEngine().resetMemoryLimit();
      } catch (Exception e) {
        failure = addFailure(failure, e);
      }
    }
    executionEngine().release(execution);
    return failure;
  }

  private DuckDbQueryEngine executionEngine() {
    return execution.engine();
  }

  private static Throwable addFailure(@Nullable Throwable failure, Throwable additional) {
    if (failure == null) {
      return additional;
    }
    failure.addSuppressed(additional);
    return failure;
  }

  private static final class BoundedArrowReader extends ArrowReader {
    private final DuckDbQueryResult result;
    private final ArrowReader delegate;

    private BoundedArrowReader(
        DuckDbQueryResult result, ArrowReader delegate, BufferAllocator allocator) {
      super(allocator);
      this.result = result;
      this.delegate = delegate;
    }

    @Override
    public boolean loadNextBatch() throws IOException {
      return result.loadNextBatch();
    }

    @Override
    public long bytesRead() {
      return delegate.bytesRead();
    }

    @Override
    public org.apache.arrow.vector.VectorSchemaRoot getVectorSchemaRoot() throws IOException {
      return delegate.getVectorSchemaRoot();
    }

    @Override
    public Map<Long, Dictionary> getDictionaryVectors() throws IOException {
      return delegate.getDictionaryVectors();
    }

    @Override
    public Dictionary lookup(long id) {
      return delegate.lookup(id);
    }

    @Override
    public Set<Long> getDictionaryIds() {
      return delegate.getDictionaryIds();
    }

    @Override
    protected void closeReadSource() throws IOException {
      delegate.close();
    }

    @Override
    protected Schema readSchema() throws IOException {
      return delegate.getVectorSchemaRoot().getSchema();
    }
  }
}
