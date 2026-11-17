/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.testcontainers.containers.PostgreSQLContainer;

/** One PostgreSQL store shared by the tests, with a fresh database or domain per use. */
final class TestStore {
  private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4");
  private static final AtomicInteger NAMES = new AtomicInteger();

  /** The Query Engine's parser every store opened by the tests parses expression values with. */
  static final SqlParser PARSER = new DuckDbSqlParser();

  static {
    POSTGRES.start();
  }

  private TestStore() {}

  static StoreSettings settings() {
    return settings(POSTGRES.getDatabaseName());
  }

  static StoreSettings settings(String database) {
    return new StoreSettings(url(database), POSTGRES.getUsername(), POSTGRES.getPassword(), 8);
  }

  /** The shared database with a connection pool of a given size. */
  static StoreSettings settingsWithPool(int maximumPoolSize) {
    return new StoreSettings(
        url(POSTGRES.getDatabaseName()),
        POSTGRES.getUsername(),
        POSTGRES.getPassword(),
        maximumPoolSize);
  }

  /** Creates an empty database on the same server. */
  static String newDatabase() throws SQLException {
    var name = "store_" + NAMES.incrementAndGet();
    try (var connection = connect(POSTGRES.getDatabaseName());
        var statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + name);
    }
    return name;
  }

  /** A domain identifier no other test uses. */
  static String newDomain() {
    return "domain-" + NAMES.incrementAndGet();
  }

  static Connection connect(String database) throws SQLException {
    return DriverManager.getConnection(
        url(database), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  static Connection connect() throws SQLException {
    return connect(POSTGRES.getDatabaseName());
  }

  private static String url(String database) {
    return "jdbc:postgresql://"
        + POSTGRES.getHost()
        + ":"
        + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        + "/"
        + database;
  }
}
