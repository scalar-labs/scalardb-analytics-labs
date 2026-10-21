/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import java.util.List;
import java.util.Objects;

/**
 * An applicability scope: a natural-language description and the URNs of the entities it mentions.
 * An empty scope does not limit where a statement applies.
 *
 * <p>Provisional normalization: the description is trimmed and its whitespace collapsed, and the
 * URN set is sorted and deduplicated. Differently worded descriptions stay different scopes.
 */
public record Scope(String description, List<String> entities) {
  public static final Scope UNRESTRICTED = new Scope("", List.of());

  public Scope {
    description = Objects.requireNonNullElse(description, "").strip().replaceAll("\\s+", " ");
    entities =
        Objects.requireNonNullElse(entities, List.<String>of()).stream()
            .distinct()
            .sorted()
            .toList();
  }

  public boolean unrestricted() {
    return description.isEmpty() && entities.isEmpty();
  }
}
