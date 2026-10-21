/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application;

import com.scalar.db.analytics.labs.duckdb.DuckDbQueryEngine;
import com.scalar.db.analytics.labs.duckdb.NativeCatalogProvider;
import com.scalar.db.analytics.labs.duckdb.SourceAttachment;
import com.scalar.db.analytics.labs.duckdb.scanner.ScannerPath;
import com.scalar.db.analytics.labs.physical.DataSourceConnection;
import com.scalar.db.analytics.labs.physical.PhysicalCatalog;
import com.scalar.db.analytics.labs.physical.PhysicalCatalogError;
import com.scalar.db.analytics.labs.physical.PhysicalCatalogException;
import com.scalar.db.analytics.labs.physical.PhysicalCatalogSettings;
import com.scalar.db.analytics.labs.physical.SerializedCallResult;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Composes the Physical Catalog and the Query Engine for one application process.
 *
 * <p>The catalog starts first and stops last. The Query Engine registers the catalog as the host of
 * its product extension, so DuckDB reaches catalog metadata only through the read-only serialized
 * calls the catalog admits, while the application uses the catalog's typed operations directly. The
 * engine attaches the data sources registered in one catalog, with connection settings that the
 * application resolves from the catalog, so SQL addresses sources only by their registered names.
 */
final class ApplicationRuntime implements AutoCloseable {
  private final PhysicalCatalog physicalCatalog;
  private @Nullable DuckDbQueryEngine queryEngine;

  private ApplicationRuntime(PhysicalCatalog physicalCatalog) {
    this.physicalCatalog = physicalCatalog;
  }

  static ApplicationRuntime start(PhysicalCatalogSettings catalogSettings) {
    return new ApplicationRuntime(PhysicalCatalog.start(catalogSettings));
  }

  PhysicalCatalog physicalCatalog() {
    return physicalCatalog;
  }

  /**
   * Starts the Query Engine over the data sources currently registered in a catalog.
   *
   * <p>Each data source is queryable as {@code name.schema.table}. A runtime starts at most one
   * engine, which it owns and closes before the catalog. Sources registered after the engine
   * starts, and source schema changes the scanners have already cached, are visible only to a new
   * runtime.
   *
   * @throws PhysicalCatalogException if the catalog cannot list or resolve its data sources
   * @throws com.scalar.db.analytics.labs.query.QueryEngineException if the engine cannot start or
   *     cannot attach a data source
   */
  synchronized DuckDbQueryEngine startQueryEngine(String catalogName) {
    if (queryEngine != null) {
      throw new IllegalStateException("A Query Engine is already running");
    }
    var engine =
        new DuckDbQueryEngine(
            nativeCatalogProvider(physicalCatalog), sourceAttachments(catalogName));
    queryEngine = engine;
    return engine;
  }

  @Override
  public synchronized void close() {
    // Closing the engine unregisters the catalog from the extension before the catalog stops.
    if (queryEngine != null) {
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
    }
    physicalCatalog.close();
  }

  private List<SourceAttachment> sourceAttachments(String catalogName) {
    var attachments = new ArrayList<SourceAttachment>();
    for (var dataSource : physicalCatalog.listDataSources(catalogName)) {
      var connection =
          physicalCatalog
              .resolveConnection(catalogName, dataSource.name())
              .orElseThrow(
                  () ->
                      new PhysicalCatalogException(
                          new PhysicalCatalogError.OperationFailed(
                              "A data source was removed while the Query Engine started.")));
      attachments.add(readPath(dataSource.name(), connection));
    }
    return attachments;
  }

  /**
   * Chooses how the engine reads one data source. Every source uses the interim scanner path until
   * a connector serves its source type; a data source then moves to the connector path here.
   */
  private static SourceAttachment readPath(String name, DataSourceConnection connection) {
    return ScannerPath.attachment(name, connection);
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
