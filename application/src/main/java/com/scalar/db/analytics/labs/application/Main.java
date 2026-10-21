/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application;

import com.scalar.db.analytics.labs.application.cli.ScalarDbAnalyticsCli;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

/** Starts the standalone ScalarDB Analytics command-line application. */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    int exitCode;
    var cli = new ScalarDbAnalyticsCli(System.out, System.err);
    installInterruptHandler(cli);
    try {
      exitCode = cli.execute(args);
    } finally {
      cli.close();
    }
    System.exit(exitCode);
  }

  private static void installInterruptHandler(ScalarDbAnalyticsCli cli) {
    try {
      var signalClass = Class.forName("sun.misc.Signal");
      var handlerClass = Class.forName("sun.misc.SignalHandler");
      var signal = signalClass.getConstructor(String.class).newInstance("INT");
      var handler =
          Proxy.newProxyInstance(
              Main.class.getClassLoader(),
              new Class<?>[] {handlerClass},
              (proxy, method, arguments) -> {
                if (method.getName().equals("handle")) {
                  if (!cli.cancel()) {
                    System.exit(130);
                  }
                }
                return null;
              });
      signalClass.getMethod("handle", signalClass, handlerClass).invoke(null, signal, handler);
    } catch (ClassNotFoundException
        | NoSuchMethodException
        | InstantiationException
        | IllegalAccessException
        | InvocationTargetException ignored) {
      // The query time limit remains available on runtimes without the JDK signal API.
    }
  }
}
