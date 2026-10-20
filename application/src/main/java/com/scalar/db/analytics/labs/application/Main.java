/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application;

/** Starts the standalone ScalarDB Analytics command-line application. */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    int exitCode;
    var cli = new ScalarDbAnalyticsCli(System.out, System.err);
    try {
      exitCode = cli.execute(args);
    } finally {
      cli.close();
    }
    System.exit(exitCode);
  }
}
