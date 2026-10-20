/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.query;

import java.io.Serializable;

/** A closed set of Query Engine failures that applications can encode for their own boundary. */
public sealed interface QueryError extends Serializable {
  String message();

  record InitializationFailed(String message) implements QueryError {}

  record InvalidQuery(String message) implements QueryError {}

  record ExecutionFailed(String message) implements QueryError {}

  record ResourceLimitExceeded(String message) implements QueryError {}

  record Canceled(String message) implements QueryError {}

  record ShutdownFailed(String message) implements QueryError {}
}
