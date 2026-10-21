/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.workspace;

import com.scalar.db.analytics.labs.application.ApplicationFailure;
import com.scalar.db.analytics.labs.semantic.SemanticCatalog;
import com.scalar.db.analytics.labs.semantic.SemanticCatalogException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Places the workspace's Semantic Catalog domain: one storage DuckDB file in the workspace
 * directory, readable and writable only by its owner. The domain is named after the workspace's
 * Physical Catalog catalog.
 */
public final class SemanticCatalogs {
  private SemanticCatalogs() {}

  /** Creates the domain and records its initial user policy version. */
  public static void create(Workspace workspace, String principal) {
    var file = workspace.semanticCatalogFile();
    try {
      SemanticCatalog.create(
              file, workspace.catalogName(), principal, SemanticCatalog.Configuration.READ_ONLY)
          .close();
    } catch (SemanticCatalogException e) {
      throw failure(e);
    }
    restrict(file);
  }

  public static SemanticCatalog open(
      Workspace workspace, SemanticCatalog.Configuration configuration) {
    if (!Files.isRegularFile(workspace.semanticCatalogFile())) {
      throw new ApplicationFailure(
          "SEMANTIC_CATALOG_NOT_INITIALIZED",
          "The workspace has no Semantic Catalog. Initialize a new workspace to create one.");
    }
    try {
      return SemanticCatalog.open(workspace.semanticCatalogFile(), configuration);
    } catch (SemanticCatalogException e) {
      throw failure(e);
    }
  }

  private static void restrict(Path file) {
    try {
      if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
      }
    } catch (IOException e) {
      throw new ApplicationFailure(
          "WORKSPACE_WRITE_FAILED", "Could not restrict the Semantic Catalog storage.");
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
