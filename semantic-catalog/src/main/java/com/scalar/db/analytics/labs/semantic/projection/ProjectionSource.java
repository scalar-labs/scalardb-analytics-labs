/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.projection;

import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.ElementRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.FactAddressRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.FactRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.InputResourceRow;
import java.util.List;
import java.util.Optional;

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
        public Optional<FactRow> fact(String fact) {
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
      };

  /**
   * A Fact Address with what a projection needs about it.
   *
   * @param facts the URNs of the Facts at the Fact Address
   */
  record LoadedAddress(FactAddressRow row, List<String> facts) {}

  Optional<FactRow> fact(String fact);

  Optional<LoadedAddress> address(String factAddress);

  Optional<ElementRow> element(String element);

  Optional<InputResourceRow> inputResource(String inputResource);

  /** The Input Resource an Input Observation observed. */
  Optional<String> observationResource(String observation);

  /** The latest Input Observation of an Input Resource. */
  Optional<String> latestObservation(String inputResource);

  /** Whether a log record is a user policy version. */
  boolean isPolicyVersion(String logRecord);
}
