/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.connector;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * The scalardb-analytics-connectors distribution package carried by this module, staged into a
 * directory with its layout intact.
 *
 * <p>The package's parts find one another relative to themselves: the DuckDB extension loads the
 * library from {@code ../lib} and initializes it with the {@code adbc-drivers} directory beside its
 * own, and each driver manifest names its library relative to itself. Staging therefore copies the
 * whole package and never moves one part.
 *
 * <p>A process stages the package once ({@link #forProcess()}), and every user of the library loads
 * it from there. The dynamic loader identifies the library by its install name, so a second staged
 * copy would resolve to the library already loaded; one copy keeps every user on the same library
 * and the one initialization it allows. The copy stays until the process exits, because drivers are
 * loaded only when a Data Source opens and loaded libraries are never unloaded.
 *
 * @param root the staged package
 * @param library the connector API library
 * @param extension the DuckDB loadable extension
 * @param adbcDrivers the directory of the ADBC drivers and their manifests
 */
public record ConnectorPackage(Path root, Path library, Path extension, Path adbcDrivers) {
  private static final String RESOURCE_DIRECTORY = "/native/connectors/";

  /** The name DuckDB loads the package's extension under. */
  public static final String EXTENSION_NAME = "scalardb_analytics_connectors";

  private static @Nullable ConnectorPackage processPackage;

  /**
   * The package staged for this process, staged into a private temporary directory on first use and
   * deleted when the process exits.
   */
  public static synchronized ConnectorPackage forProcess() throws IOException {
    if (processPackage == null) {
      var staged = stage(Files.createTempDirectory("scalardb-analytics-connectors-"));
      Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(staged::deleteQuietly));
      processPackage = staged;
    }
    return processPackage;
  }

  /** Copies the carried package into {@code directory}, which must be empty. */
  static ConnectorPackage stage(Path directory) throws IOException {
    try (var files =
        new BufferedReader(
            new InputStreamReader(
                resource(RESOURCE_DIRECTORY + "files"), StandardCharsets.UTF_8))) {
      for (var file = files.readLine(); file != null; file = files.readLine()) {
        var target = directory.resolve(file).normalize();
        if (!target.startsWith(directory)) {
          throw new IOException("The connectors package lists a file outside it: " + file);
        }
        Files.createDirectories(target.getParent());
        try (var input = resource(RESOURCE_DIRECTORY + file)) {
          Files.copy(input, target);
        }
      }
    }
    var extension = directory.resolve("duckdb").resolve(EXTENSION_NAME + ".duckdb_extension");
    return new ConnectorPackage(
        directory,
        directory.resolve("lib").resolve(System.mapLibraryName("connector_api")),
        extension,
        directory.resolve("adbc-drivers"));
  }

  /** Deletes the staged package as the process exits; a file that cannot be deleted stays. */
  private void deleteQuietly() {
    try (Stream<Path> paths = Files.walk(root)) {
      for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    } catch (IOException ignored) {
      // Nothing can report a failure during shutdown.
    }
  }

  private static InputStream resource(String resource) throws IOException {
    var input = ConnectorPackage.class.getResourceAsStream(resource);
    if (input == null) {
      throw new IOException("Packaged connectors resource is missing: " + resource);
    }
    return input;
  }
}
