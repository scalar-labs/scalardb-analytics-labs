/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.connector;

import java.util.List;

/** An Analytics type, as the connectors' Analytics type model defines it. */
public sealed interface AnalyticsType {
  /** A type without parameters. */
  record Scalar(ScalarKind kind) implements AnalyticsType {}

  record Decimal(int precision, int scale) implements AnalyticsType {}

  record Array(AnalyticsType element, boolean elementNullable) implements AnalyticsType {}

  record Map(AnalyticsType key, AnalyticsType value, boolean valueNullable)
      implements AnalyticsType {}

  record Struct(List<Field> fields) implements AnalyticsType {
    public Struct {
      fields = List.copyOf(fields);
    }
  }

  record Enum(List<String> members) implements AnalyticsType {
    public Enum {
      members = List.copyOf(members);
    }
  }

  /** A named, nullable field of a {@link Struct}. */
  record Field(String name, AnalyticsType type, boolean nullable) {}

  /** The Analytics types that have no parameters. */
  enum ScalarKind {
    BOOLEAN,
    BYTE,
    SMALL_INT,
    INT,
    BIG_INT,
    FLOAT,
    DOUBLE,
    TEXT,
    BLOB,
    DATE,
    TIME,
    TIMESTAMP,
    TIMESTAMP_TZ,
    DURATION,
    INTERVAL,
    UUID,
    JSON,
    YEAR,
    TIME_WITH_TIME_ZONE,
    UNSIGNED_BYTE,
    UNSIGNED_SMALL_INT,
    UNSIGNED_INT,
    UNSIGNED_BIG_INT
  }
}
