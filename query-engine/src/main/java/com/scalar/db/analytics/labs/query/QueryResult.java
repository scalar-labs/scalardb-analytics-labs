/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.query;

import org.apache.arrow.vector.ipc.ArrowReader;

/** Owns one streaming Arrow result and its Query Engine resources. */
public interface QueryResult extends AutoCloseable {
  String executionId();

  ArrowReader reader();

  @Override
  void close();
}
