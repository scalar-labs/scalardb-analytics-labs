/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator.llm;

import com.scalar.db.analytics.labs.semantic.generator.Generator;
import java.util.List;

/** The meaning-inference Generators, in the order their build tasks are created. */
public final class MeaningInferences {
  private MeaningInferences() {}

  public static List<Generator> generators() {
    return List.of(
        new RowMeaningInference(),
        new FieldMeaningInference(),
        new ConceptInference(),
        new CorrectionInterpretation());
  }
}
