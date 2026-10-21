/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.physical;

import java.util.List;

/** A source table and its columns as recorded by the Physical Catalog. */
public record PhysicalTable(
    String dataSourceName, List<String> namespaceNames, String name, List<Column> columns) {
  public PhysicalTable {
    namespaceNames = List.copyOf(namespaceNames);
    columns = List.copyOf(columns);
  }

  /** One recorded source column. */
  public record Column(String name, String type, int ordinalPosition, boolean nullable) {}
}
