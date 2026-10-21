/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator;

import java.util.List;

/** What a Generator run can read and use. */
public interface GeneratorContext {
  CatalogView catalog();

  String taskId();

  /** The log record identifiers, or question URNs, that triggered the build task. */
  List<String> triggers();

  /** Reads data values through the application's SQL execution, recording what was read. */
  DataAccess data();

  /** The language model, for Generators that use one. */
  LanguageModel model();
}
