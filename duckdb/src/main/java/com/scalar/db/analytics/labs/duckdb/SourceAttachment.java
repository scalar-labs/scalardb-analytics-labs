/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.duckdb;

/**
 * One data source that the Query Engine makes queryable, through one read path.
 *
 * <p>The engine attaches every source while it starts, before it disables external access, and SQL
 * then addresses the source's tables as {@code name.schema.table}. An implementation owns how its
 * read path reaches the source; the engine knows only the name. Implementations must keep
 * credentials out of SQL text, {@link #toString()}, and every exception.
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

  /** The read path this source is attached through. */
  ReadPath readPath();

  /**
   * Whether the source attaches through the connectors' DuckDB extension, which the engine then
   * loads from the connectors distribution before attaching.
   */
  default boolean usesConnectorsExtension() {
    return readPath().kind() == ReadPath.Kind.CONNECTOR;
  }

  /**
   * A read path and the DuckDB extension that reads through it (see {@code docs/query-engine.md},
   * "Source attachment and read paths").
   *
   * @param extension the extension's name, as {@code duckdb_extensions()} lists it
   */
  record ReadPath(Kind kind, String extension) {
    /** The read paths. */
    public enum Kind {
      CONNECTOR,
      SCANNER
    }
  }
}
