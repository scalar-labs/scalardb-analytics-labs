/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.semantic;

import com.scalar.db.analytics.labs.application.ApplicationFailure;
import com.scalar.db.analytics.labs.application.ApplicationRuntime;
import com.scalar.db.analytics.labs.application.workspace.SemanticCatalogs;
import com.scalar.db.analytics.labs.application.workspace.Workspace;
import com.scalar.db.analytics.labs.physical.PhysicalCatalogException;
import com.scalar.db.analytics.labs.semantic.BuildTask;
import com.scalar.db.analytics.labs.semantic.Outputs.ObservationRegistered;
import com.scalar.db.analytics.labs.semantic.Outputs.TaskRun;
import com.scalar.db.analytics.labs.semantic.SemanticCatalog;
import com.scalar.db.analytics.labs.semantic.SemanticCatalogException;
import com.scalar.db.analytics.labs.semantic.generator.Generators;
import com.scalar.db.analytics.labs.semantic.llm.CommandLanguageModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * The application service layer's hold on the Semantic Catalog for one command: the workspace's
 * runtime, its Semantic Catalog domain, and the capabilities the application provides to it — table
 * metadata retrieval, construction-run SQL, the language model, and authority checks.
 */
public final class SemanticSession implements AutoCloseable {
  private final Workspace workspace;
  private final ApplicationRuntime runtime;
  private final SemanticCatalog catalog;

  private SemanticSession(
      Workspace workspace, ApplicationRuntime runtime, SemanticCatalog catalog) {
    this.workspace = workspace;
    this.runtime = runtime;
    this.catalog = catalog;
  }

  public static SemanticSession open(Workspace workspace) {
    var runtime = workspace.openRuntime();
    try {
      var model =
          CommandLanguageModel.fromEnvironment(
              workspace.environment(), workspace.directory().resolve("llm-log"));
      var configuration =
          new SemanticCatalog.Configuration(
              Generators.standard(model),
              new ConstructionSql(runtime, workspace.catalogName()),
              model);
      return new SemanticSession(
          workspace, runtime, SemanticCatalogs.open(workspace, configuration));
    } catch (IllegalArgumentException e) {
      runtime.close();
      throw new ApplicationFailure(
          "INVALID_REQUEST",
          java.util.Objects.requireNonNullElse(e.getMessage(), "Invalid configuration."));
    } catch (RuntimeException e) {
      runtime.close();
      throw e;
    }
  }

  /** Runs one use case against an open session, translating component failures. */
  public static <T> T execute(Workspace workspace, Function<SemanticSession, T> body) {
    try (var session = open(workspace)) {
      return body.apply(session);
    } catch (SemanticCatalogException e) {
      throw SemanticCatalogs.failure(e);
    } catch (PhysicalCatalogException e) {
      throw ApplicationFailure.from(e);
    }
  }

  public SemanticCatalog catalog() {
    return catalog;
  }

  /**
   * Checks that a principal may decide on candidates and returns the reference to the result. The
   * workspace names the semantics owners; there are no delegates yet.
   */
  public String authorize(String principal) {
    if (!workspace.semanticsOwners().contains(principal)) {
      throw new ApplicationFailure(
          "NO_AUTHORITY", "The principal is not a semantics owner of this workspace.");
    }
    return "authz:" + principal + ":" + UUID.randomUUID();
  }

  /**
   * Retrieves table metadata for every registered table, or one source's, and registers each as an
   * observation (02-catalog-contract.md 3.1). Tables whose version did not change are passed too
   * and are not recorded twice.
   */
  public List<ObservationRegistered> refetch(String principal, Optional<String> source) {
    var registered = new ArrayList<ObservationRegistered>();
    for (var table :
        SourceMetadataReader.read(runtime.physicalCatalog(), workspace.catalogName(), source)) {
      registered.add(
          catalog.registerTableMetadata(
              principal, null, table.resourceIdentifier(), table.version(), table.metadata()));
    }
    return registered;
  }

  /**
   * Runs created build tasks, oldest first, until none is left or the limit is reached. The
   * application decides when and how many build tasks run.
   */
  public List<TaskRun> runCreated(int limit) {
    var runs = new ArrayList<TaskRun>();
    while (runs.size() < limit) {
      var created = catalog.createdTasks();
      if (created.isEmpty()) {
        break;
      }
      runs.add(catalog.runTask(created.getFirst().id()));
    }
    return runs;
  }

  /**
   * Runs build tasks as they are created, by this or another process, until none has been created
   * for the idle time. Another process may answer while a task runs; the tasks its answers create
   * are picked up on the next poll.
   *
   * @param listener receives each run as it finishes
   */
  public List<TaskRun> watch(
      java.time.Duration idle,
      java.time.Duration poll,
      java.util.function.Consumer<TaskRun> listener) {
    var runs = new ArrayList<TaskRun>();
    var lastWork = java.time.Instant.now();
    while (true) {
      var created = catalog.createdTasks();
      if (!created.isEmpty()) {
        try {
          var run = catalog.runTask(created.getFirst().id());
          runs.add(run);
          listener.accept(run);
        } catch (SemanticCatalogException e) {
          // Another watching process claimed the task first.
          if (e.kind() != SemanticCatalogException.Kind.NOT_ACCEPTED_IN_STATE) {
            throw e;
          }
        }
        lastWork = java.time.Instant.now();
        continue;
      }
      if (java.time.Duration.between(lastWork, java.time.Instant.now()).compareTo(idle) >= 0) {
        return runs;
      }
      try {
        Thread.sleep(poll);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return runs;
      }
    }
  }

  public List<BuildTask> remainingTasks() {
    return catalog.createdTasks();
  }

  @Override
  public void close() {
    try {
      catalog.close();
    } finally {
      runtime.close();
    }
  }
}
