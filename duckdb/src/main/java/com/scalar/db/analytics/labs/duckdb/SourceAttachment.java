/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.duckdb;

/**
 * One data source that the Query Engine makes queryable through the connectors' DuckDB extension.
 *
 * <p>The engine loads that extension and attaches every source while it starts, before it disables
 * external access, and SQL then addresses the source's tables as {@code name.schema.table}. An
 * implementation owns how it reaches the source; the engine knows only the name. Implementations
 * must keep credentials out of SQL text, {@link #toString()}, and every exception.
 */
public interface SourceAttachment {
  /** The database name under which SQL addresses this source. */
  String name();

  /**
   * Attaches the source through the given session.
   *
   * @throws SourceAttachException if the source cannot be attached
   */
  void attach(AttachSession session) throws SourceAttachException;
}
