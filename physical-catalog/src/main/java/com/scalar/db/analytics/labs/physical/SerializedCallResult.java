/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.physical;

/**
 * The outcome of a serialized unary catalog call.
 *
 * <p>Successful calls carry the serialized response message. Failed calls carry a serialized {@code
 * google.rpc.Status} whose message and details contain no credentials.
 */
public sealed interface SerializedCallResult {
  record Response(byte[] message) implements SerializedCallResult {}

  record Failure(byte[] status) implements SerializedCallResult {}
}
