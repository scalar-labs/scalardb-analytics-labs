/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The interim scanner read path: reads PostgreSQL and MySQL data sources through DuckDB's packaged
 * scanner extensions until a connector serves the source type.
 *
 * <p>Every source-type-specific decision of this path lives in this package, so the Query Engine's
 * permanent API stays independent of source types and the package can be removed once connectors
 * replace it.
 */
@NullMarked
package com.scalar.db.analytics.labs.duckdb.scanner;

import org.jspecify.annotations.NullMarked;
