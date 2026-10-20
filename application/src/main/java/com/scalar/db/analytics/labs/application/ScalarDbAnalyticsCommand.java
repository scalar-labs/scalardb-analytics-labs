/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application;

import picocli.CommandLine.Command;

@Command(
    name = "scalardb-analytics",
    description = "Runs ScalarDB Analytics in standalone mode.",
    mixinStandardHelpOptions = true)
final class ScalarDbAnalyticsCommand implements Runnable {
  @Override
  public void run() {
    throw new picocli.CommandLine.ParameterException(
        new picocli.CommandLine(this), "A command is required.");
  }
}
