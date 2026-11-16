/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.connector;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * The connector API library of the carried connectors package, initialized for this process.
 *
 * <p>Opening loads the library of the package staged for the process ({@link
 * ConnectorPackage#forProcess()}) and initializes it with the package's ADBC driver directory. The
 * library allows one initialization per process at a time, so at most one instance is open, and
 * opening fails with {@link ConnectorException.Kind#INVALID_STATE} when something else in the
 * process already initialized the library. Closing shuts the library down.
 *
 * <p>The instance owns the library for the process. The package's DuckDB extension, loaded while an
 * instance is open, uses that initialization and never shuts the library down, so an instance is
 * closed only after every DuckDB database that loaded the extension has closed. An extension loaded
 * while no instance is open initializes the library itself, which then stays initialized until the
 * process exits.
 */
public final class ConnectorLibrary implements AutoCloseable {
  private static final AtomicBoolean OPEN = new AtomicBoolean();

  private final Arena libraryArena;
  final Functions functions;
  private final AtomicBoolean closed = new AtomicBoolean();

  private ConnectorLibrary(Arena libraryArena, MemorySegment api) {
    this.libraryArena = libraryArena;
    functions = new Functions(api);
  }

  /**
   * Loads and initializes the library of the package staged for the process.
   *
   * @throws IllegalStateException if another instance is open in this process
   */
  public static ConnectorLibrary open() throws ConnectorException, IOException {
    if (!OPEN.compareAndSet(false, true)) {
      throw new IllegalStateException("The connector API library is already open in this process");
    }
    Arena arena = null;
    try {
      var staged = ConnectorPackage.forProcess();
      arena = Arena.ofShared();
      var lookup = SymbolLookup.libraryLookup(staged.library(), arena);
      var entry =
          Linker.nativeLinker()
              .downcallHandle(
                  lookup.findOrThrow("scalardb_analytics_connector_api"),
                  FunctionDescriptor.of(ADDRESS));
      var api = ((MemorySegment) entry.invokeExact()).reinterpret(Functions.TABLE_SIZE);
      var library = new ConnectorLibrary(arena, api);
      library.initialize(staged.adbcDrivers());
      return library;
    } catch (ConnectorException | IOException | RuntimeException e) {
      release(arena);
      throw e;
    } catch (Throwable e) {
      release(arena);
      throw new IllegalStateException("Could not load the connector API library", e);
    }
  }

  private void initialize(Path drivers) throws ConnectorException {
    try (var arena = Arena.ofConfined()) {
      var settings = arena.allocate(ADDRESS);
      settings.set(ADDRESS, 0, arena.allocateFrom(drivers.toAbsolutePath().toString()));
      functions.check((MemorySegment) functions.libraryInitialize.invokeExact(settings));
    } catch (ConnectorException e) {
      throw e;
    } catch (Throwable e) {
      throw new IllegalStateException(e);
    }
  }

  /** The static information JSON document: the built-in Connectors and their options. */
  public String staticInformation() {
    // The function returns a ScalardbAnalyticsConnectorString by value, which the linker writes
    // into memory from the leading allocator.
    try (var arena = Arena.ofConfined()) {
      var text = (MemorySegment) functions.staticInformation.invokeExact((SegmentAllocator) arena);
      return NativeReader.string(text, 0);
    } catch (Throwable e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Opens a Data Source of the named Connector.
   *
   * @param options the connection options, each as the text the Connector parses; values may be
   *     credentials and never appear in messages
   * @param connectionTimeout limits connecting while the Data Source and its sessions open; zero
   *     sets no limit
   */
  public DataSource openDataSource(
      String connector, Map<String, String> options, Duration connectionTimeout)
      throws ConnectorException {
    try (var arena = Arena.ofConfined()) {
      var array = arena.allocate(16L * Math.max(1, options.size()), 8);
      long offset = 0;
      for (var option : options.entrySet()) {
        array.set(ADDRESS, offset, arena.allocateFrom(option.getKey()));
        array.set(ADDRESS, offset + 8, arena.allocateFrom(option.getValue()));
        offset += 16;
      }
      var out = arena.allocate(ADDRESS);
      functions.check(
          (MemorySegment)
              functions.dataSourceOpen.invokeExact(
                  arena.allocateFrom(connector),
                  array,
                  (long) options.size(),
                  connectionTimeout.toMillis(),
                  out));
      return new DataSource(this, out.get(ADDRESS, 0));
    } catch (ConnectorException e) {
      throw e;
    } catch (Throwable e) {
      throw new IllegalStateException(e);
    }
  }

  /** Shuts the library down and releases this binding's handle on it. */
  @Override
  public void close() throws ConnectorException {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    try {
      functions.check((MemorySegment) functions.libraryShutdown.invokeExact());
    } catch (ConnectorException e) {
      // A library that cannot shut down still has live handles; unloading it would leave them
      // pointing at unmapped code, so it stays loaded.
      OPEN.set(false);
      throw e;
    } catch (Throwable e) {
      throw new IllegalStateException(e);
    }
    release(libraryArena);
  }

  private static void release(@Nullable Arena arena) {
    try {
      if (arena != null) {
        arena.close();
      }
    } finally {
      OPEN.set(false);
    }
  }

  /** Downcall handles of the function table entries this binding calls. */
  static final class Functions {
    /** The table holds 37 function pointers. */
    static final long TABLE_SIZE = 37L * 8;

    private static final Linker LINKER = Linker.nativeLinker();

    final MethodHandle staticInformation;
    final MethodHandle libraryInitialize;
    final MethodHandle libraryShutdown;
    final MethodHandle dataSourceOpen;
    final MethodHandle dataSourceClose;
    final MethodHandle sessionOpen;
    final MethodHandle sessionClose;
    final MethodHandle namespaceList;
    final MethodHandle namespaceListFree;
    final MethodHandle tableList;
    final MethodHandle tableListFree;
    final MethodHandle tableMetadata;
    final MethodHandle tableMetadataResultFree;
    final MethodHandle errorFree;

    Functions(MemorySegment api) {
      staticInformation =
          handle(api, 1, FunctionDescriptor.of(MemoryLayout.structLayout(ADDRESS, JAVA_LONG)));
      libraryInitialize = handle(api, 2, FunctionDescriptor.of(ADDRESS, ADDRESS));
      libraryShutdown = handle(api, 3, FunctionDescriptor.of(ADDRESS));
      dataSourceOpen =
          handle(
              api,
              4,
              FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS));
      dataSourceClose = handle(api, 5, FunctionDescriptor.ofVoid(ADDRESS));
      sessionOpen = handle(api, 6, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
      sessionClose = handle(api, 7, FunctionDescriptor.ofVoid(ADDRESS));
      namespaceList =
          handle(
              api,
              22,
              FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
      namespaceListFree = handle(api, 23, FunctionDescriptor.ofVoid(ADDRESS));
      tableList =
          handle(
              api,
              24,
              FunctionDescriptor.of(
                  ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
      tableListFree = handle(api, 25, FunctionDescriptor.ofVoid(ADDRESS));
      tableMetadata =
          handle(
              api,
              26,
              FunctionDescriptor.of(
                  ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS));
      tableMetadataResultFree = handle(api, 27, FunctionDescriptor.ofVoid(ADDRESS));
      errorFree = handle(api, 35, FunctionDescriptor.ofVoid(ADDRESS));
    }

    /** The handle of the table entry at the 1-based position the header declares it. */
    private static MethodHandle handle(MemorySegment api, int position, FunctionDescriptor type) {
      return LINKER.downcallHandle(api.get(ADDRESS, (position - 1) * 8L), type);
    }

    /** Throws a returned error after freeing it; does nothing for a null error. */
    void check(MemorySegment error) throws ConnectorException {
      if (error.equals(MemorySegment.NULL)) {
        return;
      }
      var exception = NativeReader.error(error);
      try {
        errorFree.invokeExact(error);
      } catch (Throwable e) {
        throw new IllegalStateException(e);
      }
      throw exception;
    }
  }
}
