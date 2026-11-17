/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.construction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalar.db.analytics.labs.duckdb.DuckDbSqlParser;
import com.scalar.db.analytics.labs.semantic.SqlParser;

/**
 * The Query Engine's parser the Semantic Catalog parses expression values with: DuckDB's serialized
 * parse tree from the embedded release, the dialect the Query Engine runs.
 */
public final class QueryEngineSqlParser implements SqlParser {
  /** The one parser; it holds nothing between calls. */
  public static final QueryEngineSqlParser INSTANCE = new QueryEngineSqlParser();

  private static final ObjectMapper JSON = new ObjectMapper();

  private QueryEngineSqlParser() {}

  @Override
  public JsonNode parse(String sql) {
    try {
      return JSON.readTree(DuckDbSqlParser.serialize(sql));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("The Query Engine gave an unreadable parse tree.", e);
    }
  }
}
