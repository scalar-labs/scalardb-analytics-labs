/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.CheckResult;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.DataFinding;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ValidationMethod;
import org.jspecify.annotations.Nullable;

/**
 * One check of a validation result (see {@code docs/semantic-catalog/record-model.md}, "Validation
 * results and dependencies"). A validation result is the list of checks an {@code accept} or a
 * {@code propose-retire} holds; a Fact Revision shows that of the latest acceptance.
 *
 * @param items the validation items the check covered: a JSON array of items, or one item
 * @param finding for a data check, and only for one, whether its result rests on a counterexample
 *     found or on no counterexample found
 * @param reference the Input Observation or Fact Revision the check was made against; absent for an
 *     inference check that has none. An Input Observation an acceptance's check references must
 *     also be among the acceptance's dependencies.
 * @param details further content, such as the quoted source text of a document check
 */
public record Check(
    ValidationMethod method,
    JsonNode items,
    CheckResult result,
    @Nullable DataFinding finding,
    @Nullable Ref reference,
    JsonNode details) {
  public Check {
    if ((method == ValidationMethod.DATA_CHECK) != (finding != null)) {
      throw new IllegalArgumentException(
          "A data check, and only a data check, states whether it found a counterexample");
    }
  }
}
