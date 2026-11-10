/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.projection;

import com.scalar.db.analytics.labs.semantic.model.CommitRejectedException;
import com.scalar.db.analytics.labs.semantic.model.ElementIdentity;
import com.scalar.db.analytics.labs.semantic.model.LogPosition;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.ElementRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.FactAddressRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.FactRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.InputResourceRow;
import com.scalar.db.analytics.labs.semantic.projection.Projection.AddressSlice;
import com.scalar.db.analytics.labs.semantic.projection.Projection.FactSlice;
import java.util.List;
import java.util.Objects;

/**
 * Applies log records to a {@link Projection}, one commit at a time, deriving the materialized rows
 * from them.
 *
 * <p>Writing a commit and replaying the log use the same projector, so the materialized records are
 * derived by the same rules on both paths and the replay check compares like with like. A
 * {@linkplain #validating validating} projector, used when writing a commit, first checks each
 * record against what the earlier records left: that every record it refers to exists and has the
 * kind the reference needs. A {@linkplain #replaying replaying} projector applies records already
 * in the log and checks nothing.
 */
public final class Projector {
  private final Projection projection;
  private final String domain;
  private final boolean validating;
  private long commit;

  private Projector(Projection projection, boolean validating) {
    this.projection = projection;
    this.domain = projection.domain();
    this.validating = validating;
  }

  /** A projector for writing new commits, which rejects invalid records. */
  public static Projector validating(Projection projection) {
    return new Projector(projection, true);
  }

  /** A projector for records already in the log. */
  public static Projector replaying(Projection projection) {
    return new Projector(projection, false);
  }

  /** Starts applying the records of a commit. */
  public void begin(long commitNumber) {
    commit = commitNumber;
  }

  /**
   * Applies one record of the current commit.
   *
   * @throws com.scalar.db.analytics.labs.semantic.model.CommitRejectedException when a validating
   *     projector finds the record invalid
   */
  public void apply(PositionedRecord positioned) {
    var at = positioned.position();
    if (at.commit() != commit) {
      throw new IllegalStateException("Record " + at + " is not in commit " + commit);
    }
    try {
      applyRecord(at, positioned.record());
    } catch (IllegalArgumentException e) {
      if (!validating) {
        throw e;
      }
      throw new CommitRejectedException("Record " + at + " is invalid: " + e.getMessage());
    }
  }

  private void applyRecord(LogPosition at, LogRecord record) {
    switch (record) {
      case LogRecord.Element element -> applyElement(at, element);
      case LogRecord.FactAddress address -> applyFactAddress(at, address);
      case LogRecord.Fact fact -> applyFact(at, fact);
      case LogRecord.InputResource resource ->
          projection.addInputResource(
              new InputResourceRow(
                  Urns.of(domain, Urns.INPUT_RESOURCE, at),
                  resource.kind(),
                  resource.tableIdentifier(),
                  resource.question(),
                  commit));
      case LogRecord.InputObservation observation -> applyObservation(at, observation);
      case LogRecord.ObservationEvent event -> {
        if (validating
            && projection.observationResource(event.observation().urnValue()).isEmpty()) {
          throw missing("Input Observation", event.observation());
        }
      }
      case LogRecord.PolicyVersion ignored ->
          projection.addPolicyVersion(Urns.logRecord(domain, at));
    }
  }

  private void applyElement(LogPosition at, LogRecord.Element element) {
    var urn = Urns.of(domain, Urns.ELEMENT, at);
    var row =
        switch (element.identity()) {
          case ElementIdentity.Unique pointed ->
              new ElementRow(urn, pointed.kind(), null, commit, null);
          case ElementIdentity.PhysicalObject technical -> {
            var parent = technical.parent();
            if (validating && parent != null) {
              var parentRow = requireElement(parent);
              if (parentRow.kind() != ElementKind.DATASET) {
                throw reject("The parent of a Field is a Dataset, not " + parentRow.kind());
              }
            }
            yield new ElementRow(
                urn, technical.kind(), parent == null ? null : parent.urnValue(), commit, null);
          }
          case ElementIdentity.Edge edge -> {
            if (validating) {
              requireElement(edge.from());
              requireElement(edge.to());
            }
            yield new ElementRow(urn, edge.kind(), null, commit, Projection.edgeIdentity(edge));
          }
        };
    projection.addElement(row);
  }

  private void applyFactAddress(LogPosition at, LogRecord.FactAddress address) {
    if (validating) {
      requireElement(address.element());
      if (address.predicate().isBlank()) {
        throw reject("A Fact Address names a predicate");
      }
    }
    var scope = address.scope();
    var row =
        new FactAddressRow(
            Urns.of(domain, Urns.FACT_ADDRESS, at),
            address.element().urnValue(),
            address.predicate(),
            scope.description(),
            scope.entities().stream().map(Ref::urnValue).toList(),
            address.predicateDefinition(),
            commit);
    projection.addAddress(new AddressSlice(row, List.of()));
  }

  private void applyFact(LogPosition at, LogRecord.Fact fact) {
    var address = requireAddress(fact.address());
    var key = fact.identityKey();
    var row =
        new FactRow(
            Urns.of(domain, Urns.FACT, at),
            address.row().factAddress(),
            key == null ? null : key.canonical(),
            commit);
    projection.addFact(new FactSlice(row));
    address.facts.add(row.fact());
  }

  private void applyObservation(LogPosition at, LogRecord.InputObservation observation) {
    var resource = observation.inputResource().urnValue();
    if (validating) {
      requireInputResource(observation.inputResource());
      var previous = observation.previous();
      var expected = projection.latestObservation(resource);
      if (!Objects.equals(expected.orElse(null), previous == null ? null : previous.urnValue())) {
        throw reject(
            "An Input Observation refers to the latest observation of its Input Resource as its"
                + " previous one");
      }
    }
    projection.addObservation(Urns.of(domain, Urns.INPUT_OBSERVATION, at), resource);
  }

  // References

  private AddressSlice requireAddress(Ref address) {
    return projection
        .address(address.urnValue())
        .orElseThrow(() -> missing("Fact Address", address));
  }

  private ElementRow requireElement(Ref element) {
    return projection.element(element.urnValue()).orElseThrow(() -> missing("element", element));
  }

  private InputResourceRow requireInputResource(Ref resource) {
    return projection
        .inputResource(resource.urnValue())
        .orElseThrow(() -> missing("Input Resource", resource));
  }

  private static CommitRejectedException missing(String what, Ref reference) {
    return reject("Missing reference: no " + what + " " + reference.urnValue());
  }

  private static CommitRejectedException reject(String reason) {
    return new CommitRejectedException(reason);
  }
}
