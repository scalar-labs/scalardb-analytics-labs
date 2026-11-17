/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.duckdb;

import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * DuckDB's parser, reached without an engine: the serialized parse tree of a SQL text, as {@code
 * json_serialize_sql} returns it from an in-memory database of the embedded DuckDB release. Nothing
 * is bound or run, so no data source, extension, or catalog is needed.
 */
public final class DuckDbSqlParser {
  private DuckDbSqlParser() {}

  /**
   * The serialized parse tree of a SQL text, as JSON text. A text that does not parse gives a tree
   * whose {@code error} is true.
   *
   * @throws IllegalStateException when DuckDB cannot be reached
   */
  public static String serialize(String sql) {
    try (var connection = DriverManager.getConnection("jdbc:duckdb:");
        var statement =
            connection.prepareStatement(
                "SELECT CAST(json_serialize_sql(CAST(? AS VARCHAR)) AS VARCHAR)")) {
      statement.setString(1, sql);
      try (var result = statement.executeQuery()) {
        if (!result.next()) {
          throw new IllegalStateException("DuckDB gave no parse tree");
        }
        return result.getString(1);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("DuckDB could not parse the SQL text", e);
    }
  }
}
