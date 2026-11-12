/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.analytics.labs.semantic.model.SemanticCatalogException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StartupTest {
  /** Every schema change, in order, that a started store must have applied exactly once. */
  private static final List<Integer> SCHEMA_VERSIONS = List.of(1, 2, 3);

  private static ExecutorService executor;

  @BeforeAll
  static void start() {
    executor = Executors.newCachedThreadPool();
  }

  @AfterAll
  static void stop() {
    executor.shutdownNow();
  }

  @Test
  void aStoreThatIsNotPostgreSqlFailsStartupWithTheReason() {
    assertThatThrownBy(
            () -> SemanticCatalogStore.open(new StoreSettings("jdbc:duckdb:", null, null)))
        .isInstanceOf(SemanticCatalogException.class)
        .hasMessageContaining("must be PostgreSQL")
        .hasMessageContaining("DuckDB");
  }

  @Test
  void concurrentStartersApplyEachSchemaChangeOnce() throws Exception {
    var database = TestStore.newDatabase();
    var starters = 8;
    var start = new CyclicBarrier(starters);
    var futures = new ArrayList<Future<Boolean>>();
    for (var i = 0; i < starters; i++) {
      futures.add(
          executor.submit(
              () -> {
                start.await();
                try (var opened = SemanticCatalogStore.open(TestStore.settings(database))) {
                  return opened != null;
                }
              }));
    }
    for (var future : futures) {
      assertThat(future.get()).isTrue();
    }
    try (var connection = TestStore.connect(database);
        var statement = connection.createStatement();
        var versions =
            statement.executeQuery(
                "SELECT version, count(*) FROM semantic_catalog.schema_versions GROUP BY version"
                    + " ORDER BY version")) {
      var applied = new ArrayList<Integer>();
      while (versions.next()) {
        assertThat(versions.getLong(2)).isEqualTo(1);
        applied.add(versions.getInt(1));
      }
      assertThat(applied).isEqualTo(SCHEMA_VERSIONS);
    }
    // A later start finds nothing to apply, and the store is usable.
    try (var reopened = SemanticCatalogStore.open(TestStore.settings(database))) {
      var domain = TestStore.newDomain();
      reopened.createDomain(domain);
      assertThat(reopened.domainExists(domain)).isTrue();
    }
  }

  @Test
  void aDomainIsCreatedOnceUnderItsIdentifier() {
    try (var store = SemanticCatalogStore.open(TestStore.settings())) {
      var domain = TestStore.newDomain();
      assertThat(store.domainExists(domain)).isFalse();
      store.createDomain(domain);
      assertThat(store.domainExists(domain)).isTrue();
      assertThatThrownBy(() -> store.createDomain(domain))
          .isInstanceOf(SemanticCatalogException.class)
          .hasMessageContaining("already exists");
      assertThatThrownBy(() -> store.createDomain("Not A Domain"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("lowercase");
    }
  }
}
