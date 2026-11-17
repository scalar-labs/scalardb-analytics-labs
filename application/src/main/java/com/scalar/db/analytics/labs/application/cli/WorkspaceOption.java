/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.cli;

import com.scalar.db.analytics.labs.application.ApplicationFailure;
import java.nio.file.Path;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Option;

/**
 * The {@code --workspace} option every workspace command mixes in. When the option is omitted, the
 * workspace comes from {@value #ENVIRONMENT_VARIABLE}, so an agent host can fix it once for a
 * session.
 */
public final class WorkspaceOption {
  /** The environment variable that names the workspace when {@code --workspace} is omitted. */
  public static final String ENVIRONMENT_VARIABLE = "SCALARDB_ANALYTICS_WORKSPACE";

  @Option(
      names = "--workspace",
      paramLabel = "DIR",
      description = "The workspace directory. Defaults to $" + ENVIRONMENT_VARIABLE + ".")
  private @Nullable Path directory;

  /**
   * Returns the workspace directory: the option when given, else the environment variable.
   *
   * @throws ApplicationFailure {@code INVALID_WORKSPACE} when neither names a workspace
   */
  public Path directory(Map<String, String> environment) {
    if (directory != null) {
      return directory;
    }
    var value = environment.get(ENVIRONMENT_VARIABLE);
    if (value == null || value.isBlank()) {
      throw new ApplicationFailure(
          "INVALID_WORKSPACE",
          "No workspace was given: pass --workspace or set " + ENVIRONMENT_VARIABLE + ".");
    }
    return Path.of(value);
  }
}
