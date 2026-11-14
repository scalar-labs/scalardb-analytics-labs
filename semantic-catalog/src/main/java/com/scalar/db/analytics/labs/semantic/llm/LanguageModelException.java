/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.llm;

/**
 * A language model could not be reached or gave no usable answer. A build task that calls it fails
 * with the message and can be resumed.
 */
public class LanguageModelException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public LanguageModelException(String message) {
    super(message);
  }

  public LanguageModelException(String message, Throwable cause) {
    super(message, cause);
  }
}
