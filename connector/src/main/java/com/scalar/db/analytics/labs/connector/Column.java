/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.connector;

/** One column of a table, in the source's column order. */
public record Column(
    String name, int ordinal, boolean nullable, Reported<String> sourceId, Type type) {

  /** How a column's type was observed. */
  public sealed interface Type {
    /** The Data Source type maps to an Analytics type. */
    record Mapped(String dataSourceType, AnalyticsType analyticsType) implements Type {}

    /** The Data Source type was read but has no Analytics type. */
    record Unsupported(String dataSourceType, UnsupportedReason reason) implements Type {}

    /** The column's type could not be read. */
    record Unread(String reason) implements Type {}
  }

  /** Why a column's Data Source type has no Analytics type. */
  public enum UnsupportedReason {
    NOT_MAPPED,
    MAPPING_NOT_IMPLEMENTED,
    PARAMETER_OUT_OF_RANGE
  }
}
