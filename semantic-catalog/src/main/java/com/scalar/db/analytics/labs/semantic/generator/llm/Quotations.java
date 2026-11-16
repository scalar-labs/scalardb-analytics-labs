/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator.llm;

import java.util.regex.Pattern;

/**
 * The check that a quotation a model gives is in the document it cites, so that a {@code document}
 * check holds text the document has rather than what a model wrote for it (see {@code
 * docs/semantic-catalog/generators.md}, "Meaning inference").
 */
final class Quotations {
  private static final Pattern WHITE_SPACE =
      Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

  private Quotations() {}

  /**
   * A text in the form quotations are checked against: runs of white space count as one space, so a
   * line break or indentation the model did not copy does not fail a quotation.
   */
  static String collapsed(String text) {
    return WHITE_SPACE.matcher(text).replaceAll(" ").strip();
  }

  /**
   * Whether a quotation is in a text given in {@link #collapsed} form. Every character other than
   * white space must match.
   */
  static boolean found(String quotation, String collapsedText) {
    var quoted = collapsed(quotation);
    return !quoted.isEmpty() && collapsedText.contains(quoted);
  }
}
