/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Adapters are configured with a concrete model, so the identity they report says which model
 * answered.
 */
class LanguageModelSettingsTest {
  private static final Duration TIMEOUT = Duration.ofMinutes(5);

  @Test
  void theClaudeAdapterRefusesAnAliasAndReportsTheConcreteModel() {
    assertThatThrownBy(() -> new LanguageModelSettings.Claude("sonnet", TIMEOUT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("concrete model ID starting with \"claude-\"");
    assertThatThrownBy(() -> new LanguageModelSettings.Claude("claude-3-5-haiku-latest", TIMEOUT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not the alias");

    var model = LanguageModel.open(new LanguageModelSettings.Claude("claude-sonnet-5-5", TIMEOUT));
    assertThat(model.identity().path("adapter").asText()).isEqualTo("claude");
    assertThat(model.identity().path("model").asText()).isEqualTo("claude-sonnet-5-5");
  }

  @Test
  void everyAdapterNeedsAModel() {
    assertThatThrownBy(() -> new LanguageModelSettings.Codex(" ", TIMEOUT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("needs a concrete model");
    assertThatThrownBy(() -> new LanguageModelSettings.Command("cat", "", TIMEOUT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("needs a concrete model");
  }
}
