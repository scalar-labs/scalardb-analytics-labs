/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application;

import com.scalar.db.analytics.labs.duckdb.DuckDbQueryEngine;
import com.scalar.db.analytics.labs.query.QueryEngine;
import com.scalar.db.analytics.labs.query.QueryResult;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** Coordinates the non-interactive SQL execution use case. */
final class QueryApplicationService implements AutoCloseable {
  private final Supplier<QueryEngine> queryEngineFactory;
  private @Nullable QueryEngine queryEngine;

  static QueryApplicationService create() {
    return new QueryApplicationService(DuckDbQueryEngine::new);
  }

  QueryApplicationService(Supplier<QueryEngine> queryEngineFactory) {
    this.queryEngineFactory = queryEngineFactory;
  }

  QueryResult execute(String sql) {
    return queryEngine().execute(sql);
  }

  @Override
  public void close() {
    if (queryEngine != null) {
      queryEngine.close();
    }
  }

  private QueryEngine queryEngine() {
    if (queryEngine == null) {
      queryEngine = queryEngineFactory.get();
    }
    return queryEngine;
  }
}
