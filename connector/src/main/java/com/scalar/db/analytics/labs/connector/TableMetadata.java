/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.connector;

import java.util.List;

/**
 * The source-independent metadata of one table that exists, as a Connector returned it.
 *
 * @param table the table's name path, the binding plans read it by
 * @param columnsMayBeHidden whether the source's catalog may silently omit columns the account
 *     cannot see
 */
public record TableMetadata(
    List<String> table,
    TableKind kind,
    Reported<String> sourceId,
    List<Column> columns,
    boolean columnsMayBeHidden,
    Reported<List<Constraint>> constraints) {
  public TableMetadata {
    table = List.copyOf(table);
    columns = List.copyOf(columns);
  }
}
