/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.duckdb;

/**
 * Serves catalog calls that the product DuckDB extension makes through its host ABI.
 *
 * <p>Calls are unary gRPC calls in serialized form, identified by the fully qualified gRPC method
 * name. The extension may call from any thread, including DuckDB worker threads, and concurrently.
 * An implementation decides which methods it serves and must keep credentials out of every response
 * and failure, because the extension can expose both to SQL callers.
 */
@FunctionalInterface
public interface NativeCatalogProvider {
  Result call(String fullMethodName, byte[] request);

  /** The outcome of one call. */
  sealed interface Result {
    /** A serialized response message. */
    record Response(byte[] message) implements Result {}

    /** A serialized {@code google.rpc.Status} that describes the failure. */
    record Failure(byte[] status) implements Result {}
  }
}
