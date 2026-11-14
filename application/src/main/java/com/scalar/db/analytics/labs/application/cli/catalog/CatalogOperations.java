/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.cli.catalog;

import com.scalar.db.analytics.labs.application.ApplicationFailure;
import com.scalar.db.analytics.labs.application.workspace.Workspace;
import com.scalar.db.analytics.labs.physical.PhysicalCatalog;
import com.scalar.db.analytics.labs.physical.PhysicalCatalogException;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.BiFunction;

/** Opens the workspace-owned catalog for one standalone command invocation. */
final class CatalogOperations {
  private CatalogOperations() {}

  static <T> T execute(
      Path workspaceDirectory,
      Map<String, String> environment,
      BiFunction<PhysicalCatalog, String, T> operation) {
    var workspace = Workspace.load(workspaceDirectory, environment);
    try (var runtime = workspace.openRuntime()) {
      return operation.apply(runtime.physicalCatalog(), workspace.catalogName());
    } catch (PhysicalCatalogException e) {
      throw ApplicationFailure.from(e);
    }
  }

  /** As {@link #execute}, for an operation that also needs the workspace's other settings. */
  static <T> T executeInWorkspace(
      Path workspaceDirectory,
      Map<String, String> environment,
      BiFunction<Workspace, PhysicalCatalog, T> operation) {
    var workspace = Workspace.load(workspaceDirectory, environment);
    try (var runtime = workspace.openRuntime()) {
      return operation.apply(workspace, runtime.physicalCatalog());
    } catch (PhysicalCatalogException e) {
      throw ApplicationFailure.from(e);
    }
  }
}
