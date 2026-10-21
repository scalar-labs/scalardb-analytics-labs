/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** One check in a validation result: how a validation method examined some validation items. */
public record Check(
    Method method, List<String> items, Result result, @Nullable DependencyRef rationale) {
  public Check {
    items = List.copyOf(items);
  }

  /** The validation methods; the first and fourth are deterministic. */
  public enum Method {
    @JsonProperty("declared-enforced")
    DECLARED_ENFORCED,
    @JsonProperty("declared-unenforced")
    DECLARED_UNENFORCED,
    @JsonProperty("data-check")
    DATA_CHECK,
    @JsonProperty("document")
    DOCUMENT,
    @JsonProperty("inference")
    INFERENCE
  }

  public enum Result {
    @JsonProperty("supported")
    SUPPORTED,
    @JsonProperty("refuted")
    REFUTED,
    @JsonProperty("undecidable")
    UNDECIDABLE
  }
}
