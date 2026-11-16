/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.cli;

import com.scalar.db.analytics.labs.application.cli.catalog.MetadataCommand;
import com.scalar.db.analytics.labs.application.cli.catalog.SourceCommand;
import com.scalar.db.analytics.labs.application.cli.query.QueryCommand;
import com.scalar.db.analytics.labs.application.cli.semantic.SemanticCommand;
import com.scalar.db.analytics.labs.application.cli.workspace.WorkspaceCommand;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import picocli.CommandLine;

/** Owns the standalone CLI transport and its process-facing input and output. */
public final class ScalarDbAnalyticsCli implements AutoCloseable {
  private final CommandLine commandLine;
  private final QueryCommand queryCommand;
  private final SemanticCommand.SemanticBuildCommand buildCommand;

  public ScalarDbAnalyticsCli(PrintStream standardOutput, PrintStream standardError) {
    this(System.in, standardOutput, standardError, System.getenv());
  }

  ScalarDbAnalyticsCli(
      InputStream standardInput, PrintStream standardOutput, PrintStream standardError) {
    this(standardInput, standardOutput, standardError, System.getenv());
  }

  ScalarDbAnalyticsCli(
      InputStream standardInput,
      PrintStream standardOutput,
      PrintStream standardError,
      Map<String, String> environment) {
    commandLine = new CommandLine(new ScalarDbAnalyticsCommand());
    commandLine.addSubcommand("version", new VersionCommand());
    queryCommand = new QueryCommand(standardInput, environment);
    commandLine.addSubcommand("query", queryCommand);

    commandLine.addSubcommand("workspace", WorkspaceCommand.create(environment));
    commandLine.addSubcommand("source", SourceCommand.create(standardInput, environment));
    commandLine.addSubcommand("metadata", MetadataCommand.create(environment));
    buildCommand = new SemanticCommand.SemanticBuildCommand(environment);
    commandLine.addSubcommand("semantic", SemanticCommand.create(buildCommand));
    commandLine.setOut(new java.io.PrintWriter(standardOutput, true, StandardCharsets.UTF_8));
    commandLine.setErr(new java.io.PrintWriter(standardError, true, StandardCharsets.UTF_8));
  }

  public int execute(String... arguments) {
    return commandLine.execute(arguments);
  }

  /**
   * Delivers an interrupt: cancels a running query, or asks a watching build run to stop after its
   * current task. Returns false when nothing took the interrupt, so the process stops at once.
   */
  public boolean cancel() {
    return queryCommand.cancel() || buildCommand.requestStop();
  }

  @Override
  public void close() {
    queryCommand.close();
  }
}
