/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Preset;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ConcurrentWritersTest {
  private static SemanticCatalogStore store;
  private static SemanticCatalogStore otherProcess;
  private static ExecutorService executor;

  @BeforeAll
  static void open() {
    store = SemanticCatalogStore.open(TestStore.settings(), TestStore.PARSER);
    otherProcess = SemanticCatalogStore.open(TestStore.settings(), TestStore.PARSER);
    executor = Executors.newCachedThreadPool();
  }

  @AfterAll
  static void close() {
    executor.shutdownNow();
    otherProcess.close();
    store.close();
  }

  private static CommitRequest observeTable(String table) {
    var request = CommitRequest.builder("observe", "scanner");
    request.add(LogRecord.InputResource.physicalSchema(table, null));
    return request.build();
  }

  private static CommitRequest policyVersion() {
    var request = CommitRequest.builder("set-policy", "owner");
    request.add(new LogRecord.PolicyVersion(Preset.MANUAL, false, "owner", "test"));
    return request.build();
  }

  @Test
  void anOverlappedWriterRetriesOnTopOfTheCommitThatLandedFirst() throws Exception {
    var s = new Scenario(store);
    var before = s.lastCommit();
    Future<CommitResult> first;
    try (var blocker = TestStore.connect()) {
      // Hold the first writer inside its transaction, after it took its snapshot and before it
      // inserts its commit, while it looks up the Input Resource it proposes.
      blocker.setAutoCommit(false);
      try (var statement = blocker.createStatement()) {
        statement.execute("LOCK TABLE semantic_catalog.input_resources IN ACCESS EXCLUSIVE MODE");
      }
      first = executor.submit(() -> store.commit(s.domain, observeTable("shop.orders")));
      awaitBlockedWriter();

      var second = otherProcess.commit(s.domain, policyVersion());
      assertThat(second.commit()).hasValue(before + 1);
      blocker.rollback();
    }
    assertThat(first.get().commit()).hasValue(before + 2);
    assertThat(s.lastCommit()).isEqualTo(before + 2);
    assertThat(store.replayCheck(s.domain).matches()).isTrue();
  }

  @Test
  void concurrentWritersAllSucceedWithConsecutiveCommitNumbers() throws Exception {
    var s = new Scenario(store);
    var before = s.lastCommit();
    var writers = 4;
    var commitsEach = 10;
    var start = new CyclicBarrier(writers);
    var futures = new ArrayList<Future<List<Long>>>();
    for (var writer = 0; writer < writers; writer++) {
      var process = writer % 2 == 0 ? store : otherProcess;
      var name = writer;
      Callable<List<Long>> work =
          () -> {
            start.await();
            var commits = new ArrayList<Long>();
            for (var i = 0; i < commitsEach; i++) {
              commits.add(
                  process
                      .commit(s.domain, observeTable("table-" + name + "-" + i))
                      .commit()
                      .orElseThrow());
            }
            return commits;
          };
      futures.add(executor.submit(work));
    }
    var commits = new TreeSet<Long>();
    for (var future : futures) {
      commits.addAll(future.get());
    }
    assertThat(commits).hasSize(writers * commitsEach);
    assertThat(commits.first()).isEqualTo(before + 1);
    assertThat(commits.last()).isEqualTo(before + writers * commitsEach);
    assertThat(store.replayCheck(s.domain).matches()).isTrue();
  }

  private static void awaitBlockedWriter() throws SQLException, InterruptedException {
    var deadline = Instant.now().plus(Duration.ofSeconds(30));
    try (var monitor = TestStore.connect();
        var statement = monitor.createStatement()) {
      while (Instant.now().isBefore(deadline)) {
        try (var waiting =
            statement.executeQuery(
                "SELECT count(*) FROM pg_locks WHERE NOT granted"
                    + " AND relation = 'semantic_catalog.input_resources'::regclass")) {
          waiting.next();
          if (waiting.getLong(1) > 0) {
            return;
          }
        }
        Thread.sleep(20);
      }
    }
    throw new AssertionError("The first writer never reached the lock");
  }
}
