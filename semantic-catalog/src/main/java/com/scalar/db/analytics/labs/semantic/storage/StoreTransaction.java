/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.storage;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The statements one transaction runs on the {@code semantic_catalog} schema. Every SQL statement
 * of the Semantic Catalog is in this class, so the tables stay behind the storage adapter.
 *
 * <p>An instance is valid only inside the transaction {@link JdbcStore} passes it to.
 */
public final class StoreTransaction {
  private final JdbcClient jdbc;

  StoreTransaction(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // Domains

  /** Whether a domain exists in this transaction's snapshot. */
  public boolean domainExists(String domain) {
    return jdbc.sql("SELECT count(*) FROM semantic_catalog.domains WHERE domain = ?")
            .param(domain)
            .query(Long.class)
            .single()
        > 0;
  }

  /**
   * Inserts a domain.
   *
   * @throws org.springframework.dao.DuplicateKeyException when the domain exists, including one a
   *     concurrent transaction created
   */
  public void insertDomain(String domain) {
    jdbc.sql("INSERT INTO semantic_catalog.domains (domain, created_at) VALUES (?, now())")
        .param(domain)
        .update();
  }
}
