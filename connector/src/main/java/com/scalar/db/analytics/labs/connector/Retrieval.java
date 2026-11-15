/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.connector;

import java.util.Optional;

/**
 * How a table metadata request was retrieved.
 *
 * @param adbcDriver the ADBC driver, absent with direct access
 */
public record Retrieval(
    Component connector, Optional<Component> adbcDriver, String libraryVersion) {

  /** A named, versioned component. */
  public record Component(String name, String version) {}
}
