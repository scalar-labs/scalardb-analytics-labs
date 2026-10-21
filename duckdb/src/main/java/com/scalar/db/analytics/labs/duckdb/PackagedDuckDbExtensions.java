/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.duckdb;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.jspecify.annotations.Nullable;

/**
 * Loads the DuckDB extension set packaged with this module: the product extension and DuckDB's
 * official PostgreSQL and MySQL scanners, all built for one DuckDB release and platform.
 *
 * <p>Extensions are loaded only from these packaged files. The connection must be opened with
 * {@link #connectionProperties(Path)} so DuckDB never installs or loads an extension on its own.
 */
final class PackagedDuckDbExtensions {
  static final String PRODUCT_EXTENSION = "scalardb_analytics";
  static final List<String> EXTENSIONS =
      List.of("postgres_scanner", "mysql_scanner", PRODUCT_EXTENSION);
  static final List<String> SUPPORTED_PLATFORMS =
      List.of("osx_arm64", "linux_amd64", "linux_arm64");

  private static final String EXTENSION_RESOURCE_DIRECTORY = "/native/";
  private static final String PROVENANCE_RESOURCE_DIRECTORY =
      "/META-INF/scalardb-analytics-labs/duckdb-extensions/";

  /** What a packaged extension was built for, as recorded in its provenance. */
  record PackagedExtension(String name, String duckDbVersion, String platform) {}

  /** The packaged extensions are incompatible with the DuckDB embedded in this process. */
  static final class IncompatibleExtensionsException extends Exception {
    private static final long serialVersionUID = 1L;

    IncompatibleExtensionsException(String message) {
      super(message);
    }
  }

  private PackagedDuckDbExtensions() {}

  /**
   * Returns the DuckDB settings that confine extension loading to the packaged files.
   *
   * @param extensionDirectory an application-owned directory that replaces DuckDB's default
   *     per-user extension directory
   */
  static Properties connectionProperties(Path extensionDirectory) {
    var properties = new Properties();
    // The product extension is not signed by DuckDB. The packaged scanners are verified against
    // pinned checksums at build time.
    properties.setProperty("allow_unsigned_extensions", "true");
    properties.setProperty("allow_community_extensions", "false");
    properties.setProperty("autoinstall_known_extensions", "false");
    properties.setProperty("autoload_known_extensions", "false");
    properties.setProperty("extension_directory", extensionDirectory.toAbsolutePath().toString());
    return properties;
  }

  /**
   * Loads every packaged extension from {@code extensionDirectory} and, when a catalog provider is
   * given, registers it as the product extension's catalog host.
   *
   * @return the registration to close after the connection, or {@code null} without a provider
   */
  static @Nullable CatalogHostRegistration load(
      Connection connection,
      Path extensionDirectory,
      @Nullable NativeCatalogProvider catalogProvider)
      throws SQLException, IncompatibleExtensionsException {
    verifyCompatibility(
        readPackagedExtensions(), runtimeVersion(connection), runtimePlatform(connection));
    try {
      var files = new ArrayList<Path>();
      for (var name : EXTENSIONS) {
        files.add(stage(name, extensionDirectory));
      }
      try (var statement = connection.createStatement()) {
        for (var file : files) {
          statement.execute("LOAD '" + escapeSqlLiteral(file.toAbsolutePath()) + "'");
        }
      }
      // The registration must resolve the library DuckDB just loaded, which is identified by its
      // path, so it happens before the staged files are deleted.
      return catalogProvider == null
          ? null
          : CatalogHostRegistration.register(
              extensionDirectory.resolve(fileName(PRODUCT_EXTENSION)).toAbsolutePath(),
              catalogProvider);
    } catch (IOException | IllegalStateException e) {
      throw new SQLException("Could not stage the packaged DuckDB extensions.", e);
    }
  }

  /**
   * Rejects packaged extensions that the running DuckDB cannot load, with a diagnostic that names
   * the mismatch. DuckDB would also reject them, but with a message about a single file.
   */
  static void verifyCompatibility(
      List<PackagedExtension> packaged, String runtimeVersion, String runtimePlatform)
      throws IncompatibleExtensionsException {
    for (var extension : packaged) {
      if (!extension.platform().equals(runtimePlatform)) {
        var remedy =
            SUPPORTED_PLATFORMS.contains(runtimePlatform)
                ? "Use the distribution built for " + runtimePlatform + "."
                : runtimePlatform + " is not supported.";
        throw new IncompatibleExtensionsException(
            "The packaged DuckDB extensions are built for "
                + extension.platform()
                + ", but this host is "
                + runtimePlatform
                + ". "
                + remedy
                + " Supported platforms: "
                + String.join(", ", SUPPORTED_PLATFORMS)
                + ".");
      }
      if (!extension.duckDbVersion().equals(runtimeVersion)) {
        throw new IncompatibleExtensionsException(
            "The packaged DuckDB extension "
                + extension.name()
                + " is built for DuckDB "
                + extension.duckDbVersion()
                + ", but the embedded DuckDB is "
                + runtimeVersion
                + ". Build the distribution from one DuckDB version set.");
      }
    }
  }

  static List<PackagedExtension> readPackagedExtensions() throws SQLException {
    var extensions = new ArrayList<PackagedExtension>();
    for (var name : EXTENSIONS) {
      var resource = PROVENANCE_RESOURCE_DIRECTORY + name + ".properties";
      try (var input = openResource(resource)) {
        var provenance = new Properties();
        provenance.load(input);
        extensions.add(
            new PackagedExtension(
                name,
                requiredProperty(provenance, "duckdbVersion", resource),
                requiredProperty(provenance, "platform", resource)));
      } catch (IOException e) {
        throw new SQLException("Could not read the packaged DuckDB extension provenance.", e);
      }
    }
    return extensions;
  }

  private static String requiredProperty(Properties properties, String key, String resource)
      throws IOException {
    var value = properties.getProperty(key);
    if (value == null || value.isBlank()) {
      throw new IOException(resource + " has no " + key);
    }
    return value;
  }

  private static String runtimeVersion(Connection connection) throws SQLException {
    return querySingleString(connection, "SELECT library_version FROM pragma_version()");
  }

  private static String runtimePlatform(Connection connection) throws SQLException {
    return querySingleString(connection, "SELECT platform FROM pragma_platform()");
  }

  private static String querySingleString(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement();
        var resultSet = statement.executeQuery(sql)) {
      if (!resultSet.next()) {
        throw new SQLException("DuckDB returned no row for " + sql);
      }
      return resultSet.getString(1);
    }
  }

  private static Path stage(String name, Path extensionDirectory) throws IOException {
    var file = extensionDirectory.resolve(fileName(name));
    try (var input = openResource(EXTENSION_RESOURCE_DIRECTORY + fileName(name))) {
      Files.copy(input, file);
    }
    return file;
  }

  private static InputStream openResource(String resource) throws IOException {
    var input = PackagedDuckDbExtensions.class.getResourceAsStream(resource);
    if (input == null) {
      throw new IOException("Packaged DuckDB extension resource is missing: " + resource);
    }
    return input;
  }

  private static String fileName(String extension) {
    return extension + ".duckdb_extension";
  }

  private static String escapeSqlLiteral(Path path) {
    return path.toString().replace("'", "''");
  }
}
