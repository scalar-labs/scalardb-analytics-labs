/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator;

/**
 * The language model an LLM Generator calls. Implementations are adapters the application selects,
 * so the provider can change without changing Generators.
 */
public interface LanguageModel {
  /** The provider and model, recorded in the Generator version. */
  String identity();

  /**
   * Sends one prompt and returns the model's text answer.
   *
   * @param purpose a short label for logs
   * @throws LanguageModelException when the model cannot be reached or does not answer
   */
  String complete(String purpose, String prompt) throws LanguageModelException;

  final class LanguageModelException extends Exception {
    private static final long serialVersionUID = 1L;

    public LanguageModelException(String message) {
      super(message);
    }

    public LanguageModelException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** A model that is not configured; LLM Generators fail with this reason. */
  LanguageModel UNAVAILABLE =
      new LanguageModel() {
        @Override
        public String identity() {
          return "unavailable";
        }

        @Override
        public String complete(String purpose, String prompt) throws LanguageModelException {
          throw new LanguageModelException("No language model is configured.");
        }
      };
}
