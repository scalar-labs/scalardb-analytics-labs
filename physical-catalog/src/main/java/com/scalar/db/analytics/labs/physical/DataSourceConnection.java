/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.physical;

import java.util.Objects;
import java.util.Optional;

/** Connection settings for a source database registered as a logical data source. */
public sealed interface DataSourceConnection {
  /** A PostgreSQL database. */
  record PostgreSql(String host, int port, String database, String username, Secret password)
      implements DataSourceConnection {
    public PostgreSql {
      requireText(host, "host");
      requirePort(port);
      requireText(database, "database");
      requireText(username, "username");
      Objects.requireNonNull(password, "password");
    }
  }

  /** A MySQL server, optionally scoped to one database. */
  record MySql(String host, int port, Optional<String> database, String username, Secret password)
      implements DataSourceConnection {
    public MySql {
      requireText(host, "host");
      requirePort(port);
      Objects.requireNonNull(database, "database");
      database.ifPresent(name -> requireText(name, "database"));
      requireText(username, "username");
      Objects.requireNonNull(password, "password");
    }
  }

  private static void requireText(String value, String name) {
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }

  private static void requirePort(int port) {
    if (port < 1 || port > 65_535) {
      throw new IllegalArgumentException("port must be between 1 and 65535");
    }
  }
}
