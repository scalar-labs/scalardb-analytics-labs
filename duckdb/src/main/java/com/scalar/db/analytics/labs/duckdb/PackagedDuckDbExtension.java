/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.duckdb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import org.jspecify.annotations.Nullable;

/** Loads the product-owned DuckDB extension from this module's packaged resources. */
final class PackagedDuckDbExtension {
  private static final String EXTENSION_FILE_NAME = "scalardb_analytics.duckdb_extension";
  private static final String RESOURCE_PATH = "/native/" + EXTENSION_FILE_NAME;

  private PackagedDuckDbExtension() {}

  /**
   * Loads the extension and, when a catalog provider is given, registers it as the extension's
   * catalog host.
   *
   * @return the registration to close after the connection, or {@code null} without a provider
   */
  static @Nullable CatalogHostRegistration load(
      Connection connection, @Nullable NativeCatalogProvider catalogProvider) throws SQLException {
    Path temporaryDirectory = null;
    try (var extension = PackagedDuckDbExtension.class.getResourceAsStream(RESOURCE_PATH)) {
      if (extension == null) {
        throw new IOException("Packaged DuckDB extension is missing: " + RESOURCE_PATH);
      }

      temporaryDirectory = Files.createTempDirectory("scalardb-analytics-duckdb-");
      var extensionFile = temporaryDirectory.resolve(EXTENSION_FILE_NAME);
      Files.copy(extension, extensionFile);

      try (var statement = connection.createStatement()) {
        statement.execute("LOAD '" + escapeSqlLiteral(extensionFile.toAbsolutePath()) + "'");
      }
      // The registration must resolve the library DuckDB just loaded, which is identified by its
      // path, so it happens before the staged file is deleted.
      return catalogProvider == null
          ? null
          : CatalogHostRegistration.register(extensionFile.toAbsolutePath(), catalogProvider);
    } catch (IOException | IllegalStateException e) {
      throw new SQLException("Could not stage the packaged DuckDB extension.", e);
    } finally {
      deleteTemporaryExtension(temporaryDirectory);
    }
  }

  private static String escapeSqlLiteral(Path path) {
    return path.toString().replace("'", "''");
  }

  private static void deleteTemporaryExtension(@Nullable Path temporaryDirectory) {
    if (temporaryDirectory == null) {
      return;
    }
    try {
      Files.deleteIfExists(temporaryDirectory.resolve(EXTENSION_FILE_NAME));
      Files.deleteIfExists(temporaryDirectory);
    } catch (IOException ignored) {
      // The operating system can retain the loaded library until process exit.
    }
  }
}
