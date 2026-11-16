/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.model.PhysicalCatalogReference;
import com.scalar.db.analytics.labs.semantic.model.SemanticCatalogException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * The SQL execution the application provides to build tasks that read data values (see {@code
 * docs/semantic-catalog/generators.md}, "Data observations" and "Sampling", and {@code
 * docs/application.md}, "Startup, configuration, and isolation").
 *
 * <p>A build task declares the tables it reads and how: a sampled table is read through a sample
 * bounded by the domain's sample size, and a whole table is read whole, as the side a sampled value
 * is looked up in. The application rejects a statement that reads anything else, applies the
 * sampling itself, and caps rows, bytes, and time. The build task is the caller; it never reaches a
 * connector directly.
 */
public interface ConstructionSql {
  /**
   * Runs one read-only statement over the declared inputs.
   *
   * @throws ConstructionSqlException when the statement is rejected or cannot be run
   */
  Outcome execute(Request request);

  /**
   * One statement and the tables it may read.
   *
   * @param sql one read-only SELECT statement addressing each declared table by its data-source
   *     registration, namespace, and table name
   * @param inputs the declared tables, each at most once
   */
  record Request(String sql, List<Input> inputs) {
    public Request {
      Objects.requireNonNull(sql, "sql");
      inputs = List.copyOf(inputs);
      if (sql.isBlank()) {
        throw new IllegalArgumentException("A construction query has a statement");
      }
      if (inputs.isEmpty()) {
        throw new IllegalArgumentException("A construction query declares the tables it reads");
      }
      var tables = new HashSet<PhysicalCatalogReference.Table>();
      for (var input : inputs) {
        if (!tables.add(input.table())) {
          throw new IllegalArgumentException(
              "A construction query declares " + input.table().canonical() + " more than once");
        }
      }
    }
  }

  /** A declared table and how it is read. */
  record Input(PhysicalCatalogReference.Table table, Reading reading) {
    public Input {
      Objects.requireNonNull(table, "table");
      Objects.requireNonNull(reading, "reading");
    }
  }

  /** How a declared table is read. */
  enum Reading {
    /** Through a sample bounded by the domain's sample size; a table within it is read whole. */
    SAMPLED,
    /** Whole, such as the side each sampled value is looked up in. */
    WHOLE
  }

  /** What running a statement gave. */
  sealed interface Outcome {
    /**
     * The statement ran to completion.
     *
     * @param columns the result's column names
     * @param rows the result's rows, each value as JSON
     * @param ranges what was read of each declared table, in the order declared
     * @param sources how each data source read was reached, one per registration read
     */
    record Read(
        List<String> columns,
        List<List<JsonNode>> rows,
        List<Range> ranges,
        List<SourceRead> sources)
        implements Outcome {
      public Read {
        columns = List.copyOf(columns);
        rows = rows.stream().map(List::copyOf).toList();
        ranges = List.copyOf(ranges);
        sources = List.copyOf(sources);
      }
    }

    /**
     * The statement exceeded a row, byte, or time limit and returned nothing. The items it was to
     * decide stay undecidable.
     */
    record LimitExceeded(String reason) implements Outcome {
      public LimitExceeded {
        Objects.requireNonNull(reason, "reason");
      }
    }
  }

  /**
   * What was read of one declared table.
   *
   * @param extent whether the table was read through a sample or whole
   * @param rowsRead how many of its rows were read
   * @param readAt when the statement started reading
   */
  record Range(PhysicalCatalogReference.Table table, Extent extent, long rowsRead, Instant readAt) {
    public Range {
      Objects.requireNonNull(table, "table");
      Objects.requireNonNull(extent, "extent");
      Objects.requireNonNull(readAt, "readAt");
      if (rowsRead < 0) {
        throw new IllegalArgumentException("rowsRead must not be negative");
      }
    }
  }

  /** Whether a table was read through a sample or whole. */
  enum Extent {
    SAMPLE,
    WHOLE_TABLE
  }

  /**
   * How one data source was read. Neither read path returns read provenance yet, so a read records
   * the path and the DuckDB extension it read through instead.
   *
   * @param registration the data-source registration
   * @param readPath the read path the source is attached through
   * @param extension the DuckDB extension that read it
   * @param extensionVersion that extension's version as DuckDB reports it
   */
  record SourceRead(
      String registration, ReadPath readPath, String extension, String extensionVersion) {
    public SourceRead {
      Objects.requireNonNull(registration, "registration");
      Objects.requireNonNull(readPath, "readPath");
      Objects.requireNonNull(extension, "extension");
      Objects.requireNonNull(extensionVersion, "extensionVersion");
    }
  }

  /** The read path a data source is attached through (see {@code docs/query-engine.md}). */
  enum ReadPath {
    CONNECTOR,
    SCANNER
  }

  /** A statement was rejected before it ran, or failed while running. */
  final class ConstructionSqlException extends SemanticCatalogException {
    private static final long serialVersionUID = 1L;

    public ConstructionSqlException(String message) {
      super(message);
    }

    public ConstructionSqlException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
