/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.duckdb;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Registers a {@link NativeCatalogProvider} into the loaded product extension through its host ABI
 * ({@code scalardb_analytics_host.h}).
 *
 * <p>Each catalog scope owns a shared arena. Responses are copied into the arena of the scope that
 * requested them and stay valid until the extension ends that scope. Exceptions never cross the
 * native boundary: every failure becomes a serialized status.
 */
final class CatalogHostRegistration implements AutoCloseable {
  private static final int HOST_ABI_VERSION = 1;
  private static final int HOST_OK = 0;
  private static final int HOST_BUSY = 6;
  private static final int STATUS_FAILED_PRECONDITION = 9;
  private static final int STATUS_INTERNAL = 13;

  private static final StructLayout CATALOG_PROVIDER_LAYOUT =
      MemoryLayout.structLayout(
          ValueLayout.ADDRESS.withName("ctx"),
          ValueLayout.ADDRESS.withName("begin_scope"),
          ValueLayout.ADDRESS.withName("end_scope"),
          ValueLayout.ADDRESS.withName("call"));
  private static final StructLayout HOST_LAYOUT =
      MemoryLayout.structLayout(
          ValueLayout.JAVA_INT.withName("abi_version"),
          ValueLayout.JAVA_INT.withName("struct_size"),
          ValueLayout.ADDRESS.withName("catalog"));

  private final NativeCatalogProvider provider;
  private final Arena libraryArena;
  private final Arena stubArena;
  private final MethodHandle unregister;
  private final Map<Long, Arena> scopes = new ConcurrentHashMap<>();
  private final AtomicLong nextScope = new AtomicLong();

  private CatalogHostRegistration(
      NativeCatalogProvider provider, Arena libraryArena, MethodHandle unregister) {
    this.provider = provider;
    this.libraryArena = libraryArena;
    this.unregister = unregister;
    stubArena = Arena.ofShared();
  }

  /**
   * Registers the provider into the extension library at the given path.
   *
   * <p>The library must already be loaded by DuckDB from the same path, so the lookup resolves to
   * the loaded extension rather than a second copy.
   */
  static CatalogHostRegistration register(Path extensionFile, NativeCatalogProvider provider) {
    var linker = Linker.nativeLinker();
    var libraryArena = Arena.ofShared();
    CatalogHostRegistration registration = null;
    try {
      var library = SymbolLookup.libraryLookup(extensionFile, libraryArena);
      var registerHost =
          linker.downcallHandle(
              library.findOrThrow("scalardb_analytics_register_host"),
              FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
      var unregisterHost =
          linker.downcallHandle(
              library.findOrThrow("scalardb_analytics_unregister_host"),
              FunctionDescriptor.of(ValueLayout.JAVA_INT));
      registration = new CatalogHostRegistration(provider, libraryArena, unregisterHost);
      registration.registerInto(linker, registerHost);
      return registration;
    } catch (Throwable e) {
      if (registration != null) {
        registration.stubArena.close();
      }
      libraryArena.close();
      throw new IllegalStateException("Could not register the catalog host.", e);
    }
  }

  private void registerInto(Linker linker, MethodHandle registerHost) throws Throwable {
    var lookup = MethodHandles.lookup();
    var beginScope =
        linker.upcallStub(
            lookup
                .findVirtual(
                    CatalogHostRegistration.class,
                    "beginScope",
                    MethodType.methodType(long.class, MemorySegment.class))
                .bindTo(this),
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
            stubArena);
    var endScope =
        linker.upcallStub(
            lookup
                .findVirtual(
                    CatalogHostRegistration.class,
                    "endScope",
                    MethodType.methodType(void.class, MemorySegment.class, long.class))
                .bindTo(this),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
            stubArena);
    var call =
        linker.upcallStub(
            lookup
                .findVirtual(
                    CatalogHostRegistration.class,
                    "call",
                    MethodType.methodType(
                        long.class,
                        MemorySegment.class,
                        long.class,
                        MemorySegment.class,
                        MemorySegment.class,
                        long.class,
                        MemorySegment.class))
                .bindTo(this),
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS),
            stubArena);

    // The extension copies both structures during registration, so a confined arena suffices.
    try (var arena = Arena.ofConfined()) {
      var catalog = arena.allocate(CATALOG_PROVIDER_LAYOUT);
      catalog.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
      catalog.set(ValueLayout.ADDRESS, ValueLayout.ADDRESS.byteSize(), beginScope);
      catalog.set(ValueLayout.ADDRESS, 2 * ValueLayout.ADDRESS.byteSize(), endScope);
      catalog.set(ValueLayout.ADDRESS, 3 * ValueLayout.ADDRESS.byteSize(), call);

      var host = arena.allocate(HOST_LAYOUT);
      host.set(ValueLayout.JAVA_INT, 0, HOST_ABI_VERSION);
      host.set(ValueLayout.JAVA_INT, 4, (int) HOST_LAYOUT.byteSize());
      host.set(ValueLayout.ADDRESS, 8, catalog);

      var result = (int) registerHost.invokeExact(host);
      if (result != HOST_OK) {
        throw new IllegalStateException("The extension rejected the catalog host: " + result);
      }
    }
  }

  /**
   * Unregisters the provider and releases the upcall stubs.
   *
   * @throws IllegalStateException if the extension still has open catalog scopes
   */
  @Override
  public void close() {
    int result;
    try {
      result = (int) unregister.invokeExact();
    } catch (Throwable e) {
      throw new IllegalStateException("Could not unregister the catalog host.", e);
    }
    if (result == HOST_BUSY) {
      throw new IllegalStateException("The extension still has open catalog scopes.");
    }
    // After a successful unregistration the extension no longer calls the stubs.
    for (var arena : scopes.values()) {
      arena.close();
    }
    scopes.clear();
    stubArena.close();
    libraryArena.close();
  }

  @SuppressWarnings("unused") // Invoked from native code.
  private long beginScope(MemorySegment context) {
    try {
      var scope = nextScope.incrementAndGet();
      scopes.put(scope, Arena.ofShared());
      return scope;
    } catch (Throwable e) {
      return 0;
    }
  }

  @SuppressWarnings("unused") // Invoked from native code.
  private void endScope(MemorySegment context, long scope) {
    try {
      var arena = scopes.remove(scope);
      if (arena != null) {
        arena.close();
      }
    } catch (Throwable ignored) {
      // Nothing can be reported across the native boundary.
    }
  }

  @SuppressWarnings("unused") // Invoked from native code.
  private long call(
      MemorySegment context,
      long scope,
      MemorySegment method,
      MemorySegment request,
      long requestSize,
      MemorySegment outData) {
    try {
      var arena = scopes.get(scope);
      if (arena == null) {
        return publish(
            outData,
            Arena.global(),
            status(STATUS_FAILED_PRECONDITION, "FAILED_PRECONDITION"),
            true);
      }
      byte[] response;
      boolean failed;
      try {
        var methodName = method.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
        var requestBytes =
            requestSize == 0
                ? new byte[0]
                : request.reinterpret(requestSize).toArray(ValueLayout.JAVA_BYTE);
        var result = provider.call(methodName, requestBytes);
        switch (result) {
          case NativeCatalogProvider.Result.Response success -> {
            response = success.message();
            failed = false;
          }
          case NativeCatalogProvider.Result.Failure failure -> {
            // An empty status would be indistinguishable from an empty successful response.
            response =
                failure.status().length == 0
                    ? status(STATUS_INTERNAL, "INTERNAL")
                    : failure.status();
            failed = true;
          }
        }
      } catch (RuntimeException e) {
        response = status(STATUS_INTERNAL, "INTERNAL");
        failed = true;
      }
      return publish(outData, arena, response, failed);
    } catch (Throwable e) {
      // The extension treats this value as an invalid provider response.
      return Long.MIN_VALUE;
    }
  }

  private static long publish(MemorySegment outData, Arena arena, byte[] bytes, boolean failed) {
    var data = arena.allocate(Math.max(1, bytes.length));
    MemorySegment.copy(bytes, 0, data, ValueLayout.JAVA_BYTE, 0, bytes.length);
    outData.reinterpret(ValueLayout.ADDRESS.byteSize()).set(ValueLayout.ADDRESS, 0, data);
    return failed ? -(long) bytes.length : bytes.length;
  }

  /** Encodes a {@code google.rpc.Status} with a code and message. */
  private static byte[] status(int code, String message) {
    var output = new ByteArrayOutputStream();
    output.write(0x08);
    writeVarint(output, code);
    var messageBytes = message.getBytes(StandardCharsets.UTF_8);
    output.write(0x12);
    writeVarint(output, messageBytes.length);
    output.writeBytes(messageBytes);
    return output.toByteArray();
  }

  private static void writeVarint(ByteArrayOutputStream output, int value) {
    var remaining = value;
    while ((remaining & ~0x7F) != 0) {
      output.write((remaining & 0x7F) | 0x80);
      remaining >>>= 7;
    }
    output.write(remaining);
  }
}
