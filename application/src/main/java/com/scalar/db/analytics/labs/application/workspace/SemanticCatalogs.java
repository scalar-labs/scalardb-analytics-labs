/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.workspace;

import com.scalar.db.analytics.labs.application.ApplicationFailure;
import com.scalar.db.analytics.labs.semantic.SemanticCatalog;
import com.scalar.db.analytics.labs.semantic.SemanticCatalogException;

/**
 * Places the workspace's Semantic Catalog domain in the Physical Catalog's store database, which
 * must be PostgreSQL for now. The domain is named after the workspace's Physical Catalog catalog.
 */
public final class SemanticCatalogs {
  private SemanticCatalogs() {}

  /** Creates the domain and records its initial user policy version. */
  public static void create(Workspace workspace, String principal) {
    try {
      SemanticCatalog.create(
              workspace.semanticStorage(),
              workspace.catalogName(),
              principal,
              SemanticCatalog.Configuration.READ_ONLY)
          .close();
    } catch (SemanticCatalogException e) {
      throw failure(e);
    }
  }

  public static SemanticCatalog open(
      Workspace workspace, SemanticCatalog.Configuration configuration) {
    try {
      return SemanticCatalog.open(
          workspace.semanticStorage(), workspace.catalogName(), configuration);
    } catch (SemanticCatalogException e) {
      if (e.kind() == SemanticCatalogException.Kind.TARGET_NOT_FOUND) {
        throw new ApplicationFailure(
            "SEMANTIC_CATALOG_NOT_INITIALIZED",
            "The workspace has no Semantic Catalog. Initialize a new workspace to create one.");
      }
      throw failure(e);
    }
  }

  /** Converts a Semantic Catalog error into a stable application failure. */
  public static ApplicationFailure failure(SemanticCatalogException exception) {
    var code =
        switch (exception.kind()) {
          case INVALID_INPUT -> "INVALID_REQUEST";
          case TARGET_NOT_FOUND -> "NOT_FOUND";
          case POINT_OUT_OF_RANGE -> "POINT_OUT_OF_RANGE";
          case NO_AUTHORITY_CHECK -> "NO_AUTHORITY";
          case NOT_ACCEPTED_IN_STATE -> "NOT_ACCEPTED_IN_STATE";
          case ACQUISITION_CONDITIONS_NOT_MET -> "ACQUISITION_CONDITIONS_NOT_MET";
          case STORAGE_FAILURE -> "SEMANTIC_STORAGE_FAILED";
        };
    return new ApplicationFailure(
        code, java.util.Objects.requireNonNullElse(exception.getMessage(), code));
  }
}
