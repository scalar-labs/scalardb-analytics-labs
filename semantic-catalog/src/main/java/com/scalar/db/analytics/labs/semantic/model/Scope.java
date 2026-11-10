/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * An applicability scope: which questions, objects, conditions, and periods a piece of knowledge
 * applies to, as a natural-language description paired with the URNs of the entities it mentions
 * (see {@code docs/semantic-catalog/record-model.md}, "Identity and URNs").
 *
 * <p>An unrestricted statement has the {@linkplain #EMPTY empty scope}, with neither. Values that
 * hold at the same time in different applicability scopes belong to different Fact Addresses and do
 * not conflict.
 *
 * @param description the natural-language description, absent for the empty scope
 * @param entities the URNs of the entities the description mentions
 */
public record Scope(@Nullable String description, List<Ref> entities) {
  /** The scope of an unrestricted statement. */
  public static final Scope EMPTY = new Scope(null, List.of());

  public Scope {
    entities = List.copyOf(entities);
    if (description == null && !entities.isEmpty()) {
      throw new IllegalArgumentException("A scope that mentions entities has a description");
    }
    if (description != null && description.isBlank()) {
      throw new IllegalArgumentException("A scope description is not blank");
    }
  }

  /** A restricted scope. */
  public static Scope described(String description, List<Ref> entities) {
    return new Scope(description, entities);
  }

  /**
   * Whether this is the empty scope. A Fact Address with the empty scope is found by its element
   * and predicate; one with a description is the same as another only when the proposer points to
   * it.
   */
  @JsonIgnore
  public boolean isEmpty() {
    return description == null;
  }

  /** The same scope with each entity reference replaced. */
  public Scope mapRefs(UnaryOperator<Ref> mapping) {
    return new Scope(description, entities.stream().map(mapping).toList());
  }
}
