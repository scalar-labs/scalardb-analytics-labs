/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.llm;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A language model reached through an adapter. It is configured with a concrete model, so what it
 * reports as its identity says which model answered.
 */
public interface LanguageModel {
  /** The adapter and the concrete model, recorded in the version of whatever calls the model. */
  JsonNode identity();

  /**
   * Sends one prompt and returns the model's text answer. The adapter adds no wording of its own,
   * so the caller's instructions are the whole fixed part of what the model reads and the caller
   * can version them.
   *
   * @param instructions the caller's fixed instructions, given as the system prompt where the
   *     adapter has one
   * @param input what the instructions apply to
   * @throws LanguageModelException when the model cannot be reached or does not answer
   */
  String complete(String instructions, String input);

  /** Opens the language model the settings name. */
  static LanguageModel open(LanguageModelSettings settings) {
    return new SubprocessLanguageModel(settings);
  }
}
