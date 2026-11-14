/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.model.LogPosition;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.InputKind;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One row of the materialized records of one domain: what is derived from the log and kept for
 * reading without replaying it (see {@code docs/semantic-catalog/storage.md}, "Tables").
 *
 * <p>Each row type corresponds to one materialized table. Every row has a {@link RowKey}, unique
 * within its table and domain, by which a projection tells which rows a commit inserted or changed,
 * and the replay check compares rows as values.
 *
 * <p>Most rows never change once written. A current Fact Record is rewritten when a commit changes
 * it, a presentable period or an address selection is closed by the commit that ends it, and a
 * dependency row changes its flags or is deleted when an acceptance or a confirmation changes the
 * dependency sets.
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
   * @param technicalReference the canonical form of the technical reference that identifies a
   *     Dataset or a Field, by which a Field is checked to be a column of its Dataset's table;
   *     absent for other kinds
   * @param createdCommit the commit that created the element
   * @param edge the identity of an edge; absent for a node
   */
  record ElementRow(
      String element,
      ElementKind kind,
      @Nullable String parent,
      @Nullable String technicalReference,
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
   * @param endA the canonical form of the end the edge starts from; for an undirected edge, the end
   *     whose canonical form sorts first
   * @param endB the canonical form of the end the edge goes to; for an undirected edge, the end
   *     whose canonical form sorts last
   * @param identityKey the canonical identity key of the identifying part
   * @param identifyingPart the edge identity as stated, including its identifying part
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
   * An Input Resource, used to look one up by table identifier.
   *
   * @param tableIdentifier the Physical Catalog's identifier of the table whose physical schema it
   *     is
   * @param createdCommit the commit that created the Input Resource
   */
  record InputResourceRow(
      String inputResource, InputKind kind, @Nullable String tableIdentifier, long createdCommit)
      implements MaterializedRow {
    @Override
    public RowKey key() {
      return RowKey.of("input_resources", inputResource);
    }
  }

  /**
   * How a Fact is treated after its latest event: what review lists and status reads filter on,
   * kept as columns of the current Fact Record.
   *
   * @param awaitingConsent whether the latest policy evaluation requested consent that no answer
   *     has given yet
   * @param openRetirementProposal the log record of the open retirement proposal against a
   *     confirmed Fact
   * @param unknownAnswerPending whether an unknown answer was given and no new dependency reference
   *     arrived since
   */
  record Treatment(
      FactState state,
      boolean awaitingConsent,
      @Nullable String openRetirementProposal,
      boolean unknownAnswerPending) {}

  /**
   * The current Fact Record of one Fact, written after every commit that changes it. It always
   * equals the result of folding the Fact's events, which the replay check verifies, so a write
   * validates against it without folding.
   *
   * @param latestEvent the log position of the latest event, which also names the current Fact
   *     Revision
   * @param record the {@link FactRecord.Stored} form of the record, as stored JSON
   */
  record FactRecordRow(
      String fact,
      String factAddress,
      LogPosition latestEvent,
      Treatment treatment,
      JsonNode record)
      implements MaterializedRow {
    @Override
    public RowKey key() {
      return RowKey.of("fact_records", fact);
    }
  }

  /**
   * A presentable period of a confirmed Fact: from the commit at which it became {@code confirmed}
   * until the commit at which it became {@code retired}. A Fact retired and confirmed again has
   * several.
   *
   * @param retiredCommit the commit of the retirement that ended the period; absent while the Fact
   *     is still presentable
   */
  record PresentablePeriodRow(String fact, long confirmedCommit, @Nullable Long retiredCommit)
      implements MaterializedRow {
    @Override
    public RowKey key() {
      return RowKey.of("presentable_periods", fact, confirmedCommit);
    }
  }

  /**
   * One dependency reference of a Fact, used to find the Facts a cascade or a new observation
   * affects. A Fact has a row for each reference that is in effect or was in effect at its latest
   * confirmation; a row that is neither is deleted.
   *
   * @param dependedRecord the Fact Revision or Input Observation depended on
   * @param dependedFact the Fact of a Fact Revision depended on
   * @param dependedInputResource the Input Resource of an Input Observation depended on
   * @param inEffect whether the reference is among those the latest acceptance stated
   * @param forConfirmation whether the reference is among those at the latest confirmation, the
   *     ones that count for selection and cascading
   */
  record DependencyRow(
      String dependentFact,
      String dependedRecord,
      @Nullable String dependedFact,
      @Nullable String dependedInputResource,
      boolean inEffect,
      boolean forConfirmation)
      implements MaterializedRow {
    public DependencyRow {
      if ((dependedFact == null) == (dependedInputResource == null)) {
        throw new IllegalArgumentException("A dependency is on either a Fact or an input");
      }
      if (!inEffect && !forConfirmation) {
        throw new IllegalArgumentException(
            "A dependency row is in effect, or was at the latest confirmation");
      }
    }

    @Override
    public RowKey key() {
      return RowKey.of("dependencies", dependentFact, dependedRecord);
    }
  }

  /**
   * The selection at one Fact Address over a commit range: the selected Fact and the eligible
   * Facts, derived by {@link SelectionRule}. A row is added when the selection changes, and the row
   * it follows is closed at that commit.
   *
   * @param fromCommit the first commit at which the row holds
   * @param toCommit the first commit at which it no longer holds; absent while it still does
   * @param selectedFact the selected Fact; absent when none is selected, as in a conflict with no
   *     previous selection
   * @param eligibleFacts the eligible Facts, sorted by URN; two or more mean a conflict
   */
  record AddressSelectionRow(
      String factAddress,
      long fromCommit,
      @Nullable Long toCommit,
      @Nullable String selectedFact,
      List<String> eligibleFacts)
      implements MaterializedRow {
    public AddressSelectionRow {
      eligibleFacts = List.copyOf(eligibleFacts);
    }

    @Override
    public RowKey key() {
      return RowKey.of("address_selections", factAddress, fromCommit);
    }

    /** Whether two or more Facts are eligible: a conflict. */
    public boolean conflict() {
      return eligibleFacts.size() > 1;
    }

    AddressSelectionRow closedAt(long commit) {
      return new AddressSelectionRow(factAddress, fromCommit, commit, selectedFact, eligibleFacts);
    }
  }
}
