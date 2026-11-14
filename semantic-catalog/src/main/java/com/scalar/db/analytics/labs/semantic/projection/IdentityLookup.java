/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.projection;

import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.EdgeIdentity;
import java.util.Optional;

/**
 * Finds the existing record a proposed record is the same as, by the identity rules of {@code
 * docs/semantic-catalog/record-model.md} ("Identity and URNs"), in the writing transaction's
 * snapshot.
 *
 * <p>Edges, empty-scope Fact Addresses, closed-range Facts, and Input Resources are also kept
 * unique by constraints in the store. Datasets and Fields cannot be: their identity depends on the
 * state of technical-reference Facts, so only this search keeps it. Either way, a concurrent writer
 * that created the same record first committed a commit this writer collides with, and the retry
 * finds that record.
 *
 * <p>Each method returns the URN of the existing record, or empty when there is none.
 */
public interface IdentityLookup {
  /**
   * The Dataset or Field of a kind whose technical-reference Fact, at the empty-scope Fact Address,
   * has an identity key.
   *
   * @param identityKey the canonical identity key of the technical reference
   */
  Optional<String> technicalElement(ElementKind kind, String identityKey);

  /** The edge of a kind with the same ends, direction, and identifying part. */
  Optional<String> edge(ElementKind kind, EdgeIdentity identity);

  /** The empty-scope Fact Address of an element and a predicate that is not free-description. */
  Optional<String> emptyScopeAddress(String element, String predicate);

  /**
   * The closed-range Fact at a Fact Address with an identity key.
   *
   * @param identityKey the canonical identity key
   */
  Optional<String> closedRangeFact(String factAddress, String identityKey);

  /** The Input Resource of a table's physical schema, by the Physical Catalog's identifier. */
  Optional<String> inputResourceByTable(String tableIdentifier);
}
