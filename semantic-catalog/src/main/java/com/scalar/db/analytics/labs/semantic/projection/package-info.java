/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * How the log determines the materialized records, without any storage.
 *
 * <p>A {@link com.scalar.db.analytics.labs.semantic.projection.Projection} holds materialized rows
 * in memory. Writing a commit loads only the rows the commit touches from the store and applies the
 * commit's records to them with a {@link
 * com.scalar.db.analytics.labs.semantic.projection.Projector} that also validates each record; the
 * rows that changed are then written back. The replay check applies the whole log to an empty
 * projection with the same projector and compares every row with the store. Both paths therefore
 * derive the materialized records by the same rules.
 *
 * <p>A {@link com.scalar.db.analytics.labs.semantic.projection.FactRecord} folds one Fact's events
 * into its state and Fact Revisions. The projector judges each Fact Event against it before
 * applying the event, and derives the state rows and presentable periods from it at the end of each
 * commit.
 */
@NullMarked
package com.scalar.db.analytics.labs.semantic.projection;

import org.jspecify.annotations.NullMarked;
