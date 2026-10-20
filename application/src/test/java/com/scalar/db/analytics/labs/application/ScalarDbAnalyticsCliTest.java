/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ScalarDbAnalyticsCliTest {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Test
  void versionReturnsStableMachineReadableOutput() throws Exception {
    var standardOutput = new ByteArrayOutputStream();
    var standardError = new ByteArrayOutputStream();
    var cli =
        new ScalarDbAnalyticsCli(
            new PrintStream(standardOutput, true, StandardCharsets.UTF_8),
            new PrintStream(standardError, true, StandardCharsets.UTF_8));

    var exitCode = cli.execute("version");

    assertThat(exitCode).isZero();
    assertThat(standardError.toString(StandardCharsets.UTF_8)).isEmpty();
    JsonNode output =
        OBJECT_MAPPER.readTree(standardOutput.toString(StandardCharsets.UTF_8).strip());
    assertThat(output.get("schema").textValue()).isEqualTo("scalardb-analytics/version");
    assertThat(output.get("schemaVersion").textValue()).isEqualTo("1");
    assertThat(output.get("productVersion").textValue()).isNotBlank();
  }

  @Test
  void unknownCommandUsesStderrAndReturnsUsageExitCode() {
    var standardOutput = new ByteArrayOutputStream();
    var standardError = new ByteArrayOutputStream();
    var cli =
        new ScalarDbAnalyticsCli(
            new PrintStream(standardOutput, true, StandardCharsets.UTF_8),
            new PrintStream(standardError, true, StandardCharsets.UTF_8));

    var exitCode = cli.execute("unknown");

    assertThat(exitCode).isEqualTo(2);
    assertThat(standardOutput.toString(StandardCharsets.UTF_8)).isEmpty();
    assertThat(standardError.toString(StandardCharsets.UTF_8))
        .contains("unknown")
        .contains("Usage: scalardb-analytics");
  }
}
