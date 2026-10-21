/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

/**
 * An error from the Semantic Catalog public API. When an operation returns an error, nothing was
 * written to the log.
 */
public final class SemanticCatalogException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final Kind kind;

  public SemanticCatalogException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public SemanticCatalogException(Kind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }

  /** The error kinds of section 2.7 of the public API. */
  public enum Kind {
    INVALID_INPUT,
    TARGET_NOT_FOUND,
    POINT_OUT_OF_RANGE,
    NO_AUTHORITY_CHECK,
    NOT_ACCEPTED_IN_STATE,
    ACQUISITION_CONDITIONS_NOT_MET,
    STORAGE_FAILURE
  }

  static SemanticCatalogException invalid(String message) {
    return new SemanticCatalogException(Kind.INVALID_INPUT, message);
  }

  static SemanticCatalogException notFound(String message) {
    return new SemanticCatalogException(Kind.TARGET_NOT_FOUND, message);
  }

  static SemanticCatalogException notAccepted(String message) {
    return new SemanticCatalogException(Kind.NOT_ACCEPTED_IN_STATE, message);
  }
}
