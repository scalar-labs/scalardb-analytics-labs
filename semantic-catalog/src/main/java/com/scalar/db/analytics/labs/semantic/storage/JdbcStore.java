/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.storage;

import com.scalar.db.analytics.labs.semantic.model.SemanticCatalogException;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Semantic Catalog's own connection pool to the store, and the transactions it runs there (see
 * {@code docs/semantic-catalog/storage.md}, "Schema and connections").
 *
 * <p>The pool is separate from the Physical Catalog's, whose connections live inside ScalarDB and
 * cannot be shared. Every transaction runs at {@code REPEATABLE READ}, so everything one operation
 * reads comes from one snapshot of the store; a read-only transaction neither blocks nor collides
 * with writes.
 */
public final class JdbcStore implements AutoCloseable {
  private static final String POSTGRESQL = "PostgreSQL";

  /** How many times a write that keeps colliding is attempted before it fails. */
  private static final int WRITE_ATTEMPTS = 100;

  private final HikariDataSource dataSource;
  private final JdbcClient jdbc;
  private final TransactionTemplate writes;
  private final TransactionTemplate reads;

  private JdbcStore(HikariDataSource dataSource) {
    this.dataSource = dataSource;
    this.jdbc = JdbcClient.create(dataSource);
    var transactionManager = new JdbcTransactionManager(dataSource);
    this.writes = new TransactionTemplate(transactionManager);
    writes.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.reads = new TransactionTemplate(transactionManager);
    reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    reads.setReadOnly(true);
  }

  /**
   * Opens a connection pool to the store, checks that the store is PostgreSQL, and applies pending
   * schema changes. The pool is closed again when any of this fails.
   *
   * @throws SemanticCatalogException when the store cannot be reached or is not PostgreSQL
   */
  public static JdbcStore open(
      String jdbcUrl, @Nullable String username, @Nullable String password, int maximumPoolSize) {
    var dataSource =
        DataSourceBuilder.create()
            .type(HikariDataSource.class)
            .url(jdbcUrl)
            .username(username)
            .password(password)
            .build();
    dataSource.setPoolName("semantic-catalog");
    dataSource.setMaximumPoolSize(maximumPoolSize);
    dataSource.setMinimumIdle(0);
    try {
      requirePostgreSql(dataSource);
      var store = new JdbcStore(dataSource);
      new SchemaMigrator(dataSource, new JdbcTransactionManager(dataSource)).migrate();
      return store;
    } catch (RuntimeException e) {
      dataSource.close();
      throw e;
    }
  }

  /**
   * The Semantic Catalog relies on {@code REPEATABLE READ} snapshots and {@code jsonb}, so it fails
   * at startup, with the product it found, on any other database.
   */
  private static void requirePostgreSql(HikariDataSource dataSource) {
    String product;
    try (var connection = dataSource.getConnection()) {
      product = connection.getMetaData().getDatabaseProductName();
    } catch (SQLException e) {
      throw new SemanticCatalogException("Cannot connect to the Semantic Catalog store", e);
    }
    if (!POSTGRESQL.equals(product)) {
      throw new SemanticCatalogException(
          "The Semantic Catalog store must be PostgreSQL, because the Semantic Catalog relies on"
              + " REPEATABLE READ snapshots and jsonb, but the configured store is "
              + product);
    }
  }

  /**
   * Runs work that appends a commit, in one {@code REPEATABLE READ} transaction, as if writes to
   * one domain ran one at a time (see {@code docs/design/semantic-catalog-write-serialization.md}).
   *
   * <p>The work inserts a commit numbered one past the last commit in its snapshot. When a
   * concurrent writer committed that number first, the insert fails with {@link
   * StoreTransaction.CommitNumberTaken}, and the work runs again from the start in a new
   * transaction, judging on top of the commit that landed first. A serialization failure is retried
   * the same way. Nothing the failed attempt wrote remains.
   *
   * <p>Every writer of a domain collides on the commit number before it writes anything else, so
   * any other unique violation means a record escaped the identity matching done in the
   * transaction. It is reported at once rather than retried.
   *
   * @throws SemanticCatalogException when the attempts run out, or when a unique constraint other
   *     than the commit number is violated
   */
  public <T> T write(Function<StoreTransaction, T> work) {
    for (var attempt = 1; ; attempt++) {
      try {
        return run(writes, work);
      } catch (StoreTransaction.CommitNumberTaken | ConcurrencyFailureException e) {
        if (attempt >= WRITE_ATTEMPTS) {
          throw new SemanticCatalogException(
              "A write kept colliding with concurrent writers after " + attempt + " attempts", e);
        }
        backOff(attempt);
      } catch (DuplicateKeyException e) {
        throw new SemanticCatalogException(
            "A write violated a unique constraint that identity matching should have satisfied: "
                + e.getMostSpecificCause().getMessage(),
            e);
      }
    }
  }

  /**
   * Runs work that writes in one {@code REPEATABLE READ} transaction, without retrying, for writes
   * that append no commit. An exception thrown by the work rolls the transaction back, so nothing
   * it wrote remains.
   */
  public <T> T writeOnce(Function<StoreTransaction, T> work) {
    return run(writes, work);
  }

  /** Runs work that only reads in one read-only {@code REPEATABLE READ} transaction. */
  public <T> T read(Function<StoreTransaction, T> work) {
    return run(reads, work);
  }

  /** Runs work in a new transaction of the template, passing it that transaction's statements. */
  private <T> T run(TransactionTemplate template, Function<StoreTransaction, T> work) {
    var result = template.execute(status -> work.apply(new StoreTransaction(jdbc)));
    if (result == null) {
      throw new IllegalStateException("Store work returns a result");
    }
    return result;
  }

  /**
   * Waits a short random time before the next attempt, so colliding writers do not retry in lock
   * step.
   */
  private static void backOff(int attempt) {
    try {
      Thread.sleep(ThreadLocalRandom.current().nextLong(1, 2L + Math.min(attempt, 20)));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SemanticCatalogException("Interrupted while retrying a write", e);
    }
  }

  /** Closes the connection pool. */
  @Override
  public void close() {
    dataSource.close();
  }
}
