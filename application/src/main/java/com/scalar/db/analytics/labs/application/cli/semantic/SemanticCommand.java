/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.cli.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scalar.db.analytics.labs.application.ApplicationFailure;
import com.scalar.db.analytics.labs.application.cli.CliOutput;
import com.scalar.db.analytics.labs.application.semantic.SemanticSession;
import com.scalar.db.analytics.labs.application.workspace.Workspace;
import com.scalar.db.analytics.labs.semantic.SemanticCatalogException;
import com.scalar.db.analytics.labs.semantic.model.Json;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * The Semantic Catalog commands: construction, review, knowledge reads, and feedback, each a
 * machine-readable call through the application service layer.
 */
@Command(name = "semantic", description = "Builds, reviews, and reads the Semantic Catalog.")
public final class SemanticCommand implements Runnable {
  static final String SCHEMA_VERSION = "1";

  private SemanticCommand() {}

  public static CommandLine create(InputStream standardInput, Map<String, String> environment) {
    var command = new CommandLine(new SemanticCommand());
    SemanticCommands.register(command, standardInput, environment);
    return command;
  }

  @Override
  public void run() {
    throw new CommandLine.ParameterException(
        new CommandLine(this), "A semantic command is required.");
  }

  /** The workspace, principal, and output contract shared by the semantic commands. */
  abstract static class Base implements Callable<Integer> {
    final InputStream standardInput;
    final Map<String, String> environment;

    @Option(names = "--workspace", required = true, paramLabel = "DIR")
    @Nullable Path workspaceDirectory;

    @Spec @Nullable CommandSpec commandSpec;

    Base(InputStream standardInput, Map<String, String> environment) {
      this.standardInput = standardInput;
      this.environment = environment;
    }

    /** The output's schema name, after {@code scalardb-analytics/semantic-}. */
    abstract String schema();

    /** Computes the command's output in an open session. */
    abstract Object run(Workspace workspace) throws IOException;

    @Override
    public final Integer call() throws IOException {
      var out = Objects.requireNonNull(commandSpec).commandLine().getOut();
      try {
        var workspace = Workspace.load(Objects.requireNonNull(workspaceDirectory), environment);
        var output = run(workspace);
        var envelope =
            Json.object()
                .put("schema", "scalardb-analytics/semantic-" + schema())
                .put("schemaVersion", SCHEMA_VERSION);
        var tree = Json.tree(output);
        if (tree instanceof ObjectNode object) {
          envelope.setAll(object);
        } else {
          envelope.set("result", tree);
        }
        out.println(Json.write(envelope));
        return CliOutput.SUCCESS;
      } catch (ApplicationFailure e) {
        return CliOutput.writeFailure(out, e);
      } catch (SemanticCatalogException e) {
        return CliOutput.writeFailure(
            out, com.scalar.db.analytics.labs.application.workspace.SemanticCatalogs.failure(e));
      } catch (IllegalArgumentException e) {
        return CliOutput.writeFailure(
            out,
            new ApplicationFailure(
                "INVALID_REQUEST", Objects.requireNonNullElse(e.getMessage(), "Invalid request.")));
      }
    }

    <T> T session(Workspace workspace, Function<SemanticSession, T> body) {
      return SemanticSession.execute(workspace, body);
    }

    /** The principal on whose behalf the call is made: the option, or the operating system user. */
    String principal(@Nullable String option) {
      if (option != null && !option.isBlank()) {
        return option;
      }
      var user = environment.getOrDefault("USER", System.getProperty("user.name"));
      if (user == null || user.isBlank()) {
        throw new ApplicationFailure("INVALID_REQUEST", "A --principal is required.");
      }
      return user;
    }

    /** Reads JSON input from a file or standard input. */
    JsonNode readJson(@Nullable Path file, boolean stdin) throws IOException {
      String text;
      if (file != null) {
        text = Files.readString(file, StandardCharsets.UTF_8);
      } else if (stdin) {
        text = new String(standardInput.readAllBytes(), StandardCharsets.UTF_8);
      } else {
        throw new ApplicationFailure("INVALID_REQUEST", "Give the input with --file or --stdin.");
      }
      try {
        return Json.read(text);
      } catch (IllegalArgumentException e) {
        throw new ApplicationFailure("INVALID_REQUEST", "The input is not valid JSON.");
      }
    }
  }
}
