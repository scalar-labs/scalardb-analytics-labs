/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.duckdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.analytics.labs.query.QueryEngineException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DuckDbQueryEngineTest {
  @Test
  void executesQueryAsArrowStream() throws Exception {
    try (var queryEngine = new DuckDbQueryEngine();
        var result = queryEngine.execute("SELECT 1 AS answer")) {
      var reader = result.reader();

      assertThat(reader.loadNextBatch()).isTrue();
      var root = reader.getVectorSchemaRoot();
      assertThat(root.getSchema().getFields()).hasSize(1);
      assertThat(root.getSchema().getFields().getFirst().getName()).isEqualTo("answer");
      assertThat(root.getVector("answer").getObject(0)).isEqualTo(1);
      assertThat(reader.loadNextBatch()).isFalse();
      assertThat(result.executionId()).isNotBlank();
    }
  }

  @Test
  void loadsPackagedProductExtension() throws Exception {
    try (var queryEngine = new DuckDbQueryEngine();
        var result = queryEngine.execute("SELECT scalardb_analytics_version() AS version")) {
      assertThat(result.reader().loadNextBatch()).isTrue();
      assertThat(result.reader().getVectorSchemaRoot().getVector("version").getObject(0).toString())
          .isEqualTo("1");
    }
  }

  @Test
  void streamsMoreThanOneArrowBatch() throws Exception {
    try (var queryEngine = new DuckDbQueryEngine();
        var result = queryEngine.execute("SELECT * FROM range(2050)")) {
      var batches = 0;
      var rows = 0;
      while (result.reader().loadNextBatch()) {
        batches++;
        rows += result.reader().getVectorSchemaRoot().getRowCount();
      }

      assertThat(batches).isGreaterThan(1);
      assertThat(rows).isEqualTo(2050);
    }
  }

  @Test
  void deniesExternalFileAccess(@TempDir Path temporaryDirectory) throws Exception {
    var csv = temporaryDirectory.resolve("values.csv");
    Files.writeString(csv, "value\n1\n");

    try (var queryEngine = new DuckDbQueryEngine()) {
      assertThatThrownBy(() -> queryEngine.execute("SELECT * FROM read_csv_auto('" + csv + "')"))
          .isInstanceOf(QueryEngineException.class)
          .hasMessage("Query execution failed.");
    }
  }

  @Test
  void disablesRuntimeExtensionDownloads() throws Exception {
    try (var queryEngine = new DuckDbQueryEngine();
        var result =
            queryEngine.execute(
                """
                SELECT current_setting('enable_external_access'),
                       current_setting('autoinstall_known_extensions'),
                       current_setting('autoload_known_extensions')
                """)) {
      assertThat(result.reader().loadNextBatch()).isTrue();
      var vectors = result.reader().getVectorSchemaRoot().getFieldVectors();
      assertThat(vectors).allSatisfy(vector -> assertThat(vector.getObject(0)).isEqualTo(false));
    }
  }

  @Test
  void preservesNullDecimalBinaryListAndStructInArrow() throws Exception {
    try (var queryEngine = new DuckDbQueryEngine();
        var result =
            queryEngine.execute(
                """
                SELECT NULL::INTEGER AS null_value,
                       123.45::DECIMAL(10, 2) AS decimal_value,
                       CAST('abc' AS BLOB) AS binary_value,
                       [1, NULL, 3] AS list_value,
                       {'name': 'alice', 'active': true} AS struct_value
                """)) {
      assertThat(result.reader().loadNextBatch()).isTrue();
      var root = result.reader().getVectorSchemaRoot();

      assertThat(root.getVector("null_value").getObject(0)).isNull();
      assertThat(root.getVector("decimal_value").getObject(0)).isEqualTo(new BigDecimal("123.45"));
      assertThat((byte[]) root.getVector("binary_value").getObject(0))
          .containsExactly('a', 'b', 'c');
      assertThat(root.getVector("list_value").getObject(0).toString()).contains("1", "null", "3");
      assertThat(root.getVector("struct_value").getObject(0).toString()).contains("alice", "true");
    }
  }
}
