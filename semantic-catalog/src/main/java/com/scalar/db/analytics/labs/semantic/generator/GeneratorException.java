/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator;

/** A Generator run that failed; its build task becomes {@code failed} with this reason. */
public final class GeneratorException extends Exception {
  private static final long serialVersionUID = 1L;

  public GeneratorException(String message) {
    super(message);
  }

  public GeneratorException(String message, Throwable cause) {
    super(message, cause);
  }
}
