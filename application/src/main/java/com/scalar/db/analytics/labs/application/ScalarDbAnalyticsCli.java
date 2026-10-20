/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import picocli.CommandLine;

/** Owns the standalone CLI transport and its process-facing input and output. */
final class ScalarDbAnalyticsCli implements AutoCloseable {
  private final CommandLine commandLine;
  private final QueryApplicationService queryService;

  ScalarDbAnalyticsCli(PrintStream standardOutput, PrintStream standardError) {
    this(System.in, standardOutput, standardError);
  }

  ScalarDbAnalyticsCli(
      InputStream standardInput, PrintStream standardOutput, PrintStream standardError) {
    queryService = QueryApplicationService.create();
    commandLine = new CommandLine(new ScalarDbAnalyticsCommand());
    commandLine.addSubcommand("version", new VersionCommand());
    commandLine.addSubcommand("query", new QueryCommand(queryService, standardInput));
    commandLine.setOut(new java.io.PrintWriter(standardOutput, true, StandardCharsets.UTF_8));
    commandLine.setErr(new java.io.PrintWriter(standardError, true, StandardCharsets.UTF_8));
  }

  int execute(String... arguments) {
    return commandLine.execute(arguments);
  }

  @Override
  public void close() {
    queryService.close();
  }
}
