/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.duckdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.analytics.labs.query.QueryEngineException;
import com.scalar.db.analytics.labs.query.QueryError;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.OutOfMemoryException;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.types.pojo.Schema;
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
  void acceptsCommentsCommonTableExpressionsAndTrailingSemicolon() throws Exception {
    try (var queryEngine = new DuckDbQueryEngine();
        var result =
            queryEngine.execute(
                """
                -- A generated query can contain comments.
                WITH values_source(value) AS (VALUES (1), (2))
                SELECT sum(value) AS total FROM values_source;
                """)) {
      assertThat(result.reader().loadNextBatch()).isTrue();
      assertThat(result.reader().getVectorSchemaRoot().getVector("total").getObject(0))
          .isEqualTo(new BigDecimal("3"));
    }
  }

  @Test
  void rejectsDataDefinitionBeforeItCanModifyState() throws Exception {
    try (var queryEngine = new DuckDbQueryEngine()) {
      assertThatThrownBy(() -> queryEngine.execute("CREATE TABLE forbidden_table(id INTEGER)"))
          .isInstanceOfSatisfying(
              QueryEngineException.class,
              exception -> {
                assertThat(exception.error()).isInstanceOf(QueryError.InvalidQuery.class);
                assertThat(exception.getMessage())
                    .isEqualTo("Only a single read-only SELECT query is allowed.");
              });

      try (var result =
          queryEngine.execute(
              "SELECT count(*) AS count FROM duckdb_tables() "
                  + "WHERE table_name = 'forbidden_table'")) {
        assertThat(result.reader().loadNextBatch()).isTrue();
        assertThat(result.reader().getVectorSchemaRoot().getVector("count").getObject(0))
            .isEqualTo(0L);
      }
    }
  }

  @Test
  void rejectsMultipleStatementsBeforeAnyCanRun() throws Exception {
    try (var queryEngine = new DuckDbQueryEngine()) {
      assertThatThrownBy(
              () ->
                  queryEngine.execute(
                      "SELECT 1; CREATE TABLE forbidden_multi_statement(id INTEGER)"))
          .isInstanceOf(QueryEngineException.class)
          .hasMessage("Only a single read-only SELECT query is allowed.");

      try (var result =
          queryEngine.execute(
              "SELECT count(*) AS count FROM duckdb_tables() "
                  + "WHERE table_name = 'forbidden_multi_statement'")) {
        assertThat(result.reader().loadNextBatch()).isTrue();
        assertThat(result.reader().getVectorSchemaRoot().getVector("count").getObject(0))
            .isEqualTo(0L);
      }
    }
  }

  @Test
  void rejectsDataModificationStatements() throws Exception {
    try (var queryEngine = new DuckDbQueryEngine()) {
      for (var sql :
          List.of(
              "INSERT INTO missing_table VALUES (1)",
              "UPDATE missing_table SET value = 1",
              "DELETE FROM missing_table")) {
        assertThatThrownBy(() -> queryEngine.execute(sql))
            .isInstanceOfSatisfying(
                QueryEngineException.class,
                exception ->
                    assertThat(exception.error()).isInstanceOf(QueryError.InvalidQuery.class));
      }
    }
  }

  @Test
  void rejectsMalformedSqlAsInvalidQuery() throws Exception {
    try (var queryEngine = new DuckDbQueryEngine()) {
      assertThatThrownBy(() -> queryEngine.execute("SELECT FROM"))
          .isInstanceOfSatisfying(
              QueryEngineException.class,
              exception ->
                  assertThat(exception.error()).isInstanceOf(QueryError.InvalidQuery.class));
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
  void rejectsEveryPackagedExtensionFunctionThatCouldBypassAttachedSources() throws Exception {
    // Functions reviewed as unable to reach a source or run SQL supplied as a value. An extension
    // change that adds any other function fails here until it is reviewed.
    var reviewed =
        Set.of(
            "scalardb_analytics_version",
            "scalardb_analytics_is_read_only_query",
            "scalardb_analytics_catalog_status");
    var coreFunctions = new HashSet<String>();
    try (var connection = DriverManager.getConnection("jdbc:duckdb:");
        var statement = connection.createStatement();
        var resultSet =
            statement.executeQuery("SELECT DISTINCT function_name FROM duckdb_functions()")) {
      while (resultSet.next()) {
        coreFunctions.add(resultSet.getString(1));
      }
    }

    try (var queryEngine = new DuckDbQueryEngine()) {
      var extensionFunctions = new ArrayList<String>();
      try (var result =
          queryEngine.execute("SELECT DISTINCT function_name FROM duckdb_functions()")) {
        while (result.reader().loadNextBatch()) {
          var names = result.reader().getVectorSchemaRoot().getVector(0);
          for (int row = 0; row < names.getValueCount(); row++) {
            var name = names.getObject(row).toString();
            if (!coreFunctions.contains(name)) {
              extensionFunctions.add(name);
            }
          }
        }
      }

      assertThat(extensionFunctions).contains("scalardb_analytics_is_read_only_query");
      for (var name : extensionFunctions) {
        if (reviewed.contains(name)) {
          continue;
        }
        assertThatThrownBy(() -> queryEngine.execute("SELECT \"" + name + "\"()"))
            .as(name)
            .hasMessage("Sources must be queried through their attached databases.");
      }
      for (var name : List.of("query", "query_table", "json_execute_serialized_sql")) {
        assertThatThrownBy(() -> queryEngine.execute("SELECT * FROM " + name + "('SELECT 1')"))
            .as(name)
            .hasMessage("Sources must be queried through their attached databases.");
      }
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
  void enforcesRowLimitWhileStreamingAndReleasesResources() throws Exception {
    try (var queryEngine = new DuckDbQueryEngine();
        var execution =
            queryEngine.start(
                "SELECT * FROM range(2050)",
                options(
                    OptionalLong.of(1_500),
                    OptionalLong.empty(),
                    Optional.empty(),
                    OptionalLong.empty()));
        var result = execution.awaitResult()) {
      var reader = result.reader();
      assertThat(reader.loadNextBatch()).isTrue();

      assertThatThrownBy(reader::loadNextBatch)
          .isInstanceOfSatisfying(
              QueryEngineException.class,
              exception -> {
                assertThat(exception.executionId()).isEqualTo(execution.executionId());
                assertThat(exception.error()).isInstanceOf(QueryError.ResourceLimitExceeded.class);
                assertThat(exception.getMessage()).isEqualTo("Query row limit exceeded.");
              });

      assertEngineRemainsUsable(queryEngine);
    }
  }

  @Test
  void enforcesByteLimitWhileStreamingAndReleasesResources() throws Exception {
    try (var queryEngine = new DuckDbQueryEngine();
        var execution =
            queryEngine.start(
                "SELECT repeat('x', 2048) AS value",
                options(
                    OptionalLong.empty(),
                    OptionalLong.of(128),
                    Optional.empty(),
                    OptionalLong.empty()));
        var result = execution.awaitResult()) {
      assertThatThrownBy(result.reader()::loadNextBatch)
          .isInstanceOfSatisfying(
              QueryEngineException.class,
              exception -> {
                assertThat(exception.executionId()).isEqualTo(execution.executionId());
                assertThat(exception.error()).isInstanceOf(QueryError.ResourceLimitExceeded.class);
                assertThat(exception.getMessage()).isEqualTo("Query byte limit exceeded.");
              });

      assertEngineRemainsUsable(queryEngine);
    }
  }

  @Test
  void enforcesTimeLimitAndReleasesResources() throws Exception {
    try (var queryEngine = new DuckDbQueryEngine();
        var execution =
            queryEngine.start(
                "SELECT sum(sin(i)) FROM range(1000000000) values_source(i)",
                options(
                    OptionalLong.empty(),
                    OptionalLong.empty(),
                    Optional.of(Duration.ofMillis(20)),
                    OptionalLong.empty()))) {
      assertThatThrownBy(execution::awaitResult)
          .isInstanceOfSatisfying(
              QueryEngineException.class,
              exception -> {
                assertThat(exception.executionId()).isEqualTo(execution.executionId());
                assertThat(exception.error()).isInstanceOf(QueryError.ResourceLimitExceeded.class);
                assertThat(exception.getMessage()).isEqualTo("Query time limit exceeded.");
              });

      assertEngineRemainsUsable(queryEngine);
    }
  }

  @Test
  void enforcesMemoryLimitAndReleasesResources() throws Exception {
    try (var queryEngine = new DuckDbQueryEngine();
        var execution =
            queryEngine.start(
                "SELECT list(i) FROM range(10000000) values_source(i)",
                options(
                    OptionalLong.empty(),
                    OptionalLong.empty(),
                    Optional.empty(),
                    OptionalLong.of(1_048_576)))) {
      assertThatThrownBy(execution::awaitResult)
          .isInstanceOfSatisfying(
              QueryEngineException.class,
              exception -> {
                assertThat(exception.executionId()).isEqualTo(execution.executionId());
                assertThat(exception.error()).isInstanceOf(QueryError.ResourceLimitExceeded.class);
                assertThat(exception.getMessage()).isEqualTo("Query memory limit exceeded.");
              });

      assertEngineRemainsUsable(queryEngine);
    }
  }

  @Test
  void aQueryWithoutALimitDoesNotInheritAnEarlierQuerysMemoryLimit() throws Exception {
    try (var queryEngine = new DuckDbQueryEngine()) {
      try (var limited =
          queryEngine.start(
              "SELECT list(i) FROM range(10000000) values_source(i)",
              options(
                  OptionalLong.empty(),
                  OptionalLong.empty(),
                  Optional.empty(),
                  OptionalLong.of(1_048_576)))) {
        assertThatThrownBy(limited::awaitResult).isInstanceOf(QueryEngineException.class);
      }

      // Far more than the earlier query's limit.
      try (var result =
          queryEngine.execute("SELECT length(list(i)) AS n FROM range(1000000) values_source(i)")) {
        assertThat(result.reader().loadNextBatch()).isTrue();
        assertThat(result.reader().getVectorSchemaRoot().getVector("n").getObject(0))
            .isEqualTo(1_000_000L);
      }
    }
  }

  @Test
  void cancellationIsIdempotentAndInterruptsStreamingWork() throws Exception {
    try (var queryEngine = new DuckDbQueryEngine();
        var execution =
            queryEngine.start(
                "SELECT i FROM range(1000000000) values_source(i)",
                DuckDbQueryExecutionOptions.unlimited());
        var result = execution.awaitResult();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var reader = result.reader();
      var failure =
          executor.submit(
              () -> {
                try {
                  while (reader.loadNextBatch()) {}
                  return (Throwable) null;
                } catch (Throwable t) {
                  return t;
                }
              });

      execution.cancel();
      execution.cancel();

      assertThat(failure.get(5, TimeUnit.SECONDS))
          .isInstanceOfSatisfying(
              QueryEngineException.class,
              exception -> {
                assertThat(exception.executionId()).isEqualTo(execution.executionId());
                assertThat(exception.error()).isInstanceOf(QueryError.Canceled.class);
                assertThat(exception.getMessage()).isEqualTo("Query execution was canceled.");
              });
      assertEngineRemainsUsable(queryEngine);
    }
  }

  @Test
  void retriesCancellationThatArrivesImmediatelyBeforeDuckDbExecutionStarts() throws Exception {
    var queryExecutor = new DeferredExecutorService();
    var controlExecutor = Executors.newSingleThreadScheduledExecutor();
    var secondCancellation = new CountDownLatch(2);
    var statement = cancellationCountingStatement(secondCancellation);
    var options = DuckDbQueryExecutionOptions.unlimited();
    try (var queryEngine = new DuckDbQueryEngine()) {
      var execution = new DuckDbQueryExecution(queryEngine, "SELECT 1", options);
      execution.start(queryExecutor, controlExecutor);
      execution.track(statement);

      execution.cancel();

      assertThat(secondCancellation.await(1, TimeUnit.SECONDS)).isTrue();
      execution.finish();
    } finally {
      queryExecutor.shutdownNow();
      controlExecutor.shutdownNow();
    }
  }

  @Test
  void cancellationAfterResultCreationStillProducesTypedError() throws Exception {
    try (var queryEngine = new DuckDbQueryEngine();
        var execution =
            queryEngine.start("SELECT 1 AS value", DuckDbQueryExecutionOptions.unlimited());
        var result = execution.awaitResult()) {
      execution.cancel();

      assertThatThrownBy(result::reader)
          .isInstanceOfSatisfying(
              QueryEngineException.class,
              exception -> {
                assertThat(exception.executionId()).isEqualTo(execution.executionId());
                assertThat(exception.error()).isInstanceOf(QueryError.Canceled.class);
              });
      assertEngineRemainsUsable(queryEngine);
    }
  }

  @Test
  void timeLimitAfterResultCreationStillProducesTypedError() throws Exception {
    try (var queryEngine = new DuckDbQueryEngine();
        var execution =
            queryEngine.start(
                "SELECT 1 AS value",
                options(
                    OptionalLong.empty(),
                    OptionalLong.empty(),
                    Optional.of(Duration.ofMillis(20)),
                    OptionalLong.empty()));
        var result = execution.awaitResult()) {
      Thread.sleep(100);

      assertThatThrownBy(result::reader)
          .isInstanceOfSatisfying(
              QueryEngineException.class,
              exception -> {
                assertThat(exception.executionId()).isEqualTo(execution.executionId());
                assertThat(exception.error()).isInstanceOf(QueryError.ResourceLimitExceeded.class);
                assertThat(exception.getMessage()).isEqualTo("Query time limit exceeded.");
              });
      assertEngineRemainsUsable(queryEngine);
    }
  }

  @Test
  void arrowMemoryFailureProducesTypedErrorAndReleasesResources() throws Exception {
    var options =
        options(
            OptionalLong.empty(),
            OptionalLong.empty(),
            Optional.empty(),
            OptionalLong.of(1_048_576));
    try (var queryEngine = new DuckDbQueryEngine();
        var rootAllocator = new RootAllocator()) {
      BufferAllocator queryAllocator =
          rootAllocator.newChildAllocator("memory-limit-test", 0, Long.MAX_VALUE);
      var execution = new DuckDbQueryExecution(queryEngine, "SELECT 1", options);
      var result =
          new DuckDbQueryResult(
              execution,
              options,
              memoryFailingReader(queryAllocator),
              queryAllocator,
              noOpProxy(ResultSet.class),
              noOpProxy(Statement.class),
              false);
      execution.complete(result);

      assertThatThrownBy(() -> execution.awaitResult().reader().loadNextBatch())
          .isInstanceOfSatisfying(
              QueryEngineException.class,
              exception -> {
                assertThat(exception.executionId()).isEqualTo(execution.executionId());
                assertThat(exception.error()).isInstanceOf(QueryError.ResourceLimitExceeded.class);
                assertThat(exception.getMessage()).isEqualTo("Query memory limit exceeded.");
              });
      assertEngineRemainsUsable(queryEngine);
    }
  }

  @Test
  void streamingFailureDoesNotExposeSensitiveDiagnostics() throws Exception {
    var sensitiveMarker = "credential=stream-secret";
    var options = DuckDbQueryExecutionOptions.unlimited();
    try (var queryEngine = new DuckDbQueryEngine();
        var rootAllocator = new RootAllocator()) {
      BufferAllocator queryAllocator =
          rootAllocator.newChildAllocator("stream-failure-test", 0, Long.MAX_VALUE);
      var execution = new DuckDbQueryExecution(queryEngine, "SELECT 1", options);
      var result =
          new DuckDbQueryResult(
              execution,
              options,
              failingReader(queryAllocator, new IllegalStateException(sensitiveMarker)),
              queryAllocator,
              noOpProxy(ResultSet.class),
              noOpProxy(Statement.class),
              false);
      execution.complete(result);

      assertThatThrownBy(() -> execution.awaitResult().reader().loadNextBatch())
          .isInstanceOfSatisfying(
              QueryEngineException.class,
              exception -> {
                assertThat(exception.error()).isInstanceOf(QueryError.ExecutionFailed.class);
                assertThat(exception.getCause()).isNull();
                assertThat(stackTrace(exception)).doesNotContain(sensitiveMarker);
              });
      assertEngineRemainsUsable(queryEngine);
    }
  }

  @Test
  void cleanupFailureDoesNotExposeSensitiveDiagnostics() throws Exception {
    var sensitiveMarker = "connection=cleanup-secret";
    var options = DuckDbQueryExecutionOptions.unlimited();
    try (var queryEngine = new DuckDbQueryEngine();
        var rootAllocator = new RootAllocator()) {
      BufferAllocator queryAllocator =
          rootAllocator.newChildAllocator("cleanup-failure-test", 0, Long.MAX_VALUE);
      var execution = new DuckDbQueryExecution(queryEngine, "SELECT 1", options);
      var result =
          new DuckDbQueryResult(
              execution,
              options,
              emptyReader(queryAllocator),
              queryAllocator,
              noOpProxy(ResultSet.class),
              cleanupFailingStatement(sensitiveMarker),
              false);
      execution.complete(result);

      execution.cancel();

      assertThatThrownBy(result::reader)
          .isInstanceOfSatisfying(
              QueryEngineException.class,
              exception -> {
                assertThat(exception.error()).isInstanceOf(QueryError.Canceled.class);
                assertThat(exception.getSuppressed())
                    .singleElement()
                    .satisfies(
                        suppressed ->
                            assertThat(suppressed)
                                .hasMessage("Additional resource cleanup failed."));
                assertThat(stackTrace(exception)).doesNotContain(sensitiveMarker);
              });
      assertEngineRemainsUsable(queryEngine);
    }
  }

  @Test
  void executionFailureDoesNotExposeSensitiveSql() {
    var sensitiveSql = "SELECT credential_value FROM connection_string_secret";

    try (var queryEngine = new DuckDbQueryEngine()) {
      assertThatThrownBy(() -> queryEngine.execute(sensitiveSql))
          .isInstanceOfSatisfying(
              QueryEngineException.class,
              exception -> {
                assertThat(exception.getMessage()).isEqualTo("Query execution failed.");
                assertThat(exception.getCause()).isNull();
                assertThat(exception.toString())
                    .doesNotContain("credential_value", "connection_string_secret", sensitiveSql);
              });
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

  private static DuckDbQueryExecutionOptions options(
      OptionalLong rowLimit,
      OptionalLong byteLimit,
      Optional<Duration> timeLimit,
      OptionalLong memoryLimitBytes) {
    return new DuckDbQueryExecutionOptions(rowLimit, byteLimit, timeLimit, memoryLimitBytes);
  }

  private static void assertEngineRemainsUsable(DuckDbQueryEngine queryEngine) throws Exception {
    try (var result = queryEngine.execute("SELECT 1 AS value")) {
      assertThat(result.reader().loadNextBatch()).isTrue();
      assertThat(result.reader().getVectorSchemaRoot().getVector("value").getObject(0))
          .isEqualTo(1);
    }
  }

  private static ArrowReader memoryFailingReader(BufferAllocator allocator) {
    return failingReader(allocator, new OutOfMemoryException("allocator capacity exceeded"));
  }

  private static ArrowReader failingReader(BufferAllocator allocator, RuntimeException failure) {
    return new ArrowReader(allocator) {
      @Override
      public boolean loadNextBatch() {
        throw failure;
      }

      @Override
      public long bytesRead() {
        return 0;
      }

      @Override
      protected void closeReadSource() {}

      @Override
      protected Schema readSchema() throws IOException {
        return new Schema(List.of());
      }
    };
  }

  private static ArrowReader emptyReader(BufferAllocator allocator) {
    return new ArrowReader(allocator) {
      @Override
      public boolean loadNextBatch() {
        return false;
      }

      @Override
      public long bytesRead() {
        return 0;
      }

      @Override
      protected void closeReadSource() {}

      @Override
      protected Schema readSchema() throws IOException {
        return new Schema(List.of());
      }
    };
  }

  private static Statement cleanupFailingStatement(String sensitiveMarker) {
    return (Statement)
        Proxy.newProxyInstance(
            Statement.class.getClassLoader(),
            new Class<?>[] {Statement.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("cancel") || method.getName().equals("close")) {
                throw new SQLException(sensitiveMarker);
              }
              return null;
            });
  }

  private static Statement cancellationCountingStatement(CountDownLatch cancellations) {
    return (Statement)
        Proxy.newProxyInstance(
            Statement.class.getClassLoader(),
            new Class<?>[] {Statement.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("cancel")) {
                cancellations.countDown();
              }
              return null;
            });
  }

  private static final class DeferredExecutorService extends AbstractExecutorService {
    private boolean shutdown;

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return shutdown;
    }

    @Override
    public void execute(Runnable command) {}
  }

  private static String stackTrace(Throwable throwable) {
    var output = new StringWriter();
    throwable.printStackTrace(new PrintWriter(output));
    return output.toString();
  }

  private static <T> T noOpProxy(Class<T> type) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, arguments) -> {
              var returnType = method.getReturnType();
              if (!returnType.isPrimitive()) {
                return null;
              }
              if (returnType == boolean.class) {
                return false;
              }
              if (returnType == char.class) {
                return '\0';
              }
              return 0;
            }));
  }
}
