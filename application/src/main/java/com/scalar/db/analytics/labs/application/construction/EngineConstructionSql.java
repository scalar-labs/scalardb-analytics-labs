/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.construction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scalar.db.analytics.labs.application.ApplicationRuntime;
import com.scalar.db.analytics.labs.application.ArrowJson;
import com.scalar.db.analytics.labs.duckdb.DuckDbQueryEngine;
import com.scalar.db.analytics.labs.duckdb.DuckDbQueryExecutionOptions;
import com.scalar.db.analytics.labs.physical.PhysicalTable;
import com.scalar.db.analytics.labs.query.QueryEngineException;
import com.scalar.db.analytics.labs.query.QueryError;
import com.scalar.db.analytics.labs.semantic.ConstructionSql;
import com.scalar.db.analytics.labs.semantic.model.PhysicalCatalogReference;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;

/**
 * Construction-run SQL over the embedded Query Engine (see {@code docs/application.md}, "Startup,
 * configuration, and isolation").
 *
 * <p>A request is checked and rewritten before it runs:
 *
 * <ol>
 *   <li>every declared table must be registered in the Physical Catalog;
 *   <li>the statement is parsed into DuckDB's serialized parse tree, and every base-table reference
 *       must name a declared table or a common table expression of the statement, while every
 *       declared table must be read;
 *   <li>each reference to a sampled table gets a reservoir sample of the domain's sample size, with
 *       a fixed seed so that every reference to one table reads the same rows, and the tree is
 *       turned back into SQL;
 *   <li>what is read of each table is counted, a sampled one only up to one row past the sample
 *       size, so the count tells whether the sample covered the whole table.
 * </ol>
 *
 * <p>The counts and the statement share one time limit and run with row and byte limits on the same
 * restricted engine as a user's query. The engine starts, with every data source of the catalog
 * attached, on the first request, and runs one query at a time, so requests run one after another.
 */
public final class EngineConstructionSql implements ConstructionSql {
  /** The most rows a construction query may return. */
  static final long ROW_LIMIT = 10_000;

  /** The most bytes a construction query's result may hold. */
  static final long BYTE_LIMIT = 64L * 1024 * 1024;

  /** The seed of every sample, so a statement reads the same rows of a table at each reference. */
  static final int SAMPLE_SEED = 20_261_116;

  private static final ObjectMapper JSON = new ObjectMapper();

  private final ApplicationRuntime runtime;
  private final String catalogName;
  private final ConstructionSqlSettings settings;
  private final Clock clock;
  private @Nullable DuckDbQueryEngine engine;
  private @Nullable JsonNode sample;

  public EngineConstructionSql(
      ApplicationRuntime runtime, String catalogName, ConstructionSqlSettings settings) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.catalogName = Objects.requireNonNull(catalogName, "catalogName");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.clock = Clock.systemUTC();
  }

  @Override
  public synchronized Outcome execute(Request request) {
    requireRegistered(request.inputs());
    var engine = engine();
    // The time limit covers the statement and its counts, not starting the engine.
    var deadline = System.nanoTime() + settings.timeLimit().toNanos();
    try {
      var sql = scopedSql(engine, request, deadline);
      var readAt = clock.instant();
      var ranges = new ArrayList<Range>();
      for (var input : request.inputs()) {
        ranges.add(range(engine, input, readAt, deadline));
      }
      var result = run(engine, sql, deadline);
      return new Outcome.Read(result.columns(), result.rows(), ranges, sources(engine, request));
    } catch (LimitExceeded e) {
      return new Outcome.LimitExceeded(e.reason);
    }
  }

  private DuckDbQueryEngine engine() {
    if (engine == null) {
      try {
        engine = runtime.startQueryEngine(catalogName);
      } catch (QueryEngineException e) {
        throw new ConstructionSqlException(e.error().message(), e);
      } catch (IllegalStateException e) {
        throw new ConstructionSqlException(
            "Construction-run SQL needs the runtime's Query Engine, which is already running.", e);
      }
    }
    return engine;
  }

  // Scope

  private void requireRegistered(List<Input> inputs) {
    var tablesBySource = new HashMap<String, List<PhysicalTable>>();
    for (var input : inputs) {
      var table = input.table();
      var registration = table.registration();
      if (!tablesBySource.containsKey(registration)) {
        if (runtime.physicalCatalog().findDataSource(catalogName, registration).isEmpty()) {
          throw new ConstructionSqlException(
              "The declared table " + table.canonical() + " has no registered data source.");
        }
        tablesBySource.put(
            registration, runtime.physicalCatalog().listTables(catalogName, registration));
      }
      var registered =
          tablesBySource.get(registration).stream()
              .anyMatch(
                  physical ->
                      physical.namespaceNames().equals(List.of(table.namespace()))
                          && physical.name().equals(table.table()));
      if (!registered) {
        throw new ConstructionSqlException(
            "The declared table " + table.canonical() + " is not registered.");
      }
    }
  }

  /** Checks what the statement reads against the declared inputs and applies their sampling. */
  private String scopedSql(DuckDbQueryEngine engine, Request request, long deadline) {
    var statement = StatementTables.of(serialize(engine, request.sql(), deadline));
    var declared = new LinkedHashMap<PhysicalCatalogReference.Table, Input>();
    request.inputs().forEach(input -> declared.put(input.table(), input));
    var read = new HashSet<PhysicalCatalogReference.Table>();
    var sampled = false;
    for (var reference : statement.baseTables()) {
      if (statement.isCommonTableExpression(reference)) {
        continue;
      }
      var input = declaredTable(reference).map(declared::get);
      if (input.isEmpty()) {
        throw new ConstructionSqlException(
            "The statement reads "
                + String.join(
                    ".",
                    List.of(
                            StatementTables.catalog(reference),
                            StatementTables.schema(reference),
                            StatementTables.table(reference))
                        .stream()
                        .filter(name -> !name.isEmpty())
                        .toList())
                + ", which is not a declared table named by its data source, namespace, and"
                + " table.");
      }
      read.add(input.get().table());
      if (input.get().reading() == Reading.SAMPLED) {
        reference.set("sample", sample(engine, deadline).deepCopy());
        sampled = true;
      }
    }
    for (var table : declared.keySet()) {
      if (!read.contains(table)) {
        throw new ConstructionSqlException(
            "The statement does not read the declared table " + table.canonical() + ".");
      }
    }
    return sampled ? deserialize(engine, statement.tree(), deadline) : request.sql();
  }

  private static Optional<PhysicalCatalogReference.Table> declaredTable(ObjectNode reference) {
    var catalog = StatementTables.catalog(reference);
    var schema = StatementTables.schema(reference);
    var table = StatementTables.table(reference);
    if (catalog.isEmpty() || schema.isEmpty() || table.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new PhysicalCatalogReference.Table(catalog, schema, table));
  }

  /** The serialized sample clause every sampled reference gets. */
  private JsonNode sample(DuckDbQueryEngine engine, long deadline) {
    if (sample == null) {
      var template =
          "SELECT 1 FROM t TABLESAMPLE reservoir("
              + settings.sampleSize()
              + " ROWS) REPEATABLE ("
              + SAMPLE_SEED
              + ")";
      var tree = serialize(engine, template, deadline);
      sample = tree.path("statements").path(0).path("node").path("from_table").path("sample");
      if (!sample.isObject()) {
        throw new ConstructionSqlException("The Query Engine gave no sample clause.");
      }
    }
    return sample;
  }

  private static JsonNode serialize(DuckDbQueryEngine engine, String sql, long deadline) {
    var json =
        scalar(
            engine, "SELECT CAST(json_serialize_sql(" + literal(sql) + ") AS VARCHAR)", deadline);
    try {
      return JSON.readTree(json.asText());
    } catch (JsonProcessingException e) {
      throw new ConstructionSqlException("The Query Engine gave an unreadable parse tree.", e);
    }
  }

  private static String deserialize(DuckDbQueryEngine engine, JsonNode tree, long deadline) {
    return scalar(
            engine,
            "SELECT json_deserialize_sql(CAST(" + literal(tree.toString()) + " AS JSON))",
            deadline)
        .asText();
  }

  // Ranges and provenance

  private Range range(DuckDbQueryEngine engine, Input input, Instant readAt, long deadline) {
    var table = quoted(input.table());
    return switch (input.reading()) {
      case SAMPLED -> {
        var bound = settings.sampleSize();
        var rows =
            scalar(
                    engine,
                    "SELECT count(*) FROM (SELECT 1 FROM " + table + " LIMIT " + (bound + 1) + ")",
                    deadline)
                .asLong();
        yield rows > bound
            ? new Range(input.table(), Extent.SAMPLE, bound, readAt)
            : new Range(input.table(), Extent.WHOLE_TABLE, rows, readAt);
      }
      case WHOLE -> {
        var rows = scalar(engine, "SELECT count(*) FROM " + table, deadline).asLong();
        yield new Range(input.table(), Extent.WHOLE_TABLE, rows, readAt);
      }
    };
  }

  private static List<SourceRead> sources(DuckDbQueryEngine engine, Request request) {
    var attached = new HashMap<String, DuckDbQueryEngine.AttachedSource>();
    engine.attachedSources().forEach(source -> attached.put(source.name(), source));
    var sources = new LinkedHashMap<String, SourceRead>();
    for (var input : request.inputs()) {
      var registration = input.table().registration();
      if (sources.containsKey(registration)) {
        continue;
      }
      var source = attached.get(registration);
      if (source == null) {
        throw new ConstructionSqlException(
            "The data source of " + input.table().canonical() + " is not attached.");
      }
      var readPath =
          switch (source.readPath().kind()) {
            case CONNECTOR -> ReadPath.CONNECTOR;
            case SCANNER -> ReadPath.SCANNER;
          };
      sources.put(
          registration,
          new SourceRead(
              registration, readPath, source.readPath().extension(), source.extensionVersion()));
    }
    return List.copyOf(sources.values());
  }

  // Running queries

  private record Rows(List<String> columns, List<List<JsonNode>> rows) {}

  /** A query exceeded a row, byte, or time limit. */
  private static final class LimitExceeded extends RuntimeException {
    private static final long serialVersionUID = 1L;

    final String reason;

    LimitExceeded(String reason) {
      super(reason, null, false, false);
      this.reason = reason;
    }
  }

  private static JsonNode scalar(DuckDbQueryEngine engine, String sql, long deadline) {
    var result = run(engine, sql, deadline);
    if (result.rows().size() != 1 || result.rows().get(0).size() != 1) {
      throw new ConstructionSqlException("The Query Engine gave no single value.");
    }
    return result.rows().get(0).get(0);
  }

  private static Rows run(DuckDbQueryEngine engine, String sql, long deadline) {
    var remaining = deadline - System.nanoTime();
    if (remaining <= 0) {
      throw new LimitExceeded("The construction query exceeded its time limit.");
    }
    var options =
        new DuckDbQueryExecutionOptions(
            OptionalLong.of(ROW_LIMIT),
            OptionalLong.of(BYTE_LIMIT),
            Optional.of(Duration.ofNanos(remaining)),
            OptionalLong.empty());
    try (var result = engine.execute(sql, options)) {
      var reader = result.reader();
      var columns =
          reader.getVectorSchemaRoot().getSchema().getFields().stream()
              .map(field -> field.getName())
              .toList();
      var rows = new ArrayList<List<JsonNode>>();
      while (reader.loadNextBatch()) {
        var root = reader.getVectorSchemaRoot();
        for (var index = 0; index < root.getRowCount(); index++) {
          var row = new ArrayList<JsonNode>(root.getFieldVectors().size());
          for (var vector : root.getFieldVectors()) {
            var value = ArrowJson.value(vector, index);
            row.add(value == null ? NullNode.getInstance() : JSON.valueToTree(value));
          }
          rows.add(row);
        }
      }
      return new Rows(columns, rows);
    } catch (QueryEngineException e) {
      if (e.error() instanceof QueryError.ResourceLimitExceeded limit) {
        throw new LimitExceeded(limit.message());
      }
      throw new ConstructionSqlException(e.error().message(), e);
    } catch (IOException e) {
      throw new ConstructionSqlException("The construction query result could not be read.", e);
    }
  }

  private static String quoted(PhysicalCatalogReference.Table table) {
    return identifier(table.registration())
        + "."
        + identifier(table.namespace())
        + "."
        + identifier(table.table());
  }

  private static String identifier(String name) {
    return "\"" + name.replace("\"", "\"\"") + "\"";
  }

  private static String literal(String value) {
    return "'" + value.replace("'", "''") + "'";
  }
}
