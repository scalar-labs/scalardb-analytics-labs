/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.construction;

import java.time.Duration;
import java.util.Objects;

/**
 * The domain's settings for construction-run SQL (see {@code docs/application.md}, "Workspace").
 *
 * @param sampleSize the most rows read of a sampled table; a table within it is read whole
 * @param timeLimit how long one construction query may take, including the counts of what it read
 */
public record ConstructionSqlSettings(long sampleSize, Duration timeLimit) {
  public static final long DEFAULT_SAMPLE_SIZE = 100_000;
  public static final Duration DEFAULT_TIME_LIMIT = Duration.ofSeconds(60);
  public static final long MAX_SAMPLE_SIZE = 10_000_000;
  public static final Duration MAX_TIME_LIMIT = Duration.ofDays(1);

  public ConstructionSqlSettings {
    Objects.requireNonNull(timeLimit, "timeLimit");
    if (sampleSize <= 0 || sampleSize > MAX_SAMPLE_SIZE) {
      throw new IllegalArgumentException(
          "The sample size must be positive and at most " + MAX_SAMPLE_SIZE);
    }
    if (timeLimit.isZero() || timeLimit.isNegative() || timeLimit.compareTo(MAX_TIME_LIMIT) > 0) {
      throw new IllegalArgumentException("The time limit must be positive and at most one day");
    }
  }

  public static ConstructionSqlSettings defaults() {
    return new ConstructionSqlSettings(DEFAULT_SAMPLE_SIZE, DEFAULT_TIME_LIMIT);
  }
}
