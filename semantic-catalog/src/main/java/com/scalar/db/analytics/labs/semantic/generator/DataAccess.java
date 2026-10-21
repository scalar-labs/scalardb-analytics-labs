/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Reads data values for a Generator. Every query result is recorded as a data observation in the
 * same commit as the Generator's results, so a {@code data-check} can reference it.
 */
public interface DataAccess {
  /**
   * Runs one read-only query through the application's SQL execution, within the construction-run
   * limits.
   *
   * @param check the kind of check, which with the target identifies the observation's Input
   *     Resource
   * @param target what the check examines, such as a set of Fields or a join
   * @param tables the resource identifiers of the tables the query reads
   * @throws DataAccessException when the query fails or exceeds a limit
   */
  DataObservation query(String check, Object target, String sql, List<String> tables)
      throws DataAccessException;

  /**
   * A query result and the data observation that records it.
   *
   * @param rows rows of JSON values, in the column order
   */
  record DataObservation(
      String inputResource,
      String inputObservation,
      String digest,
      List<String> columns,
      List<List<JsonNode>> rows) {}

  /** A query that could not produce a complete result. */
  final class DataAccessException extends Exception {
    private static final long serialVersionUID = 1L;

    private final boolean limitExceeded;

    public DataAccessException(String message, boolean limitExceeded) {
      super(message);
      this.limitExceeded = limitExceeded;
    }

    /** Whether a row, byte, or time limit stopped the query, which makes a check undecidable. */
    public boolean limitExceeded() {
      return limitExceeded;
    }
  }
}
