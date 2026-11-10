/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.scalar.db.analytics.labs.semantic.model.SemanticCatalogException;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.storage.JdbcStore;
import org.springframework.dao.DuplicateKeyException;

/**
 * The store of the Semantic Catalog and the internal operations on it (see {@code
 * docs/semantic-catalog/storage.md}).
 *
 * <p>Opening the store prepares it for use: it checks that the store is PostgreSQL and applies
 * pending schema changes. There is no separate migration step.
 *
 * <p>Every operation runs in one {@code REPEATABLE READ} transaction and judges only what that
 * transaction read; nothing is cached in the process across transactions. Several processes may
 * open the same store at once.
 *
 * <p>A store holds a connection pool and must be {@linkplain #close() closed}.
 */
public final class SemanticCatalogStore implements AutoCloseable {
  private final JdbcStore store;

  private SemanticCatalogStore(JdbcStore store) {
    this.store = store;
  }

  /**
   * Opens the Semantic Catalog's own connection pool to the store and prepares the {@code
   * semantic_catalog} schema.
   *
   * @throws SemanticCatalogException when the store cannot be reached or is not PostgreSQL; the
   *     message names the product found
   */
  public static SemanticCatalogStore open(StoreSettings settings) {
    return new SemanticCatalogStore(
        JdbcStore.open(
            settings.jdbcUrl(),
            settings.username(),
            settings.password(),
            settings.maximumPoolSize()));
  }

  /**
   * Creates a domain under a caller-supplied domain identifier. A domain is the unit of management
   * that assigns one responsible party to a body of data and its semantics, and every other record
   * belongs to exactly one domain.
   *
   * @param domain the domain identifier, in the form {@link Urns#DOMAIN} requires
   * @throws IllegalArgumentException when the identifier does not have that form
   * @throws SemanticCatalogException when the domain already exists
   */
  public void createDomain(String domain) {
    Urns.requireDomain(domain);
    try {
      store.writeOnce(
          transaction -> {
            if (transaction.domainExists(domain)) {
              throw new SemanticCatalogException("Domain " + domain + " already exists");
            }
            transaction.insertDomain(domain);
            return Boolean.TRUE;
          });
    } catch (DuplicateKeyException e) {
      // A concurrent caller created the same domain after this transaction's check.
      throw new SemanticCatalogException("Domain " + domain + " already exists", e);
    }
  }

  /** Whether a domain exists. */
  public boolean domainExists(String domain) {
    return store.read(transaction -> transaction.domainExists(domain));
  }

  /** Closes the connection pool. */
  @Override
  public void close() {
    store.close();
  }
}
