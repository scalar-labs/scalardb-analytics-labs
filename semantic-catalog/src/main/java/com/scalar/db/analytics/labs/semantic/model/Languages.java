/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * The languages a domain's readers use, one of them primary (see {@code
 * docs/semantic-catalog/languages.md}, "Declared languages"). They decide for which languages a
 * language-qualified predicate is missing knowledge and which languages a Fact Address of one may
 * carry.
 *
 * @param declared the declared languages, as BCP 47 language tags in their canonical form, without
 *     duplicates
 * @param primary the primary language, one of the declared languages
 */
public record Languages(List<String> declared, String primary) {
  /** The languages of a domain that declares none: English only. */
  public static final Languages DEFAULT = new Languages(List.of("en"), "en");

  public Languages {
    declared = List.copyOf(declared);
    if (declared.isEmpty()) {
      throw new IllegalArgumentException("A domain declares at least one language");
    }
    if (new LinkedHashSet<>(declared).size() != declared.size()) {
      throw new IllegalArgumentException("A language is declared twice: " + declared);
    }
    declared.forEach(Languages::requireTag);
    if (!declared.contains(primary)) {
      throw new IllegalArgumentException(
          "The primary language " + primary + " is not among the declared languages " + declared);
    }
  }

  /** Whether a language is one of the declared languages. */
  public boolean declares(String language) {
    return declared.contains(language);
  }

  /**
   * Requires a well-formed BCP 47 language tag in its canonical form, such as {@code ja}, {@code
   * en}, or {@code zh-Hant}, so one language always has one spelling.
   *
   * @throws IllegalArgumentException when the tag is malformed or not canonical
   */
  public static String requireTag(String tag) {
    var canonical = Locale.forLanguageTag(tag).toLanguageTag();
    if (canonical.equals("und") || !canonical.equals(tag)) {
      throw new IllegalArgumentException(
          "Not a canonical BCP 47 language tag: "
              + tag
              + (canonical.equals("und") ? "" : " (use " + canonical + ")"));
    }
    return tag;
  }
}
