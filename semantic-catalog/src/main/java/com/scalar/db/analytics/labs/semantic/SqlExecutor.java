/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.generator.DataAccess.DataAccessException;
import java.util.List;

/**
 * The application's SQL execution for construction runs. The application addresses tables by their
 * resource identifiers, imposes the row, byte, and time limits, and runs the query as the
 * construction run rather than as a user.
 */
public interface SqlExecutor {
  Result execute(String sql) throws DataAccessException;

  /**
   * @param executionId the read provenance the application returns
   */
  record Result(List<String> columns, List<List<JsonNode>> rows, String executionId) {}

  SqlExecutor UNAVAILABLE =
      sql -> {
        throw new DataAccessException("SQL execution is not available.", false);
      };
}
