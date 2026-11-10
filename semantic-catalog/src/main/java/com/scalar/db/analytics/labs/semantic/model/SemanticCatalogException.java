/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

/**
 * A Semantic Catalog operation failed. The message states the reason in terms of the record model,
 * such as an unknown domain or a store that is not PostgreSQL.
 *
 * <p>A failed operation writes nothing: it runs in one transaction, which is rolled back.
 */
public class SemanticCatalogException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public SemanticCatalogException(String message) {
    super(message);
  }

  public SemanticCatalogException(String message, Throwable cause) {
    super(message, cause);
  }
}
