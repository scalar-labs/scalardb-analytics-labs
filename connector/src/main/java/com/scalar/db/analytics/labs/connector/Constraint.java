/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.connector;

import java.util.List;
import java.util.Optional;

/** One constraint of a table. */
public sealed interface Constraint {
  String name();

  boolean enforced();

  record PrimaryKey(String name, boolean enforced, List<String> columns) implements Constraint {
    public PrimaryKey {
      columns = List.copyOf(columns);
    }
  }

  /**
   * Uniqueness from a declared constraint or a unique index, which may be partial.
   *
   * @param condition the predicate of a partial unique index
   */
  record Unique(
      String name,
      boolean enforced,
      List<String> columns,
      UniqueKind uniqueKind,
      Optional<String> condition)
      implements Constraint {
    public Unique {
      columns = List.copyOf(columns);
    }
  }

  record ForeignKey(
      String name,
      boolean enforced,
      List<String> columns,
      List<String> referencedTable,
      List<String> referencedColumns)
      implements Constraint {
    public ForeignKey {
      columns = List.copyOf(columns);
      referencedTable = List.copyOf(referencedTable);
      referencedColumns = List.copyOf(referencedColumns);
    }
  }

  record Check(String name, boolean enforced, String expression) implements Constraint {}

  /** Whether uniqueness comes from a declared constraint or a unique index. */
  enum UniqueKind {
    CONSTRAINT,
    INDEX
  }
}
