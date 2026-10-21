/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator;

import java.util.Set;

/**
 * One rule that produces candidates. The Semantic Catalog runs a Generator inside a build task and
 * records what it returns; the Generator never writes to the log itself.
 */
public interface Generator {
  /** Input kinds of {@link #inputs()}. */
  String PHYSICAL_SCHEMA = "physical-schema";

  String DOCUMENT = "document";
  String RULING = "ruling";
  String MISSING_KNOWLEDGE = "missing-knowledge";
  String USER_CORRECTION = "user-correction";
  String CORRECTION_ANSWER = "correction-answer";

  /** The input kind of a confirmed Fact of an element kind's predicate. */
  static String confirmed(
      com.scalar.db.analytics.labs.semantic.model.ElementKind kind, String predicate) {
    return "confirmed:" + kind.wireName() + ":" + predicate;
  }

  String name();

  /**
   * The Generator version. It changes with any configuration that can change the candidates
   * produced from the same input, including the model and prompt of an LLM Generator.
   */
  String version();

  /** The input kinds the Generator declares it reads, which decide when build tasks are created. */
  Set<String> inputs();

  GeneratorResult run(GeneratorContext context) throws GeneratorException;
}
