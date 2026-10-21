/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.physical;

import java.io.Serializable;

/**
 * A closed set of Physical Catalog failures.
 *
 * <p>Messages are fixed text owned by this boundary. Upstream diagnostics, which can contain
 * provider payloads and therefore credentials, never become part of an error.
 */
public sealed interface PhysicalCatalogError extends Serializable {
  String message();

  record InitializationFailed(String message) implements PhysicalCatalogError {}

  record InvalidRequest(String message) implements PhysicalCatalogError {}

  record AlreadyExists(String message) implements PhysicalCatalogError {}

  record DataSourceUnreachable(String message) implements PhysicalCatalogError {}

  record Unavailable(String message) implements PhysicalCatalogError {}

  record OperationFailed(String message) implements PhysicalCatalogError {}

  record ShutdownFailed(String message) implements PhysicalCatalogError {}
}
