/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.connector;

/**
 * A failed connector API call.
 *
 * <p>The message is the one the Connector composed, which the contract makes safe to log. The Data
 * Source's own code and message are not kept, because they may contain user data.
 */
public final class ConnectorException extends Exception {
  private static final long serialVersionUID = 1L;

  /** The kind of a connector error, as the connector contract defines it. */
  public enum Kind {
    INVALID_SETTINGS,
    CONNECTION,
    AUTHENTICATION,
    PERMISSION_DENIED,
    INVALID_REQUEST,
    INVALID_STATE,
    DOES_NOT_EXIST,
    SCHEMA_MISMATCH,
    NOT_FOUND,
    DATA,
    CANCELLATION,
    TIMEOUT,
    RESOURCE,
    DATA_SOURCE,
    INTERNAL;

    static Kind of(int code) {
      var kinds = values();
      if (code < 1 || code > kinds.length) {
        return INTERNAL;
      }
      return kinds[code - 1];
    }
  }

  private final Kind kind;
  private final boolean retryable;

  public ConnectorException(Kind kind, boolean retryable, String message) {
    super(message);
    this.kind = kind;
    this.retryable = retryable;
  }

  public Kind kind() {
    return kind;
  }

  public boolean retryable() {
    return retryable;
  }
}
