/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.testcontainers.containers.PostgreSQLContainer;

/** One PostgreSQL server shared by the tests; each test gets a database of its own. */
public final class TestStore {
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16.4").withPassword("store-" + UUID.randomUUID());

  static {
    POSTGRES.start();
  }

  private TestStore() {}

  /** Creates an empty database and returns the settings of a store in it. */
  public static StorageSettings freshStore() {
    var name = "t" + UUID.randomUUID().toString().replace("-", "");
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + name);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    var url =
        "jdbc:postgresql://"
            + POSTGRES.getHost()
            + ":"
            + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
            + "/"
            + name;
    return new StorageSettings(url, POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
