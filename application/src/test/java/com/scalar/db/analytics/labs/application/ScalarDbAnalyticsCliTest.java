/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScalarDbAnalyticsCliTest {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Test
  void versionReturnsStableMachineReadableOutput() throws Exception {
    var standardOutput = new ByteArrayOutputStream();
    var standardError = new ByteArrayOutputStream();
    int exitCode;
    try (var cli =
        new ScalarDbAnalyticsCli(
            new PrintStream(standardOutput, true, StandardCharsets.UTF_8),
            new PrintStream(standardError, true, StandardCharsets.UTF_8))) {
      exitCode = cli.execute("version");
    }

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
    int exitCode;
    try (var cli =
        new ScalarDbAnalyticsCli(
            new PrintStream(standardOutput, true, StandardCharsets.UTF_8),
            new PrintStream(standardError, true, StandardCharsets.UTF_8))) {
      exitCode = cli.execute("unknown");
    }

    assertThat(exitCode).isEqualTo(2);
    assertThat(standardOutput.toString(StandardCharsets.UTF_8)).isEmpty();
    assertThat(standardError.toString(StandardCharsets.UTF_8))
        .contains("unknown")
        .contains("Usage: scalardb-analytics");
  }

  @Test
  void queryReturnsStableMachineReadableOutput() throws Exception {
    var standardOutput = new ByteArrayOutputStream();
    var standardError = new ByteArrayOutputStream();

    int exitCode;
    try (var cli =
        new ScalarDbAnalyticsCli(
            new PrintStream(standardOutput, true, StandardCharsets.UTF_8),
            new PrintStream(standardError, true, StandardCharsets.UTF_8))) {
      exitCode = cli.execute("query", "--sql", "SELECT 1 AS answer");
    }

    assertThat(exitCode).isZero();
    assertThat(standardError.toString(StandardCharsets.UTF_8)).isEmpty();
    JsonNode output =
        OBJECT_MAPPER.readTree(standardOutput.toString(StandardCharsets.UTF_8).strip());
    assertThat(output.get("schema").textValue()).isEqualTo("scalardb-analytics/query-result");
    assertThat(output.get("schemaVersion").textValue()).isEqualTo("3");
    assertThat(output.get("executionId").textValue()).isNotBlank();
    assertThat(output.get("fields").get(0).get("name").textValue()).isEqualTo("answer");
    assertThat(output.get("fields").get(0).get("type").textValue()).contains("Int");
    assertThat(output.get("rows").get(0).get(0).intValue()).isEqualTo(1);
    assertThat(output.get("truncated").booleanValue()).isFalse();
  }

  @Test
  void queryFailureUsesStructuredStderr() throws Exception {
    var standardOutput = new ByteArrayOutputStream();
    var standardError = new ByteArrayOutputStream();

    int exitCode;
    try (var cli =
        new ScalarDbAnalyticsCli(
            new PrintStream(standardOutput, true, StandardCharsets.UTF_8),
            new PrintStream(standardError, true, StandardCharsets.UTF_8))) {
      exitCode = cli.execute("query", "--sql", "SELECT * FROM missing_table");
    }

    assertThat(exitCode).isEqualTo(1);
    assertThat(standardOutput.toString(StandardCharsets.UTF_8)).isEmpty();
    JsonNode error = OBJECT_MAPPER.readTree(standardError.toString(StandardCharsets.UTF_8).strip());
    assertThat(error.get("schema").textValue()).isEqualTo("scalardb-analytics/error");
    assertThat(error.get("schemaVersion").textValue()).isEqualTo("3");
    assertThat(error.get("executionId").textValue()).isNotBlank();
    assertThat(error.get("code").textValue()).isEqualTo("EXECUTION_FAILED");
    assertThat(error.get("message").textValue()).isEqualTo("Query execution failed.");
  }

  @Test
  void queryReadsSqlFromStandardInput() throws Exception {
    var standardInput =
        new ByteArrayInputStream("SELECT 2 AS answer".getBytes(StandardCharsets.UTF_8));
    var standardOutput = new ByteArrayOutputStream();
    var standardError = new ByteArrayOutputStream();

    int exitCode;
    try (var cli =
        new ScalarDbAnalyticsCli(
            standardInput,
            new PrintStream(standardOutput, true, StandardCharsets.UTF_8),
            new PrintStream(standardError, true, StandardCharsets.UTF_8))) {
      exitCode = cli.execute("query", "--stdin");
    }

    assertThat(exitCode).isZero();
    assertThat(standardError.toString(StandardCharsets.UTF_8)).isEmpty();
    JsonNode output =
        OBJECT_MAPPER.readTree(standardOutput.toString(StandardCharsets.UTF_8).strip());
    assertThat(output.get("rows").get(0).get(0).intValue()).isEqualTo(2);
  }

  @Test
  void queryReadsSqlFromUtf8File(@TempDir Path temporaryDirectory) throws Exception {
    var sqlFile = temporaryDirectory.resolve("query.sql");
    Files.writeString(sqlFile, "SELECT '日本語' AS answer", StandardCharsets.UTF_8);
    var standardOutput = new ByteArrayOutputStream();
    var standardError = new ByteArrayOutputStream();

    int exitCode;
    try (var cli =
        new ScalarDbAnalyticsCli(
            new PrintStream(standardOutput, true, StandardCharsets.UTF_8),
            new PrintStream(standardError, true, StandardCharsets.UTF_8))) {
      exitCode = cli.execute("query", "--file", sqlFile.toString());
    }

    assertThat(exitCode).isZero();
    assertThat(standardError.toString(StandardCharsets.UTF_8)).isEmpty();
    JsonNode output =
        OBJECT_MAPPER.readTree(standardOutput.toString(StandardCharsets.UTF_8).strip());
    assertThat(output.get("rows").get(0).get(0).textValue()).isEqualTo("日本語");
  }

  @Test
  void queryRejectsAmbiguousInputModes() {
    var standardOutput = new ByteArrayOutputStream();
    var standardError = new ByteArrayOutputStream();

    int exitCode;
    try (var cli =
        new ScalarDbAnalyticsCli(
            new PrintStream(standardOutput, true, StandardCharsets.UTF_8),
            new PrintStream(standardError, true, StandardCharsets.UTF_8))) {
      exitCode = cli.execute("query", "--sql", "SELECT 1", "--stdin");
    }

    assertThat(exitCode).isEqualTo(2);
    assertThat(standardOutput.toString(StandardCharsets.UTF_8)).isEmpty();
    assertThat(standardError.toString(StandardCharsets.UTF_8)).contains("mutually exclusive");
  }
}
