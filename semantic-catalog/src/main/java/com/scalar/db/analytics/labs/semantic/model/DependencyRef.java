/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** A dependency reference: to a Fact Revision, or to an Input Observation through a selector. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = DependencyRef.OnFact.class, name = "fact"),
  @JsonSubTypes.Type(value = DependencyRef.OnObservation.class, name = "observation")
})
public sealed interface DependencyRef {
  /** A dependency on one version of another Fact. */
  record OnFact(String factRevision) implements DependencyRef {
    public String fact() {
      return Urns.factOfRevision(factRevision);
    }
  }

  /**
   * A dependency on the range of an Input Observation a selector points to.
   *
   * @param digest the digest of that range, compared to tell whether a re-observation is new
   */
  record OnObservation(
      String inputResource, String inputObservation, String selector, String digest)
      implements DependencyRef {}
}
