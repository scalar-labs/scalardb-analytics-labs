/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.physical;

/**
 * A credential value that is never rendered by {@link #toString()}.
 *
 * <p>Records and exceptions that contain a secret can therefore be printed or logged without
 * exposing it. Code reads the value only at the point where it hands the credential to a database
 * driver or another credential channel, never into SQL text or output.
 */
public final class Secret {
  private static final String REDACTED = "[REDACTED]";

  private final String value;

  private Secret(String value) {
    this.value = value;
  }

  public static Secret of(String value) {
    return new Secret(value);
  }

  public String reveal() {
    return value;
  }

  boolean isEmpty() {
    return value.isEmpty();
  }

  @Override
  public String toString() {
    return REDACTED;
  }
}
