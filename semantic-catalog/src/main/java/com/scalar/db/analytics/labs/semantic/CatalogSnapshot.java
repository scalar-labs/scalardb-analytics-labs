/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.scalar.db.analytics.labs.semantic.model.FactRevision;
import com.scalar.db.analytics.labs.semantic.model.LogPosition;
import com.scalar.db.analytics.labs.semantic.model.LogRecordJson;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.projection.FactRecord;
import com.scalar.db.analytics.labs.semantic.storage.StoreTransaction;
import java.util.Optional;

/**
 * The Catalog Snapshot of one domain at one commit: what consumers read as the catalog.
 *
 * <p>The catalog presents, for each Fact Address, the Fact selected at the commit; elements, Fact
 * Addresses, and Facts that are not selected are material for judgement, not catalog content. A
 * snapshot is read within one {@code REPEATABLE READ} transaction and is valid only inside the
 * reader it is passed to. It never replays the log. A Fact's state and content come from its
 * materialized current Fact Record when the Fact has not changed since the commit; a Fact changed
 * since has only its own events folded up to the commit.
 *
 * <p>Which Fact each Fact Address presents is not materialized yet, so a snapshot does not select
 * one.
 */
public final class CatalogSnapshot {
  private final StoreTransaction transaction;
  private final String domain;
  private final long commit;

  CatalogSnapshot(StoreTransaction transaction, String domain, long commit) {
    this.transaction = transaction;
    this.domain = domain;
    this.commit = commit;
  }

  public String domain() {
    return domain;
  }

  public long commit() {
    return commit;
  }

  public String urn() {
    return Urns.catalogSnapshot(domain, commit);
  }

  /**
   * A Fact Record's state at this commit.
   *
   * @return empty when the Fact did not exist or was not accepted by this commit
   */
  public Optional<FactState> factState(String fact) {
    return recordAt(fact, endOfCommit()).flatMap(FactRecord::state);
  }

  /**
   * The Fact's latest Fact Revision at this commit.
   *
   * @return empty when the Fact did not exist or was not accepted by this commit
   */
  public Optional<FactRevision> latestRevision(String fact) {
    return recordAt(fact, endOfCommit()).flatMap(FactRecord::revision);
  }

  /**
   * A Fact Revision by URN.
   *
   * @return empty when no Fact Event at or before this commit produced it
   */
  public Optional<FactRevision> revision(String revision) {
    if (!inDomain(revision, Urns.FACT_REVISION)) {
      return Optional.empty();
    }
    var event = Urns.revisionEvent(revision);
    if (event.commit() > commit) {
      return Optional.empty();
    }
    return recordAt(Urns.revisionFact(revision), event)
        .flatMap(FactRecord::revision)
        .filter(found -> found.event().equals(event));
  }

  /**
   * The Fact Record as it stood after the Fact's last event at or before a position. The current
   * record answers when its latest event is at or before the position; otherwise the Fact changed
   * since, and its events are folded up to the position.
   */
  private Optional<FactRecord> recordAt(String fact, LogPosition upTo) {
    if (commit == 0 || !inDomain(fact, Urns.FACT)) {
      return Optional.empty();
    }
    var current = transaction.factRecord(domain, fact);
    if (current.isEmpty()) {
      return Optional.empty();
    }
    if (current.get().latestEvent().compareTo(upTo) <= 0) {
      return Optional.of(
          FactRecord.restore(
              LogRecordJson.fromStored(current.get().record(), FactRecord.Stored.class)));
    }
    var records = transaction.factRecords(domain, fact, upTo.commit());
    if (records.isEmpty()) {
      return Optional.empty();
    }
    var folded = FactRecord.fold(domain, records, upTo);
    return folded.state().isPresent() ? Optional.of(folded) : Optional.empty();
  }

  private LogPosition endOfCommit() {
    return new LogPosition(commit, Integer.MAX_VALUE);
  }

  private boolean inDomain(String urn, String kind) {
    return Urns.kind(urn).equals(kind) && Urns.domain(urn).equals(domain);
  }
}
