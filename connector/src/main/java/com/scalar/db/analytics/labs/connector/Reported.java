/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.connector;

/**
 * An item a Connector read, could not read, or never offers.
 *
 * @param <T> the item's value
 */
public sealed interface Reported<T> {
  /** The Connector read the item. */
  record Value<T>(T value) implements Reported<T> {}

  /** The item exists in principle but could not be read, so the table is incomplete. */
  record Missing<T>(String reason) implements Reported<T> {}

  /** The Connector never returns the item; the table stays complete. */
  record NotOffered<T>() implements Reported<T> {}
}
