/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application;

import java.io.PrintStream;
import picocli.CommandLine;

/** Owns the standalone CLI transport and its process-facing input and output. */
final class ScalarDbAnalyticsCli {
  private final CommandLine commandLine;

  ScalarDbAnalyticsCli(PrintStream standardOutput, PrintStream standardError) {
    commandLine = new CommandLine(new ScalarDbAnalyticsCommand());
    commandLine.setOut(new java.io.PrintWriter(standardOutput, true));
    commandLine.setErr(new java.io.PrintWriter(standardError, true));
  }

  int execute(String... arguments) {
    return commandLine.execute(arguments);
  }
}
