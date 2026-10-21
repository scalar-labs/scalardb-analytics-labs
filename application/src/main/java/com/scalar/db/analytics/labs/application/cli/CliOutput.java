/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalar.db.analytics.labs.application.ApplicationFailure;
import java.io.PrintWriter;
import java.util.Objects;
import java.util.UUID;

/** Writes versioned command output without exposing native diagnostics. */
public final class CliOutput {
  public static final int SUCCESS = 0;
  public static final int FAILURE = 1;
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private CliOutput() {}

  public static void write(PrintWriter writer, Object value) throws JsonProcessingException {
    writer.println(OBJECT_MAPPER.writeValueAsString(value));
  }

  public static int writeFailure(PrintWriter writer, ApplicationFailure failure)
      throws JsonProcessingException {
    return writeError(
        writer,
        UUID.randomUUID().toString(),
        failure.code(),
        Objects.requireNonNull(failure.getMessage()));
  }

  public static int writeError(PrintWriter writer, String executionId, String code, String message)
      throws JsonProcessingException {
    write(writer, new ErrorOutput("scalardb-analytics/error", "3", executionId, code, message));
    return FAILURE;
  }

  record ErrorOutput(
      String schema, String schemaVersion, String executionId, String code, String message) {}
}
