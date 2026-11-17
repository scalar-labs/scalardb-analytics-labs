/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext;

/**
 * How a model-backed Generator comes to write in the domain's recording language (see {@code
 * docs/semantic-catalog/languages.md}, "Descriptions: one recording language").
 *
 * <p>The language is part of the input, not of the instructions, so a Generator's fixed
 * instructions, and with them its version, are the same in every domain.
 */
final class RecordingLanguage {
  /** The input key that holds the recording language. */
  static final String KEY = "recordingLanguage";

  /** The instruction every model-backed Generator gives, word for word. */
  static final String INSTRUCTION =
      """
      "recordingLanguage" in the input is the BCP 47 language tag of the language the catalog \
      records descriptions in. Write every description you give, such as a meaning, a definition, \
      a scope, and a basis, in that language, whatever language the names, comments, documents, \
      or feedback use. Copy a quotation exactly as its document writes it, and leave refs, slugs, \
      names of tables and columns, and SQL expressions as they are.""";

  private RecordingLanguage() {}

  /** Puts the domain's recording language, its primary language, into a model input. */
  static void put(ObjectNode input, GeneratorContext context) {
    input.put(KEY, context.languages().primary());
  }
}
