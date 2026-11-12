/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * A dependency reference: a record, at the version read, whose changes require re-examining the
 * Fact that depends on it (see {@code docs/semantic-catalog/record-model.md}, "Validation results
 * and dependencies").
 *
 * <p>A dependency on a Fact is a <em>premise dependency</em> when that Fact is at another Fact
 * Address, and a <em>replacement dependency</em> when it is at the same one. The kind is not
 * stated: it follows from the Fact Addresses. Only premise dependencies make confirmation wait and
 * cascade rejection and retirement; replacement dependencies only decide selection.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "on")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Dependency.OnFact.class, name = "fact"),
  @JsonSubTypes.Type(value = Dependency.OnInput.class, name = "input")
})
public sealed interface Dependency {
  /**
   * A dependency on a Fact.
   *
   * @param revision the Fact Revision used
   */
  record OnFact(Ref fact, Ref revision) implements Dependency {}

  /**
   * A dependency on an input.
   *
   * @param inputResource the Input Resource, used to find what a new observation affects
   * @param observation the Input Observation actually used, used to reproduce past content
   */
  record OnInput(Ref inputResource, Ref observation) implements Dependency {}

  /** The record depended on, at its version: a Fact Revision or an Input Observation. */
  default Ref version() {
    return switch (this) {
      case OnFact onFact -> onFact.revision();
      case OnInput onInput -> onInput.observation();
    };
  }
}
