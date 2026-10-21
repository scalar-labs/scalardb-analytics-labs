/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.duckdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.analytics.labs.duckdb.PackagedDuckDbExtensions.IncompatibleExtensionsException;
import com.scalar.db.analytics.labs.duckdb.PackagedDuckDbExtensions.PackagedExtension;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class PackagedDuckDbExtensionsTest {
  @Test
  void loadsThePackagedExtensionSetFromAnApplicationOwnedDirectory() throws Exception {
    try (var queryEngine = new DuckDbQueryEngine()) {
      // duckdb_extensions() scans the extension directory, which the engine forbids after startup,
      // so each extension is observed through a function it registers.
      assertThat(
              functionNames(
                  queryEngine,
                  "'postgres_scan', 'mysql_query', 'scalardb_analytics_is_read_only_query'"))
          .containsExactlyInAnyOrder(
              "postgres_scan", "mysql_query", "scalardb_analytics_is_read_only_query");

      var settings =
          queryRows(
                  queryEngine,
                  "SELECT current_setting('autoinstall_known_extensions')::VARCHAR AS autoinstall, "
                      + "current_setting('autoload_known_extensions')::VARCHAR AS autoload, "
                      + "current_setting('allow_community_extensions')::VARCHAR AS community, "
                      + "current_setting('extension_directory') AS directory")
              .getFirst();
      assertThat(settings.get("autoinstall")).isEqualTo("false");
      assertThat(settings.get("autoload")).isEqualTo("false");
      assertThat(settings.get("community")).isEqualTo("false");
      var directory = Path.of(settings.get("directory"));
      assertThat(directory).isAbsolute();
      assertThat(directory.getFileName().toString()).startsWith("scalardb-analytics-duckdb-");
      assertThat(directory.startsWith(Path.of(System.getProperty("user.home"), ".duckdb")))
          .isFalse();
    }
  }

  @Test
  void packagesProvenanceForEveryExtension() throws IOException {
    for (var name : PackagedDuckDbExtensions.EXTENSIONS) {
      var provenance = new Properties();
      try (var input =
          getClass()
              .getResourceAsStream(
                  "/META-INF/scalardb-analytics-labs/duckdb-extensions/" + name + ".properties")) {
        assertThat(input).as(name).isNotNull();
        provenance.load(input);
      }
      assertThat(provenance.getProperty("duckdbVersion"))
          .as(name)
          .matches("v[0-9]+\\.[0-9]+\\.[0-9]+");
      assertThat(provenance.getProperty("platform"))
          .as(name)
          .isIn(PackagedDuckDbExtensions.SUPPORTED_PLATFORMS);
      assertThat(provenance.getProperty("extensionAbi")).as(name).isEqualTo("CPP");
    }
    for (var scanner : List.of("postgres_scanner", "mysql_scanner")) {
      var provenance = new Properties();
      try (var input =
          getClass()
              .getResourceAsStream(
                  "/META-INF/scalardb-analytics-labs/duckdb-extensions/"
                      + scanner
                      + ".properties")) {
        provenance.load(input);
      }
      assertThat(provenance.getProperty("sourceSha256")).as(scanner).matches("[0-9a-f]{64}");
      assertThat(provenance.getProperty("sha256")).as(scanner).matches("[0-9a-f]{64}");
      assertThat(provenance.getProperty("upstreamCommit")).as(scanner).matches("[0-9a-f]{40}");
      assertThat(provenance.getProperty("signed")).as(scanner).isEqualTo("true");
      assertThat(provenance.getProperty("license")).as(scanner).isEqualTo("MIT");
      assertThat(provenance.getProperty("bundledLicenses")).as(scanner).isNotBlank();
    }
  }

  @Test
  void rejectsExtensionsBuiltForAnotherSupportedPlatform() {
    var packaged = List.of(new PackagedExtension("postgres_scanner", "v1.5.6", "osx_arm64"));

    assertThatThrownBy(
            () -> PackagedDuckDbExtensions.verifyCompatibility(packaged, "v1.5.6", "linux_amd64"))
        .isInstanceOf(IncompatibleExtensionsException.class)
        .hasMessage(
            "The packaged DuckDB extensions are built for osx_arm64, but this host is linux_amd64."
                + " Use the distribution built for linux_amd64."
                + " Supported platforms: osx_arm64, linux_amd64, linux_arm64.");
  }

  @Test
  void rejectsAnUnsupportedPlatform() {
    var packaged = List.of(new PackagedExtension("postgres_scanner", "v1.5.6", "osx_arm64"));

    assertThatThrownBy(
            () -> PackagedDuckDbExtensions.verifyCompatibility(packaged, "v1.5.6", "windows_amd64"))
        .isInstanceOf(IncompatibleExtensionsException.class)
        .hasMessage(
            "The packaged DuckDB extensions are built for osx_arm64, but this host is"
                + " windows_amd64. windows_amd64 is not supported."
                + " Supported platforms: osx_arm64, linux_amd64, linux_arm64.");
  }

  @Test
  void rejectsExtensionsBuiltForAnotherDuckDbRelease() {
    var packaged = List.of(new PackagedExtension("mysql_scanner", "v1.5.5", "osx_arm64"));

    assertThatThrownBy(
            () -> PackagedDuckDbExtensions.verifyCompatibility(packaged, "v1.5.6", "osx_arm64"))
        .isInstanceOf(IncompatibleExtensionsException.class)
        .hasMessage(
            "The packaged DuckDB extension mysql_scanner is built for DuckDB v1.5.5, but the"
                + " embedded DuckDB is v1.5.6. Build the distribution from one DuckDB version set.");
  }

  private static List<String> functionNames(DuckDbQueryEngine queryEngine, String names)
      throws IOException {
    return queryRows(
            queryEngine,
            "SELECT DISTINCT function_name FROM duckdb_functions() "
                + "WHERE function_name IN ("
                + names
                + ")")
        .stream()
        .map(row -> row.get("function_name"))
        .toList();
  }

  private static List<Map<String, String>> queryRows(DuckDbQueryEngine queryEngine, String sql)
      throws IOException {
    var rows = new ArrayList<Map<String, String>>();
    try (var result = queryEngine.execute(sql)) {
      var reader = result.reader();
      while (reader.loadNextBatch()) {
        var root = reader.getVectorSchemaRoot();
        for (var index = 0; index < root.getRowCount(); index++) {
          var row = new HashMap<String, String>();
          for (var vector : root.getFieldVectors()) {
            row.put(vector.getName(), String.valueOf(vector.getObject(index)));
          }
          rows.add(row);
        }
      }
    }
    return rows;
  }
}
