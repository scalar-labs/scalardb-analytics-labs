/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.connector;

/** The kind of table the source distinguishes. */
public sealed interface TableKind {
  record Table() implements TableKind {}

  record View() implements TableKind {}

  record External() implements TableKind {}

  /** Another kind, in the source's own term. */
  record Other(String name) implements TableKind {}
}
