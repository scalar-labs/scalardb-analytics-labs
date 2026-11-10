/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The storage adapter: the only place that knows the {@code semantic_catalog} schema's tables and
 * SQL (see {@code docs/semantic-catalog/storage.md} and {@code
 * docs/design/semantic-catalog-store.md}).
 *
 * <p>The store is the Physical Catalog's PostgreSQL database, accessed directly through Spring JDBC
 * rather than through ScalarDB. The two catalogs share the database but neither reads the other's
 * schema. Every operation runs in one {@code REPEATABLE READ} transaction, and a write that
 * collides with a concurrent writer on the next commit number starts again in a new transaction
 * (see {@code docs/design/semantic-catalog-write-serialization.md}).
 */
@NullMarked
package com.scalar.db.analytics.labs.semantic.storage;

import org.jspecify.annotations.NullMarked;
