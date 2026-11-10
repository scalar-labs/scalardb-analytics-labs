/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The Semantic Catalog's internal operations on its store.
 *
 * <p>The Semantic Catalog records each domain's knowledge as an append-only log and presents it as
 * Catalog Snapshots (see {@code docs/semantic-catalog.md}). This package is the entry point to the
 * store that holds those records: {@link
 * com.scalar.db.analytics.labs.semantic.SemanticCatalogStore} opens it with {@link
 * com.scalar.db.analytics.labs.semantic.StoreSettings} and offers the operations the rest of the
 * Semantic Catalog builds on.
 *
 * <p>These operations are internal. Judgement, Generators, and the public API described in {@code
 * docs/semantic-catalog/public-api.md} are built on top of them and are not part of this package.
 */
@NullMarked
package com.scalar.db.analytics.labs.semantic;

import org.jspecify.annotations.NullMarked;
