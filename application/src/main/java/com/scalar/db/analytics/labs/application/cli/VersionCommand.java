/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalar.db.analytics.labs.application.Main;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(name = "version", description = "Prints machine-readable product version information.")
final class VersionCommand implements Callable<Integer> {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Spec private @Nullable CommandSpec commandSpec;

  @Override
  public Integer call() throws JsonProcessingException {
    var version = Main.class.getPackage().getImplementationVersion();
    var versionInfo =
        new VersionInfo(
            "scalardb-analytics/version", "1", version == null ? "development" : version);
    Objects.requireNonNull(commandSpec, "commandSpec")
        .commandLine()
        .getOut()
        .println(OBJECT_MAPPER.writeValueAsString(versionInfo));
    return 0;
  }

  private record VersionInfo(String schema, String schemaVersion, String productVersion) {}
}
