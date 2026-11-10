/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import org.jspecify.annotations.Nullable;

/**
 * The connection settings of the store: the Physical Catalog's PostgreSQL database, in which the
 * Semantic Catalog keeps its own {@code semantic_catalog} schema (see {@code
 * docs/semantic-catalog/storage.md}, "The store").
 *
 * <p>The settings come from the workspace configuration and are held only by the two catalogs. They
 * are never passed to the Query Engine, so SQL written by users or models cannot reach the log.
 *
 * @param jdbcUrl the JDBC URL of the store
 * @param username the user to connect as, or absent when the URL or the driver supplies it
 * @param password the password of that user, or absent when the URL or the driver supplies it
 * @param maximumPoolSize the size of the Semantic Catalog's own connection pool, which is separate
 *     from the Physical Catalog's connections inside ScalarDB
 */
public record StoreSettings(
    String jdbcUrl, @Nullable String username, @Nullable String password, int maximumPoolSize) {
  /** The pool size used when none is given. */
  public static final int DEFAULT_POOL_SIZE = 4;

  public StoreSettings {
    if (maximumPoolSize < 1) {
      throw new IllegalArgumentException("The pool holds at least one connection");
    }
  }

  /** Settings with the {@linkplain #DEFAULT_POOL_SIZE default pool size}. */
  public StoreSettings(String jdbcUrl, @Nullable String username, @Nullable String password) {
    this(jdbcUrl, username, password, DEFAULT_POOL_SIZE);
  }

  /** The settings without the password, which must not reach logs or error messages. */
  @Override
  public String toString() {
    return "StoreSettings[jdbcUrl=" + jdbcUrl + ", username=" + username + "]";
  }
}
