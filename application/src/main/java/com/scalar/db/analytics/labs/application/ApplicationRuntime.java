/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application;

import com.scalar.db.analytics.labs.duckdb.DuckDbQueryEngine;
import com.scalar.db.analytics.labs.duckdb.NativeCatalogProvider;
import com.scalar.db.analytics.labs.physical.PhysicalCatalog;
import com.scalar.db.analytics.labs.physical.PhysicalCatalogSettings;
import com.scalar.db.analytics.labs.physical.SerializedCallResult;

/**
 * Composes the Physical Catalog and the Query Engine for one application process.
 *
 * <p>The catalog starts first and stops last. The Query Engine registers the catalog as the host of
 * its product extension, so DuckDB reaches catalog metadata only through the read-only serialized
 * calls the catalog admits, while the application uses the catalog's typed operations directly.
 */
final class ApplicationRuntime implements AutoCloseable {
  private final PhysicalCatalog physicalCatalog;
  private final DuckDbQueryEngine queryEngine;

  private ApplicationRuntime(PhysicalCatalog physicalCatalog, DuckDbQueryEngine queryEngine) {
    this.physicalCatalog = physicalCatalog;
    this.queryEngine = queryEngine;
  }

  static ApplicationRuntime start(PhysicalCatalogSettings catalogSettings) {
    var physicalCatalog = PhysicalCatalog.start(catalogSettings);
    try {
      return new ApplicationRuntime(
          physicalCatalog, new DuckDbQueryEngine(nativeCatalogProvider(physicalCatalog)));
    } catch (RuntimeException e) {
      try {
        physicalCatalog.close();
      } catch (RuntimeException closeFailure) {
        e.addSuppressed(closeFailure);
      }
      throw e;
    }
  }

  PhysicalCatalog physicalCatalog() {
    return physicalCatalog;
  }

  DuckDbQueryEngine queryEngine() {
    return queryEngine;
  }

  @Override
  public void close() {
    // Closing the engine unregisters the catalog from the extension before the catalog stops.
    try {
      queryEngine.close();
    } catch (RuntimeException e) {
      try {
        physicalCatalog.close();
      } catch (RuntimeException closeFailure) {
        e.addSuppressed(closeFailure);
      }
      throw e;
    }
    physicalCatalog.close();
  }

  private static NativeCatalogProvider nativeCatalogProvider(PhysicalCatalog physicalCatalog) {
    return (fullMethodName, request) ->
        switch (physicalCatalog.callSerialized(fullMethodName, request)) {
          case SerializedCallResult.Response response ->
              new NativeCatalogProvider.Result.Response(response.message());
          case SerializedCallResult.Failure failure ->
              new NativeCatalogProvider.Result.Failure(failure.status());
        };
  }
}
