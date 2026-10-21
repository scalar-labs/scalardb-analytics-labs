/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.cli.workspace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.scalar.db.analytics.labs.application.ApplicationFailure;
import com.scalar.db.analytics.labs.application.cli.CliOutput;
import com.scalar.db.analytics.labs.application.workspace.Workspace;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "workspace", description = "Manages a standalone workspace.")
public final class WorkspaceCommand implements Runnable {
  private WorkspaceCommand() {}

  public static CommandLine create(Map<String, String> environment) {
    var command = new CommandLine(new WorkspaceCommand());
    command.addSubcommand("init", new WorkspaceInitCommand(environment));
    return command;
  }

  @Override
  public void run() {
    throw new picocli.CommandLine.ParameterException(
        new picocli.CommandLine(this), "A workspace command is required.");
  }
}

@Command(name = "init", description = "Initializes a workspace and its Physical Catalog catalog.")
final class WorkspaceInitCommand implements Callable<Integer> {
  private final Map<String, String> environment;

  @Option(names = "--workspace", required = true, paramLabel = "DIR")
  private @Nullable Path workspaceDirectory;

  @Option(names = "--catalog", required = true, paramLabel = "NAME")
  private @Nullable String catalogName;

  @Option(names = "--store-jdbc-url", required = true, paramLabel = "URL")
  private @Nullable String storeJdbcUrl;

  @Option(names = "--store-user", paramLabel = "USER")
  private @Nullable String storeUsername;

  @Option(names = "--store-password-env", required = true, paramLabel = "NAME")
  private @Nullable String storePasswordEnv;

  @Option(
      names = "--semantics-owner",
      paramLabel = "PRINCIPAL",
      description =
          "A principal who decides on Semantic Catalog candidates; repeatable. Defaults to the"
              + " operating system user.")
  private List<String> semanticsOwners = new ArrayList<>();

  @Spec private @Nullable CommandSpec commandSpec;

  WorkspaceInitCommand(Map<String, String> environment) {
    this.environment = environment;
  }

  @Override
  public Integer call() throws JsonProcessingException {
    try {
      var workspace =
          Workspace.initialize(
              Objects.requireNonNull(workspaceDirectory),
              Objects.requireNonNull(catalogName),
              Objects.requireNonNull(storeJdbcUrl),
              storeUsername,
              Objects.requireNonNull(storePasswordEnv),
              owners(),
              environment);
      CliOutput.write(
          commandSpec().commandLine().getOut(),
          new WorkspaceOutput(
              "scalardb-analytics/workspace",
              "2",
              workspace.directory().toString(),
              workspace.catalogName(),
              workspace.semanticsOwners(),
              Urns.snapshot(workspace.catalogName(), 1)));
      return CliOutput.SUCCESS;
    } catch (ApplicationFailure e) {
      return CliOutput.writeFailure(commandSpec().commandLine().getOut(), e);
    }
  }

  private List<String> owners() {
    if (!semanticsOwners.isEmpty()) {
      return semanticsOwners;
    }
    var user = environment.getOrDefault("USER", System.getProperty("user.name"));
    if (user == null || user.isBlank()) {
      throw new ApplicationFailure("INVALID_REQUEST", "A --semantics-owner is required.");
    }
    return List.of(user);
  }

  private CommandSpec commandSpec() {
    return Objects.requireNonNull(commandSpec, "commandSpec");
  }

  private record WorkspaceOutput(
      String schema,
      String schemaVersion,
      String workspace,
      String catalog,
      List<String> semanticsOwners,
      String semanticSnapshot) {}
}
