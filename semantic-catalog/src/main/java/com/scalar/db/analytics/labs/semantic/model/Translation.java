/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The text of one log record said in another declared language (see {@code
 * docs/semantic-catalog/languages.md}, "Translations"). It is derived for a reader, kept outside
 * the log, never reviewed, and never an input of a candidate. A log record never changes, so
 * neither does its translation.
 *
 * @param logRecord the URN of the log record whose text it translates
 * @param language the language it is in, a declared language other than the recording language
 * @param texts each translated text by its key in the record (see {@code TranslatableText})
 * @param translator the version of the translator that made it: the model and the instructions
 */
public record Translation(
    String logRecord, String language, Map<String, String> texts, JsonNode translator) {
  public Translation {
    texts = Collections.unmodifiableMap(new LinkedHashMap<>(texts));
    if (texts.isEmpty()) {
      throw new IllegalArgumentException("A translation translates at least one text");
    }
  }
}
