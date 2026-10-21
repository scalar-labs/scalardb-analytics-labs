/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application;

import com.scalar.db.analytics.labs.physical.PhysicalCatalogError;
import com.scalar.db.analytics.labs.physical.PhysicalCatalogException;

/** A classified application-adapter failure safe to expose to CLI callers. */
public final class ApplicationFailure extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final String code;

  public ApplicationFailure(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }

  /** Converts a Physical Catalog failure into a stable application failure. */
  public static ApplicationFailure from(PhysicalCatalogException exception) {
    PhysicalCatalogError error = exception.error();
    var code =
        switch (error) {
          case PhysicalCatalogError.InitializationFailed ignored -> "INITIALIZATION_FAILED";
          case PhysicalCatalogError.InvalidRequest ignored -> "INVALID_REQUEST";
          case PhysicalCatalogError.AlreadyExists ignored -> "ALREADY_EXISTS";
          case PhysicalCatalogError.DataSourceUnreachable ignored -> "DATA_SOURCE_UNREACHABLE";
          case PhysicalCatalogError.Unavailable ignored -> "UNAVAILABLE";
          case PhysicalCatalogError.OperationFailed ignored -> "OPERATION_FAILED";
          case PhysicalCatalogError.ShutdownFailed ignored -> "SHUTDOWN_FAILED";
        };
    return new ApplicationFailure(code, error.message());
  }
}
