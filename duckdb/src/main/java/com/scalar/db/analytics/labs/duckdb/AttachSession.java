/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.duckdb;

/**
 * The engine connection as a {@link SourceAttachment} sees it while the engine starts.
 *
 * <p>Values bound to variables reach DuckDB as statement parameters, never as SQL text, so a read
 * path passes credentials only through {@link #setVariable(String, String)} and refers to them with
 * {@code getvariable(name)}. Failures carry no DuckDB diagnostics, which can quote those values.
 */
public interface AttachSession {
  /** Sets a DuckDB session variable from a bound parameter. */
  void setVariable(String name, String value) throws SourceAttachException;

  /** Sets a DuckDB session variable from a bound parameter. */
  void setVariable(String name, int value) throws SourceAttachException;

  /** Removes a DuckDB session variable. Removing an unset variable succeeds. */
  void resetVariable(String name) throws SourceAttachException;

  /** Executes one statement. The text must not contain credentials or connection values. */
  void execute(String sql) throws SourceAttachException;
}
