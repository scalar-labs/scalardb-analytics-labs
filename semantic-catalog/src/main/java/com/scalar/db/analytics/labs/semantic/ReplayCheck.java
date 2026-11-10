/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import java.util.List;

/**
 * The outcome of the replay check: the comparison of a domain's materialized records with the
 * result of replaying its whole log from empty, URNs included (see {@code
 * docs/semantic-catalog/storage.md}, "Replay check").
 *
 * <p>Materialized records must always equal what replaying the log gives, so any difference means
 * the store diverged from its log.
 *
 * @param replayedCommits the number of commits replayed
 * @param missingFromStore the rows the replay produced that the store does not hold
 * @param unexpectedInStore the rows the store holds that the replay did not produce
 */
public record ReplayCheck(
    long replayedCommits, List<String> missingFromStore, List<String> unexpectedInStore) {
  public ReplayCheck {
    missingFromStore = List.copyOf(missingFromStore);
    unexpectedInStore = List.copyOf(unexpectedInStore);
  }

  /** Whether the materialized records equal the replay. */
  public boolean matches() {
    return missingFromStore.isEmpty() && unexpectedInStore.isEmpty();
  }
}
