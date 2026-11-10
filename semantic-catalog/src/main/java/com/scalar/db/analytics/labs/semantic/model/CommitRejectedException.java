/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

/**
 * A commit request was refused because one of its records failed validation, such as a reference to
 * a record that does not exist. Nothing of the commit was written.
 */
public final class CommitRejectedException extends SemanticCatalogException {
  private static final long serialVersionUID = 1L;

  public CommitRejectedException(String message) {
    super(message);
  }
}
