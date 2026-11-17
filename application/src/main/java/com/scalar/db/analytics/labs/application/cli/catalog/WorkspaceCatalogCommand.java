/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.cli.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.scalar.db.analytics.labs.application.ApplicationFailure;
import com.scalar.db.analytics.labs.application.cli.CliOutput;
import com.scalar.db.analytics.labs.application.cli.WorkspaceOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** Holds the workspace and classified-output contract shared by Physical Catalog commands. */
abstract class WorkspaceCatalogCommand {
  final Map<String, String> environment;

  @Mixin WorkspaceOption workspaceOption = new WorkspaceOption();

  @Spec @Nullable CommandSpec commandSpec;

  WorkspaceCatalogCommand(Map<String, String> environment) {
    this.environment = environment;
  }

  final Path workspaceDirectory() {
    return workspaceOption.directory(environment);
  }

  final CommandSpec commandSpec() {
    return Objects.requireNonNull(commandSpec, "commandSpec");
  }

  final int failure(ApplicationFailure failure) throws JsonProcessingException {
    return CliOutput.writeFailure(commandSpec().commandLine().getOut(), failure);
  }
}
