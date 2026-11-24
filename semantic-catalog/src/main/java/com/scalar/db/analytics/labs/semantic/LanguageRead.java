/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.scalar.db.analytics.labs.semantic.model.Translation;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A read in a language (see {@code docs/semantic-catalog/languages.md}, "Reading in a language"):
 * the read's result, whose every text is its original in the recording language, and beside it,
 * when the language read in is another, the translation of each text or a mark that it is pending.
 * A translation never replaces the original, so a caller can always show which wording was judged.
 *
 * @param result what the read returned, with original text only
 * @param recordingLanguage the language every original is written in, the domain's primary one
 * @param language the language read in
 * @param texts for each URN in the result that names a record with text, that text with its
 *     translation into the language or a pending mark; empty when the language read in is the
 *     recording language
 */
public record LanguageRead<T>(
    T result, String recordingLanguage, String language, Map<String, Text> texts) {
  public LanguageRead {
    texts = Collections.unmodifiableMap(new LinkedHashMap<>(texts));
  }

  /** The text of one record a read returns, in the original and in the language read in. */
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "status")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = Text.Translated.class, name = "translated"),
    @JsonSubTypes.Type(value = Text.Pending.class, name = "pending")
  })
  public sealed interface Text {
    /** The URN of the log record that holds the text. */
    String logRecord();

    /** Each original text by its key in the record, in the recording language. */
    Map<String, String> originals();

    /**
     * The text has been translated.
     *
     * @param translation the translation, which is a translation and never reviewed: each of its
     *     texts says the original under the same key
     */
    record Translated(String logRecord, Map<String, String> originals, Translation translation)
        implements Text {
      public Translated {
        originals = Collections.unmodifiableMap(new LinkedHashMap<>(originals));
      }
    }

    /**
     * The text awaits its translation, which the read queued; a read after a translation run has
     * made it returns it.
     */
    record Pending(String logRecord, Map<String, String> originals) implements Text {
      public Pending {
        originals = Collections.unmodifiableMap(new LinkedHashMap<>(originals));
      }
    }
  }
}
