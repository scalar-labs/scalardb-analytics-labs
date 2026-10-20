/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.query;

/** Reports a Query Engine operation that could not be completed. */
public final class QueryEngineException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final String executionId;
  private final QueryError error;

  public QueryEngineException(String executionId, QueryError error) {
    super(error.message());
    this.executionId = executionId;
    this.error = error;
  }

  public QueryEngineException(String executionId, QueryError error, Throwable cause) {
    super(error.message(), cause);
    this.executionId = executionId;
    this.error = error;
  }

  public String executionId() {
    return executionId;
  }

  public QueryError error() {
    return error;
  }
}
