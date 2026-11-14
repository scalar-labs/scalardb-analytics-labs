/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.storage;

import java.util.List;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates the {@code semantic_catalog} schema and applies, in order, the schema changes not yet
 * recorded in its schema version table (see {@code docs/semantic-catalog/storage.md}, "Schema and
 * connections").
 *
 * <p>Each change and its version row are applied in one transaction, which PostgreSQL's
 * transactional DDL allows, so a change is either fully applied and recorded or not at all. The
 * version row is inserted before the change's script runs: processes starting together that try the
 * same change collide on the version row's primary key, and the loser rolls back, rereads what was
 * applied, and continues. Only the winner runs the script.
 *
 * <p>The Physical Catalog's schema is migrated by the embedded upstream catalog; this class manages
 * only the Semantic Catalog's own schema.
 */
final class SchemaMigrator {
  /**
   * One schema change.
   *
   * @param version the position of the change in the ordered list, recorded once applied
   * @param description what the change adds, recorded with the version
   * @param script the class path of the SQL script that applies the change
   */
  record Change(int version, String description, String script) {}

  /** Every schema change, in the order they are applied. A change is never edited once released. */
  static final List<Change> CHANGES =
      List.of(
          new Change(
              1,
              "Semantic Catalog",
              "com/scalar/db/analytics/labs/semantic/storage/V1__semantic_catalog.sql"));

  private static final int SETUP_ATTEMPTS = 5;

  private final DataSource dataSource;
  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;

  SchemaMigrator(DataSource dataSource, PlatformTransactionManager transactionManager) {
    this.dataSource = dataSource;
    this.jdbc = JdbcClient.create(dataSource);
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /** Brings the schema up to date with {@link #CHANGES}. */
  void migrate() {
    createSchemaAndVersionTable();
    for (var change : CHANGES) {
      if (applied(change)) {
        continue;
      }
      try {
        transactions.executeWithoutResult(
            status -> {
              if (applied(change)) {
                return;
              }
              jdbc.sql(
                      "INSERT INTO semantic_catalog.schema_versions"
                          + " (version, description, applied_at) VALUES (?, ?, now())")
                  .params(change.version(), change.description())
                  .update();
              var connection = DataSourceUtils.getConnection(dataSource);
              ScriptUtils.executeSqlScript(connection, new ClassPathResource(change.script()));
            });
      } catch (DuplicateKeyException e) {
        // A concurrent starter applied this change first.
        if (!applied(change)) {
          throw e;
        }
      }
    }
  }

  /** Whether the schema version table records the change as applied. */
  private boolean applied(Change change) {
    return jdbc.sql("SELECT count(*) FROM semantic_catalog.schema_versions WHERE version = ?")
            .param(change.version())
            .query(Long.class)
            .single()
        > 0;
  }

  /**
   * {@code IF NOT EXISTS} still lets two concurrent creations of the same object collide in the
   * system catalogs, so a failed creation is retried and then succeeds as a no-op.
   */
  private void createSchemaAndVersionTable() {
    for (var attempt = 1; ; attempt++) {
      try {
        jdbc.sql("CREATE SCHEMA IF NOT EXISTS semantic_catalog").update();
        jdbc.sql(
                "CREATE TABLE IF NOT EXISTS semantic_catalog.schema_versions ("
                    + " version integer PRIMARY KEY,"
                    + " description text NOT NULL,"
                    + " applied_at timestamptz NOT NULL)")
            .update();
        return;
      } catch (DataAccessException e) {
        if (attempt >= SETUP_ATTEMPTS) {
          throw e;
        }
      }
    }
  }
}
