/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator;

import com.scalar.db.analytics.labs.semantic.generator.data.DataChecks;
import com.scalar.db.analytics.labs.semantic.generator.llm.AnalyticalSemanticsInference;
import com.scalar.db.analytics.labs.semantic.generator.llm.CorrectionInterpretation;
import com.scalar.db.analytics.labs.semantic.generator.llm.JoinAndIdentifierInference;
import com.scalar.db.analytics.labs.semantic.generator.llm.MeaningInference;
import com.scalar.db.analytics.labs.semantic.generator.schema.ConstraintIngestion;
import com.scalar.db.analytics.labs.semantic.generator.schema.TableColumnIngestion;
import java.util.ArrayList;
import java.util.List;

/** The first product's Generators, in the order their build tasks are created. */
public final class Generators {
  private Generators() {}

  /**
   * Schema interpretation, data checks, and meaning inference. The LLM Generators record the
   * model's identity in their version.
   */
  public static List<Generator> standard(LanguageModel model) {
    var generators = new ArrayList<Generator>();
    generators.add(new TableColumnIngestion());
    generators.addAll(ConstraintIngestion.all());
    generators.addAll(DataChecks.all());
    var identity = model.identity();
    generators.add(new MeaningInference(MeaningInference.Rule.ROW, identity));
    generators.add(new MeaningInference(MeaningInference.Rule.FIELD, identity));
    generators.add(new JoinAndIdentifierInference(identity));
    generators.add(new AnalyticalSemanticsInference(identity));
    generators.add(new CorrectionInterpretation(identity));
    return generators;
  }
}
