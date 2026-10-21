/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.physical;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Application-owned settings for the embedded Physical Catalog.
 *
 * @param storeJdbcUrl the JDBC URL of the database behind ScalarDB JDBC storage
 * @param storeUsername the database user, if the URL does not carry one
 * @param storePassword the database password
 * @param serializedCallTimeout the longest a serialized call may block its caller
 */
public record PhysicalCatalogSettings(
    String storeJdbcUrl,
    Optional<String> storeUsername,
    Secret storePassword,
    Duration serializedCallTimeout) {
  /** Bounds how long a DuckDB thread can wait for one catalog call. */
  public static final Duration DEFAULT_SERIALIZED_CALL_TIMEOUT = Duration.ofSeconds(10);

  public PhysicalCatalogSettings {
    if (storeJdbcUrl.isBlank()) {
      throw new IllegalArgumentException("storeJdbcUrl must not be blank");
    }
    Objects.requireNonNull(storeUsername, "storeUsername");
    Objects.requireNonNull(storePassword, "storePassword");
    if (serializedCallTimeout.isNegative() || serializedCallTimeout.isZero()) {
      throw new IllegalArgumentException("serializedCallTimeout must be positive");
    }
  }

  public PhysicalCatalogSettings(
      String storeJdbcUrl, Optional<String> storeUsername, Secret storePassword) {
    this(storeJdbcUrl, storeUsername, storePassword, DEFAULT_SERIALIZED_CALL_TIMEOUT);
  }
}
