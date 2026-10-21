/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;

/**
 * What identifies an element, carried by the acceptance of its first existence Fact.
 *
 * @param parent the higher-level element, such as a Field's Dataset
 * @param label a readable name, not part of identity
 * @param from an edge's source endpoint, or the first endpoint of an undirected edge
 * @param to an edge's target endpoint
 * @param identifying an edge's identifying part, such as a join's condition
 */
public record ElementDescriptor(
    String urn,
    ElementKind kind,
    @Nullable String parent,
    String label,
    @Nullable String from,
    @Nullable String to,
    boolean directed,
    @Nullable JsonNode identifying) {

  public static ElementDescriptor node(
      String urn, ElementKind kind, @Nullable String parent, String label) {
    return new ElementDescriptor(urn, kind, parent, label, null, null, false, null);
  }

  public static ElementDescriptor edge(
      String domain,
      ElementKind kind,
      String from,
      String to,
      boolean directed,
      JsonNode identifying,
      String label) {
    var urn = Urns.edge(domain, kind, from, to, directed, identifying);
    if (!directed && from.compareTo(to) > 0) {
      var swap = from;
      from = to;
      to = swap;
    }
    return new ElementDescriptor(urn, kind, null, label, from, to, directed, identifying);
  }

  public JsonNode identifyingOrEmpty() {
    return identifying == null ? Json.object() : identifying;
  }
}
