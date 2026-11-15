/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.connector;

import java.util.List;

/** The result of a table metadata request: one record per requested table, in request order. */
public record TableMetadataResult(List<TableRecord> records, Retrieval retrieval) {
  public TableMetadataResult {
    records = List.copyOf(records);
  }
}
