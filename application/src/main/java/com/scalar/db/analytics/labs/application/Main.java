/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application;

/** Starts the standalone ScalarDB Analytics command-line application. */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    System.exit(new ScalarDbAnalyticsCli(System.out, System.err).execute(args));
  }
}
