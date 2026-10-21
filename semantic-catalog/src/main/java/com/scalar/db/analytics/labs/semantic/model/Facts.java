/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.fasterxml.jackson.databind.JsonNode;

/** Computes Fact Address and Fact URNs, so Generators can reference Facts they propose. */
public final class Facts {
  private Facts() {}

  public static String address(String domain, String element, String predicate, Scope scope) {
    return Urns.factAddress(domain, element, predicate, scope);
  }

  public static String fact(
      String domain, String element, String predicate, Scope scope, JsonNode value) {
    return Urns.fact(domain, address(domain, element, predicate, scope), NormalForms.of(value));
  }
}
