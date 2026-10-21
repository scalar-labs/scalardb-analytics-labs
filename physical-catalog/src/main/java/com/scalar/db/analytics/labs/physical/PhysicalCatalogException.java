/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.physical;

/**
 * Reports a Physical Catalog operation that could not be completed.
 *
 * <p>The exception never retains an upstream cause, because upstream exception messages can contain
 * provider payloads with credentials.
 */
public final class PhysicalCatalogException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final PhysicalCatalogError error;

  public PhysicalCatalogException(PhysicalCatalogError error) {
    super(error.message());
    this.error = error;
  }

  public PhysicalCatalogError error() {
    return error;
  }
}
