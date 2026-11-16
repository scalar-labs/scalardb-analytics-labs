/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

/**
 * A domain's settings for which data reads reach an external model (see {@code
 * docs/semantic-catalog/generators.md}, "Input passed to external models"). Input a setting
 * withholds is not read for the model, never reaches it, and is recorded as missing.
 *
 * @param countStatistics whether row counts, null ratios, and distinct counts from a bounded sample
 *     are passed; passed by default
 * @param valueSamples whether some distinct values of each column are passed; withheld by default,
 *     because values that must not leave the organization could otherwise reach an external model
 *     before the organization decides
 */
public record ModelInputSettings(boolean countStatistics, boolean valueSamples) {
  /** Count statistics passed, value samples withheld. */
  public static ModelInputSettings defaults() {
    return new ModelInputSettings(true, false);
  }
}
