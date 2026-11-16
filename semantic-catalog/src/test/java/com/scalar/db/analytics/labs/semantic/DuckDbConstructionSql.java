/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A stand-in for the application's construction-run SQL over an in-memory DuckDB, the dialect of
 * the Query Engine: each statement runs as written against tables a test creates under their
 * data-source registration, namespace, and table name.
 *
 * <p>It does not sample. A sampled table larger than the sample size is still read whole, and the
 * range reports a sample of the sample size, as the application would; a table within it is
 * reported read whole. Every statement it runs is kept, so a test can see what was read.
 */
final class DuckDbConstructionSql implements ConstructionSql, AutoCloseable {
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
  static final Instant READ_AT = Instant.parse("2026-11-16T00:00:00Z");

  private final Connection connection;
  private final long sampleSize;
  final List<Request> requests = new ArrayList<>();
  private boolean overLimit;

  DuckDbConstructionSql(long sampleSize) {
    this.sampleSize = sampleSize;
    try {
      connection = DriverManager.getConnection("jdbc:duckdb:");
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Runs setup statements, such as attaching a registration and creating its tables. */
  DuckDbConstructionSql run(String... statements) {
    try (var statement = connection.createStatement()) {
      for (var sql : statements) {
        statement.execute(sql);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    return this;
  }

  /** Makes every later statement exceed a limit of the application. */
  void exceedLimits() {
    overLimit = true;
  }

  @Override
  public synchronized Outcome execute(Request request) {
    requests.add(request);
    if (overLimit) {
      return new Outcome.LimitExceeded("time limit of 1s exceeded");
    }
    try (var statement = connection.createStatement()) {
      var ranges = new ArrayList<Range>();
      for (var input : request.inputs()) {
        var table = quoted(input.table());
        long rows;
        try (var count = statement.executeQuery("SELECT count(*) FROM " + table)) {
          count.next();
          rows = count.getLong(1);
        }
        ranges.add(
            input.reading() == Reading.SAMPLED && rows > sampleSize
                ? new Range(input.table(), Extent.SAMPLE, sampleSize, READ_AT)
                : new Range(input.table(), Extent.WHOLE_TABLE, rows, READ_AT));
      }
      var columns = new ArrayList<String>();
      var rows = new ArrayList<List<JsonNode>>();
      try (var result = statement.executeQuery(request.sql())) {
        var meta = result.getMetaData();
        for (var i = 1; i <= meta.getColumnCount(); i++) {
          columns.add(meta.getColumnLabel(i));
        }
        while (result.next()) {
          var row = new ArrayList<JsonNode>();
          for (var i = 1; i <= columns.size(); i++) {
            var value = result.getObject(i);
            row.add(
                value == null
                    ? JSON.nullNode()
                    : value instanceof Number number
                        ? JSON.numberNode(number.longValue())
                        : JSON.textNode(value.toString()));
          }
          rows.add(row);
        }
      }
      var sources =
          request.inputs().stream()
              .map(input -> input.table().registration())
              .distinct()
              .map(registration -> new SourceRead(registration, "test", "1"))
              .toList();
      return new Outcome.Read(columns, rows, ranges, sources);
    } catch (SQLException e) {
      throw new ConstructionSqlException("The stand-in could not run: " + e.getMessage(), e);
    }
  }

  private static String quoted(
      com.scalar.db.analytics.labs.semantic.model.PhysicalCatalogReference.Table table) {
    return "\""
        + table.registration()
        + "\".\""
        + table.namespace()
        + "\".\""
        + table.table()
        + "\"";
  }

  @Override
  public void close() throws SQLException {
    connection.close();
  }
}
