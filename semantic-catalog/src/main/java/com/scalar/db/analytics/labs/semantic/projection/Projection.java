/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.projection;

import com.scalar.db.analytics.labs.semantic.model.ElementIdentity;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.EdgeIdentity;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.ElementRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.FactAddressRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.FactRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.InputResourceRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.RowKey;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The materialized records of one domain held in memory: all of them after a replay from empty, or,
 * when a commit is written, only those the commit touches.
 *
 * <p>A record not held yet is loaded from the {@link ProjectionSource} when first asked for, and
 * the rows it came with are remembered, so {@link #changedRows()} returns exactly the rows the
 * applied records inserted or changed. The {@link Projector} is the only thing that changes a
 * projection.
 */
public final class Projection {
  private final String domain;
  private final ProjectionSource source;
  private final Map<String, FactSlice> facts = new LinkedHashMap<>();
  private final Map<String, AddressSlice> addresses = new LinkedHashMap<>();
  private final Map<String, ElementRow> elements = new LinkedHashMap<>();
  private final Map<String, InputResourceRow> inputResources = new LinkedHashMap<>();
  private final Map<String, String> observationResources = new HashMap<>();
  private final Map<String, String> latestObservations = new HashMap<>();
  private final Set<String> policyVersions = new HashSet<>();
  private final Map<RowKey, MaterializedRow> loaded = new HashMap<>();

  /** A projection of a domain that loads what it does not hold from a source. */
  public Projection(String domain, ProjectionSource source) {
    this.domain = domain;
    this.source = source;
  }

  public String domain() {
    return domain;
  }

  /** A Fact and the materialized rows derived for it. */
  public static final class FactSlice {
    private final FactRow row;

    FactSlice(FactRow row) {
      this.row = row;
    }

    public FactRow row() {
      return row;
    }

    Stream<MaterializedRow> rows() {
      return Stream.of(row);
    }
  }

  /** A Fact Address, the Facts at it, and the materialized rows derived for it. */
  public static final class AddressSlice {
    private final FactAddressRow row;
    final Set<String> facts;

    AddressSlice(FactAddressRow row, List<String> facts) {
      this.row = row;
      this.facts = new LinkedHashSet<>(facts);
    }

    public FactAddressRow row() {
      return row;
    }

    public Set<String> facts() {
      return Set.copyOf(facts);
    }

    Stream<MaterializedRow> rows() {
      return Stream.of(row);
    }
  }

  // Lookups, loading from the source on first use. Each returns empty when the record exists
  // neither in this projection nor in its source.

  /** A Fact by URN. */
  public Optional<FactSlice> fact(String urn) {
    var held = facts.get(urn);
    if (held != null) {
      return Optional.of(held);
    }
    var found = source.fact(urn);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    var slice = new FactSlice(found.get());
    remember(slice.rows());
    facts.put(urn, slice);
    return Optional.of(slice);
  }

  /** A Fact Address by URN. */
  public Optional<AddressSlice> address(String urn) {
    var held = addresses.get(urn);
    if (held != null) {
      return Optional.of(held);
    }
    var found = source.address(urn);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    var record = found.get();
    var slice = new AddressSlice(record.row(), record.facts());
    remember(slice.rows());
    addresses.put(urn, slice);
    return Optional.of(slice);
  }

  /** An element by URN. */
  public Optional<ElementRow> element(String urn) {
    var held = elements.get(urn);
    if (held != null) {
      return Optional.of(held);
    }
    var found = source.element(urn);
    found.ifPresent(
        row -> {
          remember(Stream.of(row));
          elements.put(urn, row);
        });
    return found;
  }

  /** An Input Resource by URN. */
  public Optional<InputResourceRow> inputResource(String urn) {
    var held = inputResources.get(urn);
    if (held != null) {
      return Optional.of(held);
    }
    var found = source.inputResource(urn);
    found.ifPresent(
        row -> {
          remember(Stream.of(row));
          inputResources.put(urn, row);
        });
    return found;
  }

  /** The Input Resource an Input Observation observed. */
  public Optional<String> observationResource(String observation) {
    var held = observationResources.get(observation);
    return held != null ? Optional.of(held) : source.observationResource(observation);
  }

  /**
   * The latest Input Observation of an Input Resource, including ones applied to this projection.
   */
  public Optional<String> latestObservation(String inputResource) {
    var held = latestObservations.get(inputResource);
    return held != null ? Optional.of(held) : source.latestObservation(inputResource);
  }

  /** Whether a log record is a user policy version. */
  public boolean isPolicyVersion(String logRecord) {
    return policyVersions.contains(logRecord) || source.isPolicyVersion(logRecord);
  }

  // Changes made by the projector

  void addFact(FactSlice slice) {
    facts.put(slice.row().fact(), slice);
  }

  void addAddress(AddressSlice slice) {
    addresses.put(slice.row().factAddress(), slice);
  }

  void addElement(ElementRow row) {
    elements.put(row.element(), row);
  }

  void addInputResource(InputResourceRow row) {
    inputResources.put(row.inputResource(), row);
  }

  void addObservation(String observation, String inputResource) {
    observationResources.put(observation, inputResource);
    latestObservations.put(inputResource, observation);
  }

  void addPolicyVersion(String logRecord) {
    policyVersions.add(logRecord);
  }

  private void remember(Stream<? extends MaterializedRow> rows) {
    rows.forEach(row -> loaded.put(row.key(), row));
  }

  // Results

  /** Every row held, which is every materialized row of the domain after a replay from empty. */
  public Stream<MaterializedRow> rows() {
    return Stream.of(
            elements.values().stream().map(MaterializedRow.class::cast),
            inputResources.values().stream().map(MaterializedRow.class::cast),
            addresses.values().stream().flatMap(AddressSlice::rows),
            facts.values().stream().flatMap(FactSlice::rows))
        .flatMap(rows -> rows);
  }

  /**
   * The rows to write back: those that did not exist when loaded and those whose content changed,
   * compared by {@linkplain MaterializedRow#key() key}.
   */
  public List<MaterializedRow> changedRows() {
    return rows().filter(row -> !row.equals(loaded.get(row.key()))).toList();
  }

  /**
   * The identity of an edge whose references are resolved, with the ends of an undirected edge
   * sorted by URN, so two proposals of the same edge give equal identities.
   */
  public static EdgeIdentity edgeIdentity(ElementIdentity.Edge edge) {
    var from = edge.from().urnValue();
    var to = edge.to().urnValue();
    var swap = !edge.directed() && from.compareTo(to) > 0;
    return new EdgeIdentity(
        swap ? to : from,
        swap ? from : to,
        edge.directed(),
        edge.identityKey().canonical(),
        edge.identifyingPart());
  }
}
