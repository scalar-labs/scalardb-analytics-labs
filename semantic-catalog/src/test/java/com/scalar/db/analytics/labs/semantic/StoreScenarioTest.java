/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.analytics.labs.semantic.model.CommitRejectedException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;

/**
 * Scenarios against the shared PostgreSQL store. After every scenario, the materialized records of
 * each domain it used must equal a replay of that domain's log.
 */
abstract class StoreScenarioTest {
  static SemanticCatalogStore store;
  private final List<Scenario> scenarios = new ArrayList<>();

  @BeforeAll
  static void open() {
    store = SemanticCatalogStore.open(TestStore.settings());
  }

  @AfterAll
  static void close() {
    store.close();
  }

  Scenario scenario() {
    var scenario = new Scenario(store);
    scenarios.add(scenario);
    return scenario;
  }

  @AfterEach
  void materializedRecordsEqualReplay() {
    for (var scenario : scenarios) {
      var result = store.replayCheck(scenario.domain);
      assertThat(result.missingFromStore()).as("rows missing from the store").isEmpty();
      assertThat(result.unexpectedInStore()).as("rows the replay does not produce").isEmpty();
      assertThat(result.replayedCommits()).isEqualTo(scenario.lastCommit());
    }
  }

  static void assertRejected(Scenario s, Scenario.Commit commit, String reason) {
    var before = s.lastCommit();
    assertThatThrownBy(() -> s.write(commit))
        .isInstanceOf(CommitRejectedException.class)
        .hasMessageContaining(reason);
    assertThat(s.lastCommit()).isEqualTo(before);
  }
}
