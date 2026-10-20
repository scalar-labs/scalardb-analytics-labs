/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.analytics.labs.query.QueryEngine;
import com.scalar.db.analytics.labs.query.QueryResult;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class QueryApplicationServiceTest {
  @Test
  void startsQueryEngineOnlyWhenItIsUsed() {
    var starts = new AtomicInteger();
    var closes = new AtomicInteger();

    try (var service =
        new QueryApplicationService(
            () -> {
              starts.incrementAndGet();
              return new TestQueryEngine(closes);
            })) {
      assertThat(starts).hasValue(0);

      service.execute("SELECT 1");
      service.execute("SELECT 2");

      assertThat(starts).hasValue(1);
    }

    assertThat(closes).hasValue(1);
  }

  @Test
  void closingUnusedServiceDoesNotStartQueryEngine() {
    var starts = new AtomicInteger();

    var service =
        new QueryApplicationService(
            () -> {
              starts.incrementAndGet();
              return new TestQueryEngine(new AtomicInteger());
            });
    assertThat(starts).hasValue(0);

    service.close();

    assertThat(starts).hasValue(0);
  }

  private static final class TestQueryEngine implements QueryEngine {
    private final AtomicInteger closes;

    private TestQueryEngine(AtomicInteger closes) {
      this.closes = closes;
    }

    @Override
    public QueryResult execute(String sql) {
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
    public void close() {
      closes.incrementAndGet();
    }
  }
}
