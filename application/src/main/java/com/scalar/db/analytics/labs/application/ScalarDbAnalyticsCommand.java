/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
    name = "scalardb-analytics",
    description = "Runs ScalarDB Analytics in standalone mode.",
    subcommands = VersionCommand.class)
final class ScalarDbAnalyticsCommand implements Runnable {
  @Option(
      names = {"-h", "--help"},
      usageHelp = true,
      description = "Show this help message and exit.")
  private boolean helpRequested;

  @Override
  public void run() {
    throw new picocli.CommandLine.ParameterException(
        new picocli.CommandLine(this), "A command is required.");
  }
}
