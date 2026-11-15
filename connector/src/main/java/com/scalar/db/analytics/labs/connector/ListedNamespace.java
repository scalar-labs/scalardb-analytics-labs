/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.connector;

import java.util.List;

/** One namespace list entry. */
public sealed interface ListedNamespace {
  record Namespace(List<String> names) implements ListedNamespace {
    public Namespace {
      names = List.copyOf(names);
    }
  }

  /** A range whose list could not be read; it is not known to be empty. */
  record Unreadable(List<String> scope, String reason) implements ListedNamespace {
    public Unreadable {
      scope = List.copyOf(scope);
    }
  }
}
