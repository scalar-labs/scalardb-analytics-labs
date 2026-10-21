/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.physical;

import java.util.Optional;

/**
 * A registered logical data source as resolved through the Physical Catalog.
 *
 * <p>The descriptor identifies where the source lives but never carries its credentials.
 */
public record DataSourceDescriptor(
    String catalogName,
    String name,
    Type type,
    String host,
    int port,
    Optional<String> database,
    String username) {

  /** The source database products supported by the MVP. */
  public enum Type {
    POSTGRESQL,
    MYSQL
  }
}
