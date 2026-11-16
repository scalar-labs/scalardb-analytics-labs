/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.llm;

import java.time.Duration;
import java.util.Objects;

/**
 * Which adapter reaches the model, the concrete model it uses, and how long one answer may take.
 * Whoever opens the model passes them in; the application reads them from the workspace
 * configuration. No credential is part of them, because each adapter runs a program that is already
 * signed in.
 *
 * <p>The model must be concrete, because the version of whatever calls the model includes it: an
 * alias the provider may later point elsewhere, such as {@code sonnet}, would leave provenance
 * unable to say which model produced a Fact.
 */
public sealed interface LanguageModelSettings {
  /** The concrete model the adapter uses. */
  String model();

  /** How long one answer may take before the call fails. */
  Duration timeout();

  /**
   * A locally installed, signed-in {@code claude} CLI run as {@code claude -p} with no tools.
   *
   * @param model a model ID starting with {@code claude-}; a bare alias such as {@code sonnet} and
   *     a {@code -latest} alias are refused
   */
  record Claude(String model, Duration timeout) implements LanguageModelSettings {
    public Claude {
      requireModel(model);
      if (!model.startsWith("claude-") || model.endsWith("-latest")) {
        throw new IllegalArgumentException(
            "The claude adapter needs a concrete model ID starting with \"claude-\", not the"
                + " alias \""
                + model
                + "\"");
      }
      requirePositive(timeout);
    }
  }

  /**
   * A locally installed, signed-in {@code codex} CLI run as {@code codex exec} in a read-only
   * sandbox, with every tool and customization its options can turn off turned off. Codex cannot
   * remove every tool or skip its home directory's {@code AGENTS.md}; see {@code
   * docs/semantic-catalog/generators.md}, "Reaching a language model".
   *
   * @param model the model passed to {@code codex exec -m}, used as given
   */
  record Codex(String model, Duration timeout) implements LanguageModelSettings {
    public Codex {
      requireModel(model);
      requirePositive(timeout);
    }
  }

  /**
   * A configured shell command that reads the prompt on standard input and writes the answer on
   * standard output, to connect another harness.
   *
   * @param command the command, run with {@code sh -c}
   * @param model the identity of the model the command reaches; the product cannot learn it from
   *     the command, so it is stated here
   */
  record Command(String command, String model, Duration timeout) implements LanguageModelSettings {
    public Command {
      if (Objects.requireNonNull(command, "command").isBlank()) {
        throw new IllegalArgumentException("The command adapter needs a command");
      }
      requireModel(model);
      requirePositive(timeout);
    }
  }

  private static void requireModel(String model) {
    if (Objects.requireNonNull(model, "model").isBlank()) {
      throw new IllegalArgumentException("A language model adapter needs a concrete model");
    }
  }

  private static void requirePositive(Duration timeout) {
    if (Objects.requireNonNull(timeout, "timeout").isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("A language model timeout must be positive");
    }
  }
}
