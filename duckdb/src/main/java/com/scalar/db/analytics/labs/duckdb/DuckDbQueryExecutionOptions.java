/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.duckdb;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Optional DuckDB and Arrow resource limits for one query execution. */
public record DuckDbQueryExecutionOptions(
    OptionalLong rowLimit,
    OptionalLong byteLimit,
    Optional<Duration> timeLimit,
    OptionalLong memoryLimitBytes) {
  public DuckDbQueryExecutionOptions {
    Objects.requireNonNull(rowLimit, "rowLimit must not be null");
    Objects.requireNonNull(byteLimit, "byteLimit must not be null");
    Objects.requireNonNull(timeLimit, "timeLimit must not be null");
    Objects.requireNonNull(memoryLimitBytes, "memoryLimitBytes must not be null");
    requirePositive(rowLimit, "rowLimit");
    requirePositive(byteLimit, "byteLimit");
    requirePositive(memoryLimitBytes, "memoryLimitBytes");
    timeLimit.ifPresent(DuckDbQueryExecutionOptions::requirePositive);
  }

  public static DuckDbQueryExecutionOptions unlimited() {
    return new DuckDbQueryExecutionOptions(
        OptionalLong.empty(), OptionalLong.empty(), Optional.empty(), OptionalLong.empty());
  }

  private static void requirePositive(OptionalLong value, String name) {
    if (value.isPresent() && value.getAsLong() <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requirePositive(Duration value) {
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException("timeLimit must be positive");
    }
    try {
      value.toNanos();
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("timeLimit is too large", e);
    }
  }
}
