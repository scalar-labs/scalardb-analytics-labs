/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.query;

/** Executes SQL through the embedded Query Engine. */
public interface QueryEngine extends AutoCloseable {
  QueryResult execute(String sql);

  @Override
  void close();
}
