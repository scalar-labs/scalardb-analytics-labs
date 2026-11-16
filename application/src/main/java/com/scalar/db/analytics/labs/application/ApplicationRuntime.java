/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application;

import com.scalar.db.analytics.labs.connector.ConnectorException;
import com.scalar.db.analytics.labs.connector.ConnectorLibrary;
import com.scalar.db.analytics.labs.duckdb.DuckDbQueryEngine;
import com.scalar.db.analytics.labs.duckdb.NativeCatalogProvider;
import com.scalar.db.analytics.labs.duckdb.SourceAttachment;
import com.scalar.db.analytics.labs.duckdb.connector.ConnectorPath;
import com.scalar.db.analytics.labs.physical.PhysicalCatalog;
import com.scalar.db.analytics.labs.physical.PhysicalCatalogError;
import com.scalar.db.analytics.labs.physical.PhysicalCatalogException;
import com.scalar.db.analytics.labs.physical.PhysicalCatalogSettings;
import com.scalar.db.analytics.labs.physical.SerializedCallResult;
import com.scalar.db.analytics.labs.query.QueryEngineException;
import com.scalar.db.analytics.labs.query.QueryError;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Composes the Physical Catalog and the Query Engine for one application process.
 *
 * <p>The catalog starts first and stops last. The Query Engine registers the catalog as the host of
 * its product extension, so DuckDB reaches catalog metadata only through the read-only serialized
 * calls the catalog admits, while the application uses the catalog's typed operations directly. The
 * engine attaches the data sources registered in one catalog, with connection settings that the
 * application resolves from the catalog, so SQL addresses sources only by their registered names.
 *
 * <p>The runtime owns the connector API library for the process. It initializes the library the
 * first time an operation or the Query Engine needs it, before the engine loads the connectors
 * extension, so the extension uses that initialization, and shuts the library down after the engine
 * has closed.
 */
public final class ApplicationRuntime implements AutoCloseable {
  private final PhysicalCatalog physicalCatalog;
  private @Nullable ConnectorLibrary connectorLibrary;
  private @Nullable DuckDbQueryEngine queryEngine;

  private ApplicationRuntime(PhysicalCatalog physicalCatalog) {
    this.physicalCatalog = physicalCatalog;
  }

  public static ApplicationRuntime start(PhysicalCatalogSettings catalogSettings) {
    return new ApplicationRuntime(PhysicalCatalog.start(catalogSettings));
  }

  public PhysicalCatalog physicalCatalog() {
    return physicalCatalog;
  }

  /**
   * The connector API library of this process, initialized on first use and shut down when the
   * runtime closes.
   *
   * @throws IllegalStateException if the library is already open in this process outside this
   *     runtime
   */
  public synchronized ConnectorLibrary connectorLibrary() throws ConnectorException, IOException {
    var library = connectorLibrary;
    if (library == null) {
      library = ConnectorLibrary.open();
      connectorLibrary = library;
    }
    return library;
  }

  /**
   * Starts the Query Engine over the data sources currently registered in a catalog.
   *
   * <p>Each data source is queryable as {@code name.schema.table}. A runtime starts at most one
   * engine, which it owns and closes before the catalog. Sources registered after the engine starts
   * are visible only to a new runtime. Every data source is read through its Connector.
   *
   * @throws PhysicalCatalogException if the catalog cannot list or resolve its data sources
   * @throws com.scalar.db.analytics.labs.query.QueryEngineException if the engine cannot start or
   *     cannot attach a data source
   */
  public synchronized DuckDbQueryEngine startQueryEngine(String catalogName) {
    if (queryEngine != null) {
      throw new IllegalStateException("A Query Engine is already running");
    }
    var attachments = sourceAttachments(catalogName);
    if (!attachments.isEmpty()) {
      // The extension uses the library initialized in the process when it loads, so the runtime
      // initializes it first and keeps the settings and the shutdown.
      try {
        connectorLibrary();
      } catch (ConnectorException | IOException e) {
        throw new QueryEngineException(
            UUID.randomUUID().toString(),
            new QueryError.InitializationFailed("The connector API library could not be loaded."));
      }
    }
    var engine = new DuckDbQueryEngine(nativeCatalogProvider(physicalCatalog), attachments);
    queryEngine = engine;
    return engine;
  }

  /**
   * Closes the Query Engine, then shuts the connector API library down, then stops the catalog.
   *
   * <p>Closing the engine unregisters the catalog from the extension before the catalog stops, and
   * closes the DuckDB databases that use the library before it shuts down.
   *
   * @throws ApplicationFailure with code {@code SHUTDOWN_FAILED} if the connector API library could
   *     not shut down
   */
  @Override
  public synchronized void close() {
    RuntimeException failure = null;
    if (queryEngine != null) {
      try {
        queryEngine.close();
      } catch (RuntimeException e) {
        failure = e;
      }
    }
    if (connectorLibrary != null) {
      try {
        connectorLibrary.close();
      } catch (ConnectorException e) {
        failure =
            combine(
                failure,
                new ApplicationFailure(
                    "SHUTDOWN_FAILED", "The connector API library could not shut down."));
      } catch (RuntimeException e) {
        failure = combine(failure, e);
      }
    }
    try {
      physicalCatalog.close();
    } catch (RuntimeException e) {
      failure = combine(failure, e);
    }
    if (failure != null) {
      throw failure;
    }
  }

  private static RuntimeException combine(@Nullable RuntimeException first, RuntimeException next) {
    if (first == null) {
      return next;
    }
    first.addSuppressed(next);
    return first;
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
      attachments.add(ConnectorPath.attachment(dataSource.name(), connection));
    }
    return attachments;
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
