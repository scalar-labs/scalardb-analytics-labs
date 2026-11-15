/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.connector;

import java.util.List;

/** One table list entry. */
public sealed interface ListedTable {
  record Table(List<String> names, TableKind kind) implements ListedTable {
    public Table {
      names = List.copyOf(names);
    }
  }

  /** A range whose list could not be read; it is not known to be empty. */
  record Unreadable(List<String> scope, String reason) implements ListedTable {
    public Unreadable {
      scope = List.copyOf(scope);
    }
  }
}
