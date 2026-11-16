/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import java.util.Objects;

/**
 * The content of one registered document, the observation of a document Input Resource (see {@code
 * docs/semantic-catalog/construction.md}, "What starts construction"). The registering principal is
 * recorded on the commit, not here.
 *
 * @param name what the semantics owner calls the document, such as its file name
 * @param text the document's text, which meaning inference passes in parts and against which it
 *     verifies quotations
 */
public record Document(String name, String text) {
  public Document {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(text, "text");
    if (name.isBlank()) {
      throw new IllegalArgumentException("A document has a name");
    }
    if (text.isBlank()) {
      throw new IllegalArgumentException("A document has text");
    }
  }
}
