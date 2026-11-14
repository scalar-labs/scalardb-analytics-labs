/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * A language model reached by running a program as a subprocess: a signed-in coding-agent CLI with
 * no tools, or a configured command.
 *
 * <p>Each call runs the program in a new, empty temporary directory, so a CLI picks up no
 * repository context; the prompt and the program's output pass through files in a second temporary
 * directory, outside the one it runs in. The subprocess inherits the process environment, which is
 * how a CLI finds its own sign-in; the product reads and passes no credential.
 */
final class SubprocessLanguageModel implements LanguageModel {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** How much of a failing program's error output a failure message keeps. */
  private static final int ERROR_TAIL = 800;

  /** The codex features that declare tools, each turned off for the call. */
  private static final List<String> CODEX_DISABLED_FEATURES =
      List.of(
          "shell_tool",
          "unified_exec",
          "code_mode_host",
          "multi_agent",
          "goals",
          "view_image",
          "image_generation",
          "apps",
          "plugins",
          "browser_use",
          "computer_use",
          "in_app_browser",
          "skill_search",
          "sleep_tool",
          "tool_suggest");

  /** How long a stopped program is given to exit. */
  private static final Duration STOP_WAIT = Duration.ofSeconds(5);

  private final LanguageModelSettings settings;

  SubprocessLanguageModel(LanguageModelSettings settings) {
    this.settings = settings;
  }

  @Override
  public JsonNode identity() {
    var adapter =
        switch (settings) {
          case LanguageModelSettings.Claude ignored -> "claude";
          case LanguageModelSettings.Codex ignored -> "codex";
          case LanguageModelSettings.Command ignored -> "command";
        };
    return JsonNodeFactory.instance
        .objectNode()
        .put("adapter", adapter)
        .put("model", settings.model());
  }

  @Override
  public String complete(String instructions, String input) {
    try {
      var io = Files.createTempDirectory("semantic-catalog-model-io-");
      try {
        var workingDirectory = Files.createTempDirectory("semantic-catalog-model-");
        try {
          return switch (settings) {
            case LanguageModelSettings.Claude claude ->
                claude(claude, io, workingDirectory, instructions, input);
            case LanguageModelSettings.Codex codex ->
                codex(codex, io, workingDirectory, instructions + "\n\n" + input);
            case LanguageModelSettings.Command command ->
                command(command, io, workingDirectory, instructions + "\n\n" + input);
          };
        } finally {
          deleteQuietly(workingDirectory);
        }
      } finally {
        deleteQuietly(io);
      }
    } catch (IOException e) {
      throw new LanguageModelException(name() + ": " + e.getMessage(), e);
    }
  }

  private String claude(
      LanguageModelSettings.Claude claude,
      Path io,
      Path workingDirectory,
      String instructions,
      String input)
      throws IOException {
    // No built-in tools; no CLAUDE.md, memory, skills, plugins, hooks, or commands (safe mode keeps
    // the CLI's own sign-in); no settings files; no MCP servers; nothing saved.
    var command =
        List.of(
            "claude",
            "-p",
            "--output-format",
            "json",
            "--tools",
            "",
            "--safe-mode",
            "--disable-slash-commands",
            "--setting-sources",
            "",
            "--strict-mcp-config",
            "--no-session-persistence",
            "--system-prompt",
            instructions,
            "--model",
            claude.model());
    var result = run(command, io, workingDirectory, input);
    if (result.exitCode() != 0 && result.stdout().isBlank()) {
      throw new LanguageModelException(
          "claude: exit " + result.exitCode() + ": " + tail(result.stderr()));
    }
    JsonNode envelope;
    try {
      envelope = MAPPER.readTree(result.stdout());
    } catch (JsonProcessingException e) {
      throw new LanguageModelException(
          "claude: exit "
              + result.exitCode()
              + ": output is not its JSON envelope: "
              + tail(result.stderr()),
          e);
    }
    if (result.exitCode() != 0 || envelope.path("is_error").asBoolean(false)) {
      throw new LanguageModelException(
          "claude: " + envelope.path("result").asText(tail(result.stderr())));
    }
    var answer = envelope.path("result");
    if (!answer.isTextual()) {
      throw new LanguageModelException("claude: the JSON envelope holds no result");
    }
    return answer.asText();
  }

  private String codex(
      LanguageModelSettings.Codex codex, Path io, Path workingDirectory, String prompt)
      throws IOException {
    var lastMessage = io.resolve("last-message.txt");
    var command =
        new ArrayList<>(
            List.of(
                "codex",
                "exec",
                "--skip-git-repo-check",
                "--ephemeral",
                "--color",
                "never",
                // No user config.toml (MCP servers, profiles) or execpolicy rules; a read-only
                // sandbox with no approvals and no inherited environment for commands.
                "--ignore-user-config",
                "--ignore-rules",
                "--sandbox",
                "read-only",
                "-c",
                "approval_policy=never",
                "-c",
                "shell_environment_policy.inherit=none",
                // No web search, AGENTS.md from the working directory, skills, plugins, apps,
                // sub-agents, or memories.
                "-c",
                "web_search=disabled",
                "-c",
                "project_doc_max_bytes=0",
                "-c",
                "skills.bundled.enabled=false",
                "-c",
                "skills.include_instructions=false",
                "-c",
                "plugins._default.enabled=false",
                "-c",
                "apps._default.enabled=false",
                "-c",
                "agents.enabled=false",
                "-c",
                "memories.use_memories=false"));
    // The tools codex lets a feature flag remove. It has no option that removes every tool:
    // apply_patch and the code-mode exec stay declared, and the sandbox keeps them from writing
    // or running anything.
    for (var feature : CODEX_DISABLED_FEATURES) {
      command.addAll(List.of("--disable", feature));
    }
    command.addAll(
        List.of(
            "-C",
            workingDirectory.toString(),
            "-o",
            lastMessage.toString(),
            "-m",
            codex.model(),
            "-"));
    var result = run(command, io, workingDirectory, prompt);
    if (result.exitCode() != 0 || !Files.exists(lastMessage)) {
      throw new LanguageModelException(
          "codex: exit " + result.exitCode() + ": " + tail(result.stderr()));
    }
    return Files.readString(lastMessage, StandardCharsets.UTF_8);
  }

  private String command(
      LanguageModelSettings.Command command, Path io, Path workingDirectory, String prompt)
      throws IOException {
    var result = run(List.of("sh", "-c", command.command()), io, workingDirectory, prompt);
    if (result.exitCode() != 0) {
      throw new LanguageModelException(
          "command: exit " + result.exitCode() + ": " + tail(result.stderr()));
    }
    return result.stdout();
  }

  private record Result(int exitCode, String stdout, String stderr) {}

  private Result run(List<String> command, Path io, Path workingDirectory, String input)
      throws IOException {
    var stdin = io.resolve("stdin.txt");
    var stdout = io.resolve("stdout.txt");
    var stderr = io.resolve("stderr.txt");
    Files.writeString(stdin, input, StandardCharsets.UTF_8);
    var builder =
        new ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .redirectInput(stdin.toFile())
            .redirectOutput(stdout.toFile())
            .redirectError(stderr.toFile());
    Process process;
    try {
      process = builder.start();
    } catch (IOException e) {
      throw new LanguageModelException(name() + ": cannot run " + command.getFirst(), e);
    }
    try {
      if (!process.waitFor(settings.timeout())) {
        stop(process);
        throw new LanguageModelException(
            name() + ": no answer within " + settings.timeout().toSeconds() + "s");
      }
    } catch (InterruptedException e) {
      stop(process);
      Thread.currentThread().interrupt();
      throw new LanguageModelException(name() + ": interrupted", e);
    }
    return new Result(
        process.exitValue(),
        Files.readString(stdout, StandardCharsets.UTF_8),
        Files.readString(stderr, StandardCharsets.UTF_8));
  }

  /**
   * Stops the program and whatever it started, such as the command a shell runs, and waits briefly
   * for it to exit so its temporary directories are removed after it is gone.
   */
  private static void stop(Process process) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
    try {
      process.waitFor(STOP_WAIT);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private String name() {
    return identity().path("adapter").asText();
  }

  private static String tail(String text) {
    var trimmed = text.strip();
    return trimmed.length() <= ERROR_TAIL
        ? trimmed
        : trimmed.substring(trimmed.length() - ERROR_TAIL);
  }

  private static void deleteQuietly(Path directory) {
    try (Stream<Path> paths = Files.walk(directory)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException ignored) {
                  // Temporary files are removed on a best-effort basis.
                }
              });
    } catch (IOException ignored) {
      // Temporary files are removed on a best-effort basis.
    }
  }
}
