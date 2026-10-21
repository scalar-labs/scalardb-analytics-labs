/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.cli.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalar.db.analytics.labs.application.workspace.Workspace;
import com.scalar.db.analytics.labs.query.QueryEngineException;
import com.scalar.db.analytics.labs.query.QueryError;
import com.scalar.db.analytics.labs.query.QueryResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class QueryCommandTest {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Test
  void cleanupFailureProducesOnlyAnErrorEnvelope(@TempDir Path workspace) throws Exception {
    writeWorkspaceConfiguration(workspace);
    var output = new ByteArrayOutputStream();
    var command =
        new QueryCommand(
            new ByteArrayInputStream(new byte[0]),
            Map.of("STORE_PASSWORD", "secret"),
            ignored -> new QueryApplicationService(CleanupFailingRunner::new));
    var commandLine = new CommandLine(command);
    commandLine.setOut(new PrintWriter(output, true, StandardCharsets.UTF_8));

    var exitCode = commandLine.execute("--workspace", workspace.toString(), "--sql", "SELECT 1");

    assertThat(exitCode).isEqualTo(1);
    assertThat(output.toString(StandardCharsets.UTF_8).lines()).hasSize(1);
    var error = OBJECT_MAPPER.readTree(output.toString(StandardCharsets.UTF_8).strip());
    assertThat(error.get("schema").textValue()).isEqualTo("scalardb-analytics/error");
    assertThat(error.get("code").textValue()).isEqualTo("SHUTDOWN_FAILED");
  }

  @Test
  void waitingForStandardInputIsNotReportedAsAnActiveQuery(@TempDir Path workspace)
      throws Exception {
    writeWorkspaceConfiguration(workspace);
    var input = new BlockingInputStream();
    var command =
        new QueryCommand(
            input,
            Map.of("STORE_PASSWORD", "secret"),
            ignored -> new QueryApplicationService(CleanupFailingRunner::new));
    var commandLine = new CommandLine(command);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var execution =
          executor.submit(
              () -> commandLine.execute("--workspace", workspace.toString(), "--stdin"));
      assertThat(input.awaitRead()).isTrue();

      assertThat(command.cancel()).isFalse();

      input.finish();
      assertThat(execution.get(5, TimeUnit.SECONDS)).isEqualTo(1);
    }
  }

  private static void writeWorkspaceConfiguration(Path workspace) throws IOException {
    Files.writeString(
        workspace.resolve(Workspace.CONFIG_FILE_NAME),
        """
        {
          "schema": "scalardb-analytics/workspace-configuration",
          "schemaVersion": "1",
          "catalogName": "catalog",
          "storeJdbcUrl": "jdbc:postgresql://localhost/catalog",
          "storePasswordEnv": "STORE_PASSWORD"
        }
        """);
  }

  private static final class CleanupFailingRunner implements QueryApplicationService.Runner {
    @Override
    public QueryResult execute(String sql, Optional<Duration> timeLimit) {
      var allocator = new RootAllocator();
      var reader = emptyReader(allocator);
      return new QueryResult() {
        @Override
        public String executionId() {
          return "cleanup-failure";
        }

        @Override
        public ArrowReader reader() {
          return reader;
        }

        @Override
        public void close() {
          try {
            reader.close();
          } catch (IOException e) {
            throw new AssertionError(e);
          } finally {
            allocator.close();
          }
          throw new QueryEngineException(
              executionId(), new QueryError.ShutdownFailed("Could not close the query result."));
        }
      };
    }

    @Override
    public void cancel() {}

    @Override
    public void close() {}
  }

  private static ArrowReader emptyReader(RootAllocator allocator) {
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

  private static final class BlockingInputStream extends InputStream {
    private final CountDownLatch reading = new CountDownLatch(1);
    private final CountDownLatch finished = new CountDownLatch(1);

    @Override
    public int read() throws IOException {
      reading.countDown();
      try {
        if (!finished.await(5, TimeUnit.SECONDS)) {
          throw new IOException("Timed out waiting for the test to release standard input.");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted while reading standard input.", e);
      }
      return -1;
    }

    boolean awaitRead() throws InterruptedException {
      return reading.await(5, TimeUnit.SECONDS);
    }

    void finish() {
      finished.countDown();
    }
  }
}
