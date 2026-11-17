/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * The Query Engine's parser as the tests reach it: DuckDB's {@code json_serialize_sql} on an
 * in-memory database of the same DuckDB release the product embeds.
 */
final class DuckDbSqlParser implements SqlParser {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Override
  public JsonNode parse(String sql) {
    try (var connection = DriverManager.getConnection("jdbc:duckdb:");
        var statement =
            connection.prepareStatement(
                "SELECT CAST(json_serialize_sql(CAST(? AS VARCHAR)) AS VARCHAR)")) {
      statement.setString(1, sql);
      try (var result = statement.executeQuery()) {
        result.next();
        return JSON.readTree(result.getString(1));
      }
    } catch (SQLException | JsonProcessingException e) {
      throw new IllegalStateException("DuckDB could not parse " + sql, e);
    }
  }
}
