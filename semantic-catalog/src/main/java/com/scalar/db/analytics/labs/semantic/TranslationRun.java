/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import java.util.List;

/**
 * What a run of a domain's translation queue did (see {@code docs/semantic-catalog/public-api.md},
 * "Running translations").
 *
 * @param translated the queued translations the run made, in the order made
 * @param failed the queued translations the run tried and could not make, which stay queued
 */
public record TranslationRun(List<Translated> translated, List<Failed> failed) {
  public TranslationRun {
    translated = List.copyOf(translated);
    failed = List.copyOf(failed);
  }

  /**
   * A translation the run made and kept.
   *
   * @param logRecord the URN of the log record whose text it translates
   * @param language the language it says the text in
   */
  public record Translated(String logRecord, String language) {}

  /**
   * A translation the run could not make.
   *
   * @param logRecord the URN of the log record whose text was to be translated
   * @param language the language it was to be translated into
   * @param reason why it failed, such as an unavailable model or an answer that left a text out
   */
  public record Failed(String logRecord, String language, String reason) {}
}
