/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One row of the materialized records of one domain: what is derived from the log and kept for
 * reading without replaying it (see {@code docs/semantic-catalog/storage.md}, "Tables").
 *
 * <p>Each row type corresponds to one materialized table. Every row has a {@link RowKey}, unique
 * within its table and domain, by which a projection tells which rows a commit inserted or changed,
 * and the replay check compares rows as values.
 */
public sealed interface MaterializedRow {
  RowKey key();

  /**
   * A row's identity: its table and its primary key values after the domain.
   *
   * @param table the name of the materialized table
   */
  record RowKey(String table, List<Object> values) {
    public RowKey {
      values = List.copyOf(values);
    }

    static RowKey of(String table, Object... values) {
      return new RowKey(table, List.of(values));
    }
  }

  /**
   * An element, used to match edges by identity and to find the Fields of a Dataset.
   *
   * @param parent the Dataset of a Field; absent for other kinds
   * @param createdCommit the commit that created the element
   * @param edge the identity of an edge; absent for a node
   */
  record ElementRow(
      String element,
      ElementKind kind,
      @Nullable String parent,
      long createdCommit,
      @Nullable EdgeIdentity edge)
      implements MaterializedRow {
    @Override
    public RowKey key() {
      return RowKey.of("elements", element);
    }
  }

  /**
   * An edge's identity: kind aside, what makes two edges the same.
   *
   * @param endA the end the edge starts from; for an undirected edge, the end whose URN sorts first
   * @param endB the end the edge goes to; for an undirected edge, the end whose URN sorts last
   * @param identityKey the canonical identity key of the identifying part
   * @param identifyingPart the identifying part as stated
   */
  record EdgeIdentity(
      String endA, String endB, boolean directed, String identityKey, JsonNode identifyingPart) {}

  /**
   * A Fact Address, used to find empty-scope Fact Addresses by element and predicate.
   *
   * @param scopeDescription the applicability scope's description; absent for the empty scope
   * @param scopeEntities the URNs of the entities the applicability scope mentions
   * @param predicateDefinition the definition of a free-description predicate
   * @param createdCommit the commit that created the Fact Address
   */
  record FactAddressRow(
      String factAddress,
      String element,
      String predicate,
      @Nullable String scopeDescription,
      List<String> scopeEntities,
      @Nullable String predicateDefinition,
      long createdCommit)
      implements MaterializedRow {
    public FactAddressRow {
      scopeEntities = List.copyOf(scopeEntities);
    }

    @Override
    public RowKey key() {
      return RowKey.of("fact_addresses", factAddress);
    }

    /**
     * Whether the Fact Address is found by element and predicate alone: its scope is empty and its
     * predicate is not free-description.
     */
    public boolean matchable() {
      return scopeDescription == null && predicateDefinition == null;
    }
  }

  /**
   * A Fact, used to match closed-range Facts and to find Datasets and Fields by technical
   * reference.
   *
   * @param identityKey the canonical identity key of a closed-range Fact; absent otherwise
   * @param createdCommit the commit that created the Fact
   */
  record FactRow(String fact, String factAddress, @Nullable String identityKey, long createdCommit)
      implements MaterializedRow {
    @Override
    public RowKey key() {
      return RowKey.of("facts", fact);
    }
  }

  /**
   * An Input Resource, used to look one up by table identifier or by question.
   *
   * @param tableIdentifier the Physical Catalog's identifier of the table whose physical schema it
   *     is
   * @param question the URN of the question at which missing knowledge was found
   * @param createdCommit the commit that created the Input Resource
   */
  record InputResourceRow(
      String inputResource,
      String kind,
      @Nullable String tableIdentifier,
      @Nullable String question,
      long createdCommit)
      implements MaterializedRow {
    @Override
    public RowKey key() {
      return RowKey.of("input_resources", inputResource);
    }
  }
}
