/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.storage.StoreTransaction;

/**
 * The Catalog Snapshot of one domain at one commit: what consumers read as the catalog.
 *
 * <p>The catalog presents, for each Fact Address, the Fact selected at the commit; elements, Fact
 * Addresses, and Facts that are not selected are material for judgement, not catalog content. A
 * snapshot is read within one {@code REPEATABLE READ} transaction from the materialized records,
 * never by replaying the log, and is valid only inside the reader it is passed to.
 *
 * <p>A Fact becomes catalog content only once it is confirmed and selected. The log does not yet
 * record Fact Events, so nothing is selected and a snapshot presents only its commit and URN.
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
}
