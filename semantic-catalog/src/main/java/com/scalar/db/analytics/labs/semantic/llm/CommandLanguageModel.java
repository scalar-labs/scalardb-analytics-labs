/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.llm;

import com.scalar.db.analytics.labs.semantic.generator.LanguageModel;
import com.scalar.db.analytics.labs.semantic.model.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * A language model reached through a locally installed, already signed-in coding-agent CLI run as a
 * subprocess with no tools: {@code claude -p} or {@code codex exec}.
 *
 * <p>The adapter is selected by configuration, so the provider can later move to another harness
 * without changing Generators. Prompts run in an empty temporary directory, so the CLI picks up no
 * repository context, and no credential passes through the product.
 */
public final class CommandLanguageModel implements LanguageModel {
  /** Selects the provider: {@code claude} (the default), {@code codex}, or {@code none}. */
  public static final String PROVIDER_VARIABLE = "SCALARDB_ANALYTICS_LLM";

  public static final String MODEL_VARIABLE = "SCALARDB_ANALYTICS_LLM_MODEL";
  public static final String TIMEOUT_VARIABLE = "SCALARDB_ANALYTICS_LLM_TIMEOUT_SECONDS";

  private static final String SYSTEM_PROMPT =
      "You are a component of a data catalog application. Answer with exactly one JSON object and"
          + " nothing else. Do not use tools.";

  public enum Provider {
    CLAUDE,
    CODEX
  }

  private final Provider provider;
  private final @Nullable String model;
  private final Duration timeout;
  private final @Nullable Path logDirectory;

  public CommandLanguageModel(
      Provider provider, @Nullable String model, Duration timeout, @Nullable Path logDirectory) {
    this.provider = provider;
    this.model = model;
    this.timeout = timeout;
    this.logDirectory = logDirectory;
  }

  /**
   * The model the environment selects, or {@link LanguageModel#UNAVAILABLE} for {@code none}.
   *
   * @param logDirectory where each prompt and answer is written, or null for no log
   */
  public static LanguageModel fromEnvironment(
      Map<String, String> environment, @Nullable Path logDirectory) {
    var name = environment.getOrDefault(PROVIDER_VARIABLE, "claude").strip();
    var model = environment.get(MODEL_VARIABLE);
    var timeout =
        Duration.ofSeconds(Long.parseLong(environment.getOrDefault(TIMEOUT_VARIABLE, "300")));
    return switch (name) {
      case "claude" ->
          new CommandLanguageModel(
              Provider.CLAUDE, model == null ? "sonnet" : model, timeout, logDirectory);
      case "codex" -> new CommandLanguageModel(Provider.CODEX, model, timeout, logDirectory);
      case "none" -> LanguageModel.UNAVAILABLE;
      default ->
          throw new IllegalArgumentException("Unknown " + PROVIDER_VARIABLE + " provider: " + name);
    };
  }

  @Override
  public String identity() {
    var name = provider == Provider.CLAUDE ? "claude" : "codex";
    return model == null ? name : name + "/" + model;
  }

  @Override
  public String complete(String purpose, String prompt) throws LanguageModelException {
    var started = System.nanoTime();
    String answer;
    try {
      var directory = Files.createTempDirectory("sdba-llm-");
      try {
        answer =
            provider == Provider.CLAUDE
                ? runClaude(directory, prompt)
                : runCodex(directory, prompt);
      } finally {
        deleteQuietly(directory);
      }
    } catch (IOException e) {
      throw new LanguageModelException(identity() + ": " + e.getMessage(), e);
    }
    log(purpose, prompt, answer, Duration.ofNanos(System.nanoTime() - started));
    return answer;
  }

  private String runClaude(Path directory, String prompt)
      throws IOException, LanguageModelException {
    var command =
        new ArrayList<>(
            List.of(
                "claude",
                "-p",
                "--output-format",
                "json",
                "--tools",
                "",
                "--no-session-persistence",
                "--setting-sources",
                "",
                "--strict-mcp-config",
                "--system-prompt",
                SYSTEM_PROMPT));
    if (model != null) {
      command.addAll(List.of("--model", model));
    }
    var result = run(command, directory, prompt);
    if (result.exitCode != 0 && result.stdout.isBlank()) {
      throw new LanguageModelException(
          "claude: exit " + result.exitCode + ": " + tail(result.stderr));
    }
    var envelope = Json.read(result.stdout);
    if (envelope.path("is_error").asBoolean(false) || result.exitCode != 0) {
      throw new LanguageModelException(
          "claude: " + envelope.path("result").asText(tail(result.stderr)));
    }
    return envelope.path("result").asText("");
  }

  private String runCodex(Path directory, String prompt)
      throws IOException, LanguageModelException {
    var output = directory.resolve("last-message.txt");
    var command =
        new ArrayList<>(
            List.of(
                "codex",
                "exec",
                "--skip-git-repo-check",
                "--sandbox",
                "read-only",
                "--ephemeral",
                "--color",
                "never",
                "-C",
                directory.toString(),
                "-o",
                output.toString()));
    if (model != null) {
      command.addAll(List.of("-m", model));
    }
    command.add("-");
    var result = run(command, directory, SYSTEM_PROMPT + "\n\n" + prompt);
    if (result.exitCode != 0 || !Files.exists(output)) {
      throw new LanguageModelException(
          "codex: exit " + result.exitCode + ": " + tail(result.stderr));
    }
    return Files.readString(output, StandardCharsets.UTF_8);
  }

  private record Result(int exitCode, String stdout, String stderr) {}

  private Result run(List<String> command, Path directory, String input)
      throws IOException, LanguageModelException {
    var stdout = directory.resolve("stdout.txt");
    var stderr = directory.resolve("stderr.txt");
    var stdin = directory.resolve("stdin.txt");
    Files.writeString(stdin, input, StandardCharsets.UTF_8);
    var builder =
        new ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectInput(stdin.toFile())
            .redirectOutput(stdout.toFile())
            .redirectError(stderr.toFile());
    final Process process;
    try {
      process = builder.start();
    } catch (IOException e) {
      throw new LanguageModelException(
          command.getFirst() + ": command not found or not runnable", e);
    }
    try {
      if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new LanguageModelException(
            command.getFirst() + ": timed out after " + timeout.toSeconds() + "s");
      }
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new LanguageModelException(command.getFirst() + ": interrupted", e);
    }
    return new Result(
        process.exitValue(),
        Files.readString(stdout, StandardCharsets.UTF_8),
        Files.readString(stderr, StandardCharsets.UTF_8));
  }

  private static String tail(String text) {
    var trimmed = text.strip();
    return trimmed.length() <= 800 ? trimmed : trimmed.substring(trimmed.length() - 800);
  }

  private void log(String purpose, String prompt, String answer, Duration elapsed) {
    if (logDirectory == null) {
      return;
    }
    try {
      Files.createDirectories(logDirectory);
      var stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
      var safe = purpose.replaceAll("[^A-Za-z0-9_-]", "_");
      Files.writeString(
          logDirectory.resolve(stamp + "-" + safe + ".txt"),
          "model: "
              + identity()
              + "\npurpose: "
              + purpose
              + "\nseconds: "
              + elapsed.toSeconds()
              + "\n\n===== PROMPT =====\n"
              + prompt
              + "\n\n===== ANSWER =====\n"
              + answer
              + "\n",
          StandardCharsets.UTF_8);
    } catch (IOException ignored) {
      // The log is a diagnostic aid; a failure to write it does not fail the call.
    }
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
                  // Temporary files are best-effort cleanup.
                }
              });
    } catch (IOException ignored) {
      // Temporary files are best-effort cleanup.
    }
  }
}
