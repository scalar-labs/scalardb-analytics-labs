/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.projection;

import com.scalar.db.analytics.labs.semantic.model.LogPosition;
import com.scalar.db.analytics.labs.semantic.model.LogRecord.FactEvent;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.AddressSelectionRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.DependencyRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.ElementRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.FactAddressRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.FactRecordRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.FactRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.InputResourceRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.PresentablePeriodRow;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Where a {@link Projection} loads the materialized records it does not hold yet: the store, read
 * in the writing transaction's snapshot, or nothing at all when the log is replayed from empty.
 *
 * <p>Each method returns what the source holds for one record, or empty when it holds nothing.
 */
public interface ProjectionSource {
  /** The source of a projection that starts empty and is built only from the records applied. */
  ProjectionSource EMPTY =
      new ProjectionSource() {
        @Override
        public Optional<LoadedFact> fact(String fact) {
          return Optional.empty();
        }

        @Override
        public Optional<LoadedAddress> address(String factAddress) {
          return Optional.empty();
        }

        @Override
        public Optional<ElementRow> element(String element) {
          return Optional.empty();
        }

        @Override
        public Optional<InputResourceRow> inputResource(String inputResource) {
          return Optional.empty();
        }

        @Override
        public Optional<String> observationResource(String observation) {
          return Optional.empty();
        }

        @Override
        public Optional<String> latestObservation(String inputResource) {
          return Optional.empty();
        }

        @Override
        public boolean isPolicyVersion(String logRecord) {
          return false;
        }

        @Override
        public Optional<String> factAddress(String fact) {
          return Optional.empty();
        }

        @Override
        public Optional<FactEvent> event(String fact, LogPosition at) {
          return Optional.empty();
        }

        @Override
        public Set<String> dependents(String fact) {
          return Set.of();
        }

        @Override
        public Set<String> confirmedDependents(String fact) {
          return Set.of();
        }

        @Override
        public List<SelectionRule.Fact> selectionFacts(String factAddress) {
          return List.of();
        }
      };

  /**
   * A Fact with what a projection needs about it.
   *
   * @param record its current Fact Record; every written Fact has one, because a Fact is accepted
   *     in the commit that creates it
   * @param periods its presentable periods, oldest first
   * @param dependencies its dependency rows
   */
  record LoadedFact(
      FactRow row,
      FactRecordRow record,
      List<PresentablePeriodRow> periods,
      List<DependencyRow> dependencies) {}

  /**
   * A Fact Address with what a projection needs about it.
   *
   * @param facts the URNs of the Facts at the Fact Address
   * @param resolutions its conflict resolutions, in log order
   * @param currentSelection the open row of its selection history, if any
   */
  record LoadedAddress(
      FactAddressRow row,
      List<String> facts,
      List<SelectionRule.Resolution> resolutions,
      Optional<AddressSelectionRow> currentSelection) {}

  Optional<LoadedFact> fact(String fact);

  Optional<LoadedAddress> address(String factAddress);

  Optional<ElementRow> element(String element);

  Optional<InputResourceRow> inputResource(String inputResource);

  /** The Input Resource an Input Observation observed. */
  Optional<String> observationResource(String observation);

  /** The latest Input Observation of an Input Resource. */
  Optional<String> latestObservation(String inputResource);

  /** Whether a log record is a user policy version. */
  boolean isPolicyVersion(String logRecord);

  /** The Fact Address of a Fact, without loading its records. */
  Optional<String> factAddress(String fact);

  /** A Fact's event at a log position in the log. */
  Optional<FactEvent> event(String fact, LogPosition at);

  /** The Facts that have a dependency reference to a Fact. */
  Set<String> dependents(String fact);

  /**
   * The Facts that are {@code confirmed} and were confirmed with a dependency reference to a Fact.
   */
  Set<String> confirmedDependents(String fact);

  /**
   * What the selection rule needs about every Fact at a Fact Address, read without loading the
   * Facts' records: since when each is presentable, and the Facts its latest confirmation depended
   * on.
   */
  List<SelectionRule.Fact> selectionFacts(String factAddress);
}
