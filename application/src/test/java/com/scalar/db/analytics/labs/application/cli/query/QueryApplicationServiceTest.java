/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.cli.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.analytics.labs.query.QueryResult;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class QueryApplicationServiceTest {
  @Test
  void startsRunnerOnlyWhenItIsUsed() {
    var starts = new AtomicInteger();
    var closes = new AtomicInteger();

    try (var service =
        new QueryApplicationService(
            () -> {
              starts.incrementAndGet();
              return new TestRunner(closes, new AtomicInteger());
            })) {
      assertThat(starts).hasValue(0);

      service.execute("SELECT 1", Optional.empty()).close();
      service.execute("SELECT 2", Optional.of(Duration.ofSeconds(1))).close();

      assertThat(starts).hasValue(1);
    }

    assertThat(closes).hasValue(1);
  }

  @Test
  void closingUnusedServiceDoesNotStartRunner() {
    var starts = new AtomicInteger();

    var service =
        new QueryApplicationService(
            () -> {
              starts.incrementAndGet();
              return new TestRunner(new AtomicInteger(), new AtomicInteger());
            });
    service.close();

    assertThat(starts).hasValue(0);
  }

  @Test
  void cancellationIsForwardedToTheActiveRunner() {
    var cancellations = new AtomicInteger();
    try (var service =
        new QueryApplicationService(() -> new TestRunner(new AtomicInteger(), cancellations))) {
      service.execute("SELECT 1", Optional.empty()).close();

      service.cancel();
    }

    assertThat(cancellations).hasValue(1);
  }

  @Test
  void cancellationBeforeRunnerCreationIsLatched() {
    var cancellations = new AtomicInteger();
    try (var service =
        new QueryApplicationService(() -> new TestRunner(new AtomicInteger(), cancellations))) {
      service.cancel();

      service.execute("SELECT 1", Optional.empty()).close();
    }

    assertThat(cancellations).hasValue(1);
  }

  private static final class TestRunner implements QueryApplicationService.Runner {
    private final AtomicInteger closes;
    private final AtomicInteger cancellations;

    private TestRunner(AtomicInteger closes, AtomicInteger cancellations) {
      this.closes = closes;
      this.cancellations = cancellations;
    }

    @Override
    public QueryResult execute(String sql, Optional<Duration> timeLimit) {
      return new QueryResult() {
        @Override
        public String executionId() {
          return "test-execution";
        }

        @Override
        public org.apache.arrow.vector.ipc.ArrowReader reader() {
          throw new UnsupportedOperationException();
        }

        @Override
        public void close() {}
      };
    }

    @Override
    public void cancel() {
      cancellations.incrementAndGet();
    }

    @Override
    public void close() {
      closes.incrementAndGet();
    }
  }
}
