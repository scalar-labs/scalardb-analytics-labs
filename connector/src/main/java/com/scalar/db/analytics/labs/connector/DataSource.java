/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.connector;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * An open Data Source of one Connector.
 *
 * <p>Each request runs on a session of its own, opened for the request and closed after it, so a
 * Data Source may serve requests from several threads at once.
 */
public final class DataSource implements AutoCloseable {
  private final ConnectorLibrary library;
  private final MemorySegment handle;
  private final AtomicBoolean closed = new AtomicBoolean();

  DataSource(ConnectorLibrary library, MemorySegment handle) {
    this.library = library;
    this.handle = handle;
  }

  /**
   * Lists the readable namespaces.
   *
   * @param timeout limits the request; zero sets no limit
   */
  public List<ListedNamespace> namespaces(Duration timeout) throws ConnectorException {
    return withSession(
        (functions, session, arena) -> {
          var out = arena.allocate(ADDRESS);
          functions.check(
              (MemorySegment)
                  functions.namespaceList.invokeExact(
                      session, MemorySegment.NULL, timeout.toMillis(), MemorySegment.NULL, out));
          var list = out.get(ADDRESS, 0);
          try {
            return NativeReader.namespaceList(list);
          } finally {
            functions.namespaceListFree.invokeExact(list);
          }
        });
  }

  /**
   * Lists the tables of the namespaces whose name paths exactly match {@code namespaces}.
   *
   * @param namespaces the namespaces to list, each as its name path, or {@code null} for all
   * @param timeout limits the request; zero sets no limit
   */
  public List<ListedTable> tables(@Nullable List<List<String>> namespaces, Duration timeout)
      throws ConnectorException {
    return withSession(
        (functions, session, arena) -> {
          // The connector API filters namespaces by their names, the last name of each path, so
          // a namespace of another catalog with the same name is dropped below.
          var filter =
              namespaces == null
                  ? MemorySegment.NULL
                  : nameFilter(
                      arena, namespaces.stream().map(path -> path.get(path.size() - 1)).toList());
          var out = arena.allocate(ADDRESS);
          functions.check(
              (MemorySegment)
                  functions.tableList.invokeExact(
                      session,
                      filter,
                      MemorySegment.NULL,
                      timeout.toMillis(),
                      MemorySegment.NULL,
                      out));
          var list = out.get(ADDRESS, 0);
          try {
            var entries = NativeReader.tableList(list);
            if (namespaces == null) {
              return entries;
            }
            return entries.stream()
                .filter(
                    entry ->
                        !(entry instanceof ListedTable.Table table)
                            || namespaces.contains(
                                table.names().subList(0, table.names().size() - 1)))
                .toList();
          } finally {
            functions.tableListFree.invokeExact(list);
          }
        });
  }

  /**
   * Reads one record per requested table, in request order, including tables that do not exist or
   * could not be read.
   *
   * @param tables the tables, each as its name path
   * @param timeout limits the request; zero sets no limit
   */
  public TableMetadataResult tableMetadata(List<List<String>> tables, Duration timeout)
      throws ConnectorException {
    return withSession(
        (functions, session, arena) -> {
          var array = arena.allocate(NativeReader.NAMES_SIZE * Math.max(1, tables.size()), 8);
          for (int i = 0; i < tables.size(); i++) {
            writeNames(arena, array, i * NativeReader.NAMES_SIZE, tables.get(i));
          }
          var out = arena.allocate(ADDRESS);
          functions.check(
              (MemorySegment)
                  functions.tableMetadata.invokeExact(
                      session,
                      array,
                      (long) tables.size(),
                      timeout.toMillis(),
                      MemorySegment.NULL,
                      out));
          var result = out.get(ADDRESS, 0);
          try {
            return NativeReader.tableMetadataResult(result);
          } finally {
            functions.tableMetadataResultFree.invokeExact(result);
          }
        });
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      try {
        library.functions.dataSourceClose.invokeExact(handle);
      } catch (Throwable e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private interface SessionCall<T> {
    T call(ConnectorLibrary.Functions functions, MemorySegment session, Arena arena)
        throws Throwable;
  }

  private <T> T withSession(SessionCall<T> call) throws ConnectorException {
    if (closed.get()) {
      throw new IllegalStateException("The Data Source is closed");
    }
    var functions = library.functions;
    try (var arena = Arena.ofConfined()) {
      var out = arena.allocate(ADDRESS);
      functions.check((MemorySegment) functions.sessionOpen.invokeExact(handle, out));
      var session = out.get(ADDRESS, 0);
      try {
        return call.call(functions, session, arena);
      } finally {
        functions.sessionClose.invokeExact(session);
      }
    } catch (ConnectorException | RuntimeException e) {
      throw e;
    } catch (Throwable e) {
      throw new IllegalStateException(e);
    }
  }

  private static MemorySegment nameFilter(Arena arena, List<String> names) {
    var pointers = arena.allocate(8L * Math.max(1, names.size()), 8);
    for (int i = 0; i < names.size(); i++) {
      pointers.set(ADDRESS, i * 8L, arena.allocateFrom(names.get(i)));
    }
    var filter = arena.allocate(16, 8);
    filter.set(ADDRESS, 0, pointers);
    filter.set(JAVA_LONG, 8, names.size());
    return filter;
  }

  /** Writes a ScalardbAnalyticsConnectorNames at {@code offset} of {@code target}. */
  private static void writeNames(
      Arena arena, MemorySegment target, long offset, List<String> names) {
    var strings = arena.allocate(NativeReader.STRING_SIZE * Math.max(1, names.size()), 8);
    for (int i = 0; i < names.size(); i++) {
      var bytes = names.get(i).getBytes(StandardCharsets.UTF_8);
      strings.set(ADDRESS, i * NativeReader.STRING_SIZE, arena.allocateFrom(names.get(i)));
      strings.set(JAVA_LONG, i * NativeReader.STRING_SIZE + 8, bytes.length);
    }
    target.set(ADDRESS, offset, strings);
    target.set(JAVA_LONG, offset + 8, names.size());
  }
}
