/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.duckdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.analytics.labs.query.QueryEngineException;
import com.scalar.db.analytics.labs.query.QueryError;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * Exercises the extension's host ABI: registration, calls from DuckDB threads, failure handling,
 * and unregistration with the engine lifecycle.
 */
class CatalogHostTest {
  private static final String HEALTH_CHECK = "grpc.health.v1.Health/Check";
  // grpc.health.v1.HealthCheckResponse{status: SERVING}
  private static final byte[] SERVING = {0x08, 0x01};

  @Test
  void reportsCatalogUnavailableWithoutRegisteredHost() {
    try (var queryEngine = new DuckDbQueryEngine()) {
      assertThatThrownBy(() -> drain(queryEngine, "SELECT scalardb_analytics_catalog_status()"))
          .isInstanceOf(QueryEngineException.class);
    }
  }

  @Test
  void callsTheRegisteredHostFromDuckDbWorkerThreads() throws Exception {
    var callers = new CopyOnWriteArrayList<Thread>();
    var methods = new CopyOnWriteArrayList<String>();
    NativeCatalogProvider provider =
        (method, request) -> {
          callers.add(Thread.currentThread());
          methods.add(method);
          return new NativeCatalogProvider.Result.Response(SERVING);
        };

    try (var queryEngine = new DuckDbQueryEngine(provider);
        var result =
            queryEngine.execute(
                // The range source is single-threaded; scanning the aggregate's partitioned hash
                // table runs on parallel DuckDB worker threads.
                """
                SELECT count(*) AS matching
                FROM (SELECT i % 1000000 AS bucket FROM range(0, 4000000) t(i) GROUP BY bucket)
                WHERE scalardb_analytics_catalog_status() = 'SERVING'
                """)) {
      assertThat(result.reader().loadNextBatch()).isTrue();
      assertThat(result.reader().getVectorSchemaRoot().getVector("matching").getObject(0))
          .isEqualTo(1_000_000L);
    }

    assertThat(methods).isNotEmpty().allMatch(HEALTH_CHECK::equals);
    // Queries start on virtual threads and stream on this thread. Any other platform-thread caller
    // is a thread that DuckDB created.
    var testThread = Thread.currentThread();
    assertThat(callers).anyMatch(thread -> !thread.isVirtual() && thread != testThread);
  }

  @Test
  void reportsProviderFailuresWithoutTheirDiagnostics() {
    var diagnostic = "provider-diagnostic-" + System.nanoTime();
    NativeCatalogProvider provider =
        (method, request) -> new NativeCatalogProvider.Result.Failure(status(14, diagnostic));

    try (var queryEngine = new DuckDbQueryEngine(provider)) {
      assertThatThrownBy(() -> drain(queryEngine, "SELECT scalardb_analytics_catalog_status()"))
          .isInstanceOfSatisfying(
              QueryEngineException.class,
              exception -> assertThat(stackTrace(exception)).doesNotContain(diagnostic));
    }
  }

  @Test
  void containsProviderExceptionsAtTheNativeBoundary() throws Exception {
    NativeCatalogProvider provider =
        (method, request) -> {
          throw new IllegalStateException("provider failure");
        };

    try (var queryEngine = new DuckDbQueryEngine(provider)) {
      assertThatThrownBy(() -> drain(queryEngine, "SELECT scalardb_analytics_catalog_status()"))
          .isInstanceOf(QueryEngineException.class);
      // The engine remains usable after the failed call.
      try (var result = queryEngine.execute("SELECT 1 AS answer")) {
        assertThat(result.reader().loadNextBatch()).isTrue();
      }
    }
  }

  @Test
  void appliesTimeLimitsOnceABlockingCallReturns() {
    NativeCatalogProvider provider =
        (method, request) -> {
          try {
            Thread.sleep(300);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return new NativeCatalogProvider.Result.Response(SERVING);
        };
    var options =
        new DuckDbQueryExecutionOptions(
            OptionalLong.empty(),
            OptionalLong.empty(),
            Optional.of(Duration.ofMillis(100)),
            OptionalLong.empty());

    try (var queryEngine = new DuckDbQueryEngine(provider)) {
      var started = System.nanoTime();
      // Without the time limit this query would make hundreds of 300 ms calls.
      assertThatThrownBy(
              () -> {
                try (var result =
                    queryEngine.execute(
                        """
                        SELECT count(*) AS matching
                        FROM (SELECT i % 1000000 AS bucket FROM range(0, 4000000) t(i) GROUP BY bucket)
                        WHERE scalardb_analytics_catalog_status() = 'SERVING'
                        """,
                        options)) {
                  while (result.reader().loadNextBatch()) {
                    // Consume every batch so the limit can surface.
                  }
                }
              })
          .isInstanceOfSatisfying(
              QueryEngineException.class,
              exception ->
                  assertThat(exception.error())
                      .isInstanceOf(QueryError.ResourceLimitExceeded.class));
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    }
  }

  @Test
  void enginesHostTheirOwnProviders() throws Exception {
    List<String> first = new CopyOnWriteArrayList<>();
    List<String> second = new CopyOnWriteArrayList<>();

    try (var firstEngine =
            new DuckDbQueryEngine(
                (method, request) -> {
                  first.add(method);
                  return new NativeCatalogProvider.Result.Response(SERVING);
                });
        var secondEngine =
            new DuckDbQueryEngine(
                (method, request) -> {
                  second.add(method);
                  return new NativeCatalogProvider.Result.Response(SERVING);
                })) {
      drain(firstEngine, "SELECT scalardb_analytics_catalog_status()");

      assertThat(first).isNotEmpty();
      assertThat(second).isEmpty();
      drain(secondEngine, "SELECT scalardb_analytics_catalog_status()");
      assertThat(second).isNotEmpty();
    }
  }

  private static void drain(DuckDbQueryEngine queryEngine, String sql) throws Exception {
    try (var result = queryEngine.execute(sql)) {
      while (result.reader().loadNextBatch()) {
        // Consume every batch so evaluation failures surface.
      }
    }
  }

  /** Encodes a {@code google.rpc.Status} with a code and message. */
  private static byte[] status(int code, String message) {
    var messageBytes = message.getBytes(StandardCharsets.UTF_8);
    var status = new byte[4 + messageBytes.length];
    status[0] = 0x08;
    status[1] = (byte) code;
    status[2] = 0x12;
    status[3] = (byte) messageBytes.length;
    System.arraycopy(messageBytes, 0, status, 4, messageBytes.length);
    return status;
  }

  private static String stackTrace(Throwable throwable) {
    var writer = new StringWriter();
    throwable.printStackTrace(new PrintWriter(writer));
    return writer.toString();
  }
}
