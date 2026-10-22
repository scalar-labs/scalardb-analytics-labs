/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The database the Semantic Catalog is stored in: the Physical Catalog's store, which must be
 * PostgreSQL for now. The Semantic Catalog keeps its tables in its own schema there.
 */
public record StorageSettings(String jdbcUrl, @Nullable String username, String password) {
  public StorageSettings {
    Objects.requireNonNull(jdbcUrl, "jdbcUrl");
    Objects.requireNonNull(password, "password");
    if (!jdbcUrl.startsWith("jdbc:postgresql:")) {
      throw new SemanticCatalogException(
          SemanticCatalogException.Kind.INVALID_INPUT,
          "The Semantic Catalog requires a PostgreSQL store.");
    }
  }

  @Override
  public String toString() {
    return "StorageSettings[jdbcUrl="
        + jdbcUrl
        + ", username="
        + username
        + ", password=[REDACTED]]";
  }
}
