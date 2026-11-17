/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The Query Engine's parser, which the application provides (see {@code
 * docs/semantic-catalog/record-model.md}, "Elements and predicates", on expression values).
 *
 * <p>Expression values, such as a Measure's aggregation rule and filter conditions, are SQL in the
 * Query Engine's dialect. The Semantic Catalog parses them at acceptance, and model-backed
 * Generators before proposing them, to learn which columns they reference; it never binds or runs
 * them here.
 */
@FunctionalInterface
public interface SqlParser {
  /**
   * Parses one SQL text into the Query Engine's serialized parse tree, the form DuckDB's {@code
   * json_serialize_sql} returns. A text that does not parse gives a tree whose {@code error} is
   * true, not an exception.
   */
  JsonNode parse(String sql);
}
