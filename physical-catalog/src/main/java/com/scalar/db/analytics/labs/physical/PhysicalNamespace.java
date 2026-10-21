/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.physical;

import java.util.List;

/** A source namespace recorded by the Physical Catalog. */
public record PhysicalNamespace(String dataSourceName, List<String> names) {
  public PhysicalNamespace {
    names = List.copyOf(names);
  }
}
