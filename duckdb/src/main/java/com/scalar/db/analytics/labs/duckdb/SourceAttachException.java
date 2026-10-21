/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.duckdb;

/**
 * A data source could not be attached.
 *
 * <p>The exception carries neither a message nor a cause, because diagnostics of a read path can
 * quote connection settings. The engine reports the failure with the source's name only.
 */
public final class SourceAttachException extends Exception {
  private static final long serialVersionUID = 1L;

  public SourceAttachException() {}
}
