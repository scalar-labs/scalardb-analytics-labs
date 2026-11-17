/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalar.db.analytics.labs.application.workspace.Workspace;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScalarDbAnalyticsCliTest {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Test
  void versionReturnsStableMachineReadableOutput() throws Exception {
    var result = execute(Map.of(), "version");

    assertThat(result.exitCode()).isZero();
    assertThat(result.standardError()).isEmpty();
    JsonNode output = OBJECT_MAPPER.readTree(result.standardOutput().strip());
    assertThat(output.get("schema").textValue()).isEqualTo("scalardb-analytics/version");
    assertThat(output.get("schemaVersion").textValue()).isEqualTo("1");
    assertThat(output.get("productVersion").textValue()).isNotBlank();
  }

  @Test
  void unknownCommandUsesStderrAndReturnsUsageExitCode() {
    var result = execute(Map.of(), "unknown");

    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(result.standardOutput()).isEmpty();
    assertThat(result.standardError()).contains("unknown").contains("Usage: scalardb-analytics");
  }

  @Test
  void queryRejectsAmbiguousInputModesBeforeOpeningWorkspace(@TempDir Path directory) {
    var result =
        execute(
            Map.of(), "query", "--workspace", directory.toString(), "--sql", "SELECT 1", "--stdin");

    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(result.standardOutput()).isEmpty();
    assertThat(result.standardError()).contains("mutually exclusive");
  }

  @Test
  void missingWorkspaceUsesClassifiedJsonOnStdout(@TempDir Path directory) throws Exception {
    var missing = directory.resolve("missing");
    var result = execute(Map.of(), "query", "--workspace", missing.toString(), "--sql", "SELECT 1");

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.standardError()).isEmpty();
    var output = OBJECT_MAPPER.readTree(result.standardOutput().strip());
    assertThat(output.get("schema").textValue()).isEqualTo("scalardb-analytics/error");
    assertThat(output.get("code").textValue()).isEqualTo("INVALID_WORKSPACE");
  }

  @Test
  void withoutTheOptionOrTheEnvironmentVariableNoWorkspaceIsClassified() throws Exception {
    var result = execute(Map.of(), "semantic", "languages");

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.standardError()).isEmpty();
    var output = OBJECT_MAPPER.readTree(result.standardOutput().strip());
    assertThat(output.get("code").textValue()).isEqualTo("INVALID_WORKSPACE");
    assertThat(output.get("message").textValue())
        .contains("--workspace")
        .contains(WorkspaceOption.ENVIRONMENT_VARIABLE);
  }

  @Test
  void theWorkspaceOptionOverridesTheEnvironmentVariable(@TempDir Path directory) throws Exception {
    var named = directory.resolve("named");
    Files.createDirectory(named);
    Files.writeString(named.resolve(Workspace.CONFIG_FILE_NAME), "not-json");
    var environment = Map.of(WorkspaceOption.ENVIRONMENT_VARIABLE, named.toString());

    var fromEnvironment = execute(environment, "query", "--sql", "SELECT 1");
    var fromOption =
        execute(
            environment,
            "query",
            "--workspace",
            directory.resolve("missing").toString(),
            "--sql",
            "SELECT 1");

    assertThat(OBJECT_MAPPER.readTree(fromEnvironment.standardOutput().strip()).get("message"))
        .hasToString("\"The workspace configuration is invalid.\"");
    assertThat(OBJECT_MAPPER.readTree(fromOption.standardOutput().strip()).get("message"))
        .hasToString("\"The workspace does not exist or is invalid.\"");
  }

  @Test
  void invalidWorkspaceUsesClassifiedJsonOnStdout(@TempDir Path directory) throws Exception {
    Files.writeString(directory.resolve(Workspace.CONFIG_FILE_NAME), "not-json");

    var result =
        execute(Map.of(), "query", "--workspace", directory.toString(), "--sql", "SELECT 1");

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.standardError()).isEmpty();
    assertThat(OBJECT_MAPPER.readTree(result.standardOutput().strip()).get("code").textValue())
        .isEqualTo("INVALID_WORKSPACE");
  }

  @Test
  void workspaceInitRejectsCredentialsInJdbcUrlWithoutPersistingThem(@TempDir Path directory)
      throws Exception {
    var workspace = directory.resolve("workspace");
    var secret = "do-not-persist";

    var result =
        execute(
            Map.of("STORE_PASSWORD", "separate-secret"),
            "workspace",
            "init",
            "--workspace",
            workspace.toString(),
            "--catalog",
            "catalog",
            "--store-jdbc-url",
            "jdbc:postgresql://localhost/catalog?%70assword=" + secret,
            "--store-password-env",
            "STORE_PASSWORD");

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.standardError()).isEmpty();
    assertThat(result.standardOutput()).doesNotContain(secret);
    assertThat(OBJECT_MAPPER.readTree(result.standardOutput().strip()).get("code").textValue())
        .isEqualTo("INVALID_REQUEST");
    assertThat(workspace.resolve(Workspace.CONFIG_FILE_NAME)).doesNotExist();
  }

  @Test
  void workspaceLoadRejectsGroupWritableConfiguration(@TempDir Path directory) throws Exception {
    var path = directory.resolve(Workspace.CONFIG_FILE_NAME);
    Files.writeString(
        path,
        """
        {
          "schema": "scalardb-analytics/workspace-configuration",
          "schemaVersion": "1",
          "catalogName": "catalog",
          "storeJdbcUrl": "jdbc:postgresql://localhost/catalog",
          "storePasswordEnv": "STORE_PASSWORD"
        }
        """);
    if (!Files.getFileStore(path).supportsFileAttributeView("posix")) {
      return;
    }
    Files.setPosixFilePermissions(
        path,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_WRITE));

    var result =
        execute(
            Map.of("STORE_PASSWORD", "secret"),
            "query",
            "--workspace",
            directory.toString(),
            "--sql",
            "SELECT 1");

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(OBJECT_MAPPER.readTree(result.standardOutput().strip()).get("code").textValue())
        .isEqualTo("INVALID_WORKSPACE");
  }

  @Test
  void aLanguageNotInItsCanonicalFormIsAnInvalidWorkspaceThatNamesTheFix(@TempDir Path directory)
      throws Exception {
    Files.writeString(
        directory.resolve(Workspace.CONFIG_FILE_NAME),
        """
        {
          "schema": "scalardb-analytics/workspace-configuration",
          "schemaVersion": "1",
          "catalogName": "catalog",
          "storeJdbcUrl": "jdbc:postgresql://localhost/catalog",
          "storePasswordEnv": "STORE_PASSWORD",
          "semanticCatalog": { "languages": ["ja", "EN"] }
        }
        """);

    var result =
        execute(
            Map.of("STORE_PASSWORD", "secret"),
            "query",
            "--workspace",
            directory.toString(),
            "--sql",
            "SELECT 1");

    assertThat(result.exitCode()).isEqualTo(1);
    var output = OBJECT_MAPPER.readTree(result.standardOutput().strip());
    assertThat(output.get("code").textValue()).isEqualTo("INVALID_WORKSPACE");
    assertThat(output.get("message").textValue())
        .contains("semanticCatalog.languages")
        .contains("EN")
        .contains("use en");
  }

  @Test
  void workspaceLoadRejectsSymbolicLink(@TempDir Path directory) throws Exception {
    var target = directory.resolve("target");
    Files.createDirectory(target);
    Files.writeString(
        target.resolve(Workspace.CONFIG_FILE_NAME),
        """
        {
          "schema": "scalardb-analytics/workspace-configuration",
          "schemaVersion": "1",
          "catalogName": "catalog",
          "storeJdbcUrl": "jdbc:postgresql://localhost/catalog",
          "storePasswordEnv": "STORE_PASSWORD"
        }
        """);
    var link = directory.resolve("linked-workspace");
    Files.createSymbolicLink(link, target);

    var result =
        execute(
            Map.of("STORE_PASSWORD", "secret"),
            "query",
            "--workspace",
            link.toString(),
            "--sql",
            "SELECT 1");

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(OBJECT_MAPPER.readTree(result.standardOutput().strip()).get("code").textValue())
        .isEqualTo("INVALID_WORKSPACE");
  }

  @Test
  void packagesQueryAgentSkillVersionOne() throws IOException {
    assertThat(packagedSkill())
        .contains("version: 1", "Workflow A: build and review", "Workflow B: answer a question");
  }

  @Test
  void theAgentSkillDocumentsOnlyCommandsAndOptionsTheCliHas() throws IOException {
    var invocations = invocations(packagedSkill());
    assertThat(invocations).hasSizeGreaterThan(15);

    try (var cli = new ScalarDbAnalyticsCli(System.out, System.err)) {
      for (var invocation : invocations) {
        var command = cli.commandLine();
        var index = 0;
        while (index < invocation.size()
            && command.getSubcommands().containsKey(invocation.get(index))) {
          command = Objects.requireNonNull(command.getSubcommands().get(invocation.get(index)));
          index++;
        }
        assertThat(index).as("a command in: %s", invocation).isPositive();
        assertThat(command.getSubcommands()).as("a complete command in: %s", invocation).isEmpty();
        for (var token : invocation.subList(index, invocation.size())) {
          if (token.startsWith("--")) {
            assertThat(command.getCommandSpec().findOption(token))
                .as("option %s of: %s", token, invocation)
                .isNotNull();
          }
        }
      }
    }
  }

  private static String packagedSkill() throws IOException {
    var resource =
        Objects.requireNonNull(
            ScalarDbAnalyticsCli.class.getResourceAsStream(
                "/META-INF/scalardb-analytics/skills/query-agent-v1/SKILL.md"));
    try (var input = resource) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /**
   * The CLI invocations in the skill's console blocks: the words after {@code scalardb-analytics},
   * with continued lines joined and optional brackets removed, up to a heredoc.
   */
  private static List<List<String>> invocations(String skill) {
    var invocations = new ArrayList<List<String>>();
    var inBlock = false;
    var current = new StringBuilder();
    for (var line : skill.split("\\R")) {
      var trimmed = line.strip();
      if (trimmed.startsWith("```")) {
        inBlock = trimmed.equals("```console");
        continue;
      }
      if (!inBlock || (current.isEmpty() && !trimmed.contains("scalardb-analytics "))) {
        continue;
      }
      var continued = trimmed.endsWith("\\");
      current.append(' ').append(continued ? trimmed.substring(0, trimmed.length() - 1) : trimmed);
      if (!continued) {
        var text = current.substring(current.indexOf("scalardb-analytics ") + 19);
        var words = new ArrayList<String>();
        for (var word : text.replace("[", " ").replace("]", " ").split("\\s+")) {
          if (word.startsWith("<<")) {
            break;
          }
          if (!word.isEmpty()) {
            words.add(word);
          }
        }
        invocations.add(words);
        current.setLength(0);
      }
    }
    return invocations;
  }

  private static Result execute(Map<String, String> environment, String... arguments) {
    var standardOutput = new ByteArrayOutputStream();
    var standardError = new ByteArrayOutputStream();
    int exitCode;
    try (var cli =
        new ScalarDbAnalyticsCli(
            System.in,
            new PrintStream(standardOutput, true, StandardCharsets.UTF_8),
            new PrintStream(standardError, true, StandardCharsets.UTF_8),
            environment)) {
      exitCode = cli.execute(arguments);
    }
    return new Result(
        exitCode,
        standardOutput.toString(StandardCharsets.UTF_8),
        standardError.toString(StandardCharsets.UTF_8));
  }

  private record Result(int exitCode, String standardOutput, String standardError) {}
}
