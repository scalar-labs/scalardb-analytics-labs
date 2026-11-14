/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.projection;

import com.scalar.db.analytics.labs.semantic.model.Dependency;
import com.scalar.db.analytics.labs.semantic.model.ElementIdentity;
import com.scalar.db.analytics.labs.semantic.model.LogPosition;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.LogRecord.FactEvent;
import com.scalar.db.analytics.labs.semantic.model.LogRecordJson;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.AddressSelectionRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.DependencyRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.EdgeIdentity;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.ElementRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.FactAddressRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.FactRecordRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.FactRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.InputResourceRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.PresentablePeriodRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.RowKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

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
  private @Nullable PolicyInForce policyInForce;
  private final Map<RowKey, MaterializedRow> loaded = new HashMap<>();

  /** A projection of a domain that loads what it does not hold from a source. */
  public Projection(String domain, ProjectionSource source) {
    this.domain = domain;
    this.source = source;
  }

  public String domain() {
    return domain;
  }

  /**
   * A Fact, its Fact Record, and the materialized rows derived for it. The presentable periods are
   * kept oldest first; the {@link Projector} extends them at the end of each commit.
   */
  public static final class FactSlice {
    private final FactRow row;
    private final FactRecord record;
    final List<PresentablePeriodRow> periods;

    /**
     * The events this projection applied to the Fact. Earlier events are found in the source when a
     * basis or a dependency names one.
     */
    final Map<LogPosition, FactEvent> events = new HashMap<>();

    FactSlice(FactRow row, FactRecord record, List<PresentablePeriodRow> periods) {
      this.row = row;
      this.record = record;
      this.periods = new ArrayList<>(periods);
    }

    public FactRow row() {
      return row;
    }

    public FactRecord record() {
      return record;
    }

    /**
     * One row per dependency reference in effect or in effect at the latest confirmation, derived
     * from the Fact Record.
     */
    List<DependencyRow> dependencyRows() {
      var inEffect = new LinkedHashSet<>(record.dependencies());
      var confirmation = record.confirmationDependencies();
      var all = new LinkedHashSet<>(inEffect);
      all.addAll(confirmation);
      var rows = new ArrayList<DependencyRow>();
      for (var dependency : all) {
        var depended = dependency.version().urnValue();
        var current = inEffect.contains(dependency);
        var forConfirmation = confirmation.contains(dependency);
        rows.add(
            switch (dependency) {
              case Dependency.OnFact onFact ->
                  new DependencyRow(
                      row.fact(),
                      depended,
                      onFact.fact().urnValue(),
                      null,
                      current,
                      forConfirmation);
              case Dependency.OnInput onInput ->
                  new DependencyRow(
                      row.fact(),
                      depended,
                      null,
                      onInput.inputResource().urnValue(),
                      current,
                      forConfirmation);
            });
      }
      return rows;
    }

    /** The current Fact Record's row, or empty before the first acceptance. */
    Optional<FactRecordRow> recordRow() {
      return record
          .stored()
          .map(
              stored ->
                  new FactRecordRow(
                      row.fact(),
                      row.factAddress(),
                      stored.latestEvent(),
                      record.treatment(),
                      LogRecordJson.toStored(stored)));
    }

    Stream<MaterializedRow> rows() {
      return Stream.of(
              Stream.<MaterializedRow>of(row),
              recordRow().stream(),
              periods.stream(),
              dependencyRows().stream())
          .flatMap(rows -> rows);
    }
  }

  /**
   * A Fact Address, the Facts at it, its conflict resolutions, and the materialized rows derived
   * for it. Of its selection history only the open row is loaded; the {@link Projector} closes it
   * and adds a new one when the selection changes.
   */
  public static final class AddressSlice {
    private final FactAddressRow row;
    final Set<String> facts;
    final List<SelectionRule.Resolution> resolutions;
    final List<AddressSelectionRow> selections;

    AddressSlice(
        FactAddressRow row,
        List<String> facts,
        List<SelectionRule.Resolution> resolutions,
        List<AddressSelectionRow> selections) {
      this.row = row;
      this.facts = new LinkedHashSet<>(facts);
      this.resolutions = new ArrayList<>(resolutions);
      this.selections = new ArrayList<>(selections);
    }

    /** The selection holding at the end of the latest commit applied, if any. */
    public Optional<AddressSelectionRow> currentSelection() {
      return selections.isEmpty() || selections.getLast().toCommit() != null
          ? Optional.empty()
          : Optional.of(selections.getLast());
    }

    public FactAddressRow row() {
      return row;
    }

    public Set<String> facts() {
      return Set.copyOf(facts);
    }

    Stream<MaterializedRow> rows() {
      return Stream.concat(Stream.of(row), selections.stream());
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
    var loaded = found.get();
    var record =
        FactRecord.restore(
            LogRecordJson.fromStored(loaded.record().record(), FactRecord.Stored.class));
    var slice = new FactSlice(loaded.row(), record, loaded.periods());
    remember(Stream.of(loaded.row(), loaded.record()));
    remember(loaded.periods().stream());
    remember(loaded.dependencies().stream());
    facts.put(urn, slice);
    return Optional.of(slice);
  }

  /**
   * A Fact's event at a log position: one this projection applied, or one already in the log.
   *
   * @return empty when the Fact has no event there
   */
  public Optional<FactEvent> event(String fact, LogPosition at) {
    var held = facts.get(fact);
    if (held != null) {
      var applied = held.events.get(at);
      if (applied != null) {
        return Optional.of(applied);
      }
    }
    return source.event(fact, at);
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
    var slice =
        new AddressSlice(
            record.row(),
            record.facts(),
            record.resolutions(),
            record.currentSelection().stream().toList());
    remember(slice.rows());
    addresses.put(urn, slice);
    return Optional.of(slice);
  }

  /**
   * What the selection rule needs about every Fact at a Fact Address. A Fact this projection holds
   * answers from its Fact Record, which may have changed in the commit; any other comes from one
   * read of the source for the whole Fact Address.
   */
  public List<SelectionRule.Fact> selectionFacts(AddressSlice address) {
    var result = new ArrayList<SelectionRule.Fact>();
    var notHeld = new LinkedHashSet<String>();
    for (var fact : address.facts) {
      var held = facts.get(fact);
      if (held == null) {
        notHeld.add(fact);
        continue;
      }
      var record = held.record();
      result.add(
          new SelectionRule.Fact(
              fact,
              record.is(FactState.CONFIRMED)
                  ? record.latestConfirmation().map(LogPosition::commit).orElse(null)
                  : null,
              record.confirmationFactDependencies()));
    }
    if (!notHeld.isEmpty()) {
      for (var fact : source.selectionFacts(address.row().factAddress())) {
        if (notHeld.contains(fact.fact())) {
          result.add(fact);
        }
      }
    }
    return result;
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

  /**
   * The Facts that have a dependency reference to a Fact, in the store or among the Facts this
   * projection holds.
   */
  public Set<String> dependents(String fact) {
    var dependents = new LinkedHashSet<>(source.dependents(fact));
    for (var slice : facts.values()) {
      if (slice.record().factDependencies().contains(fact)) {
        dependents.add(slice.row().fact());
      }
    }
    return dependents;
  }

  /**
   * The Facts that may be confirmed with a dependency reference to a Fact: those the store holds as
   * such, and those this projection holds that are. A Fact the store returns may have changed in
   * this projection, so a caller judges each against its loaded Fact Record.
   */
  public Set<String> confirmedDependents(String fact) {
    var dependents = new LinkedHashSet<>(source.confirmedDependents(fact));
    for (var slice : facts.values()) {
      if (slice.record().is(FactState.CONFIRMED)
          && slice.record().confirmationFactDependencies().contains(fact)) {
        dependents.add(slice.row().fact());
      }
    }
    return dependents;
  }

  /** The Fact Address of a Fact, read without loading the Fact's records when it is not held. */
  public Optional<String> factAddress(String fact) {
    var held = facts.get(fact);
    return held != null ? Optional.of(held.row().factAddress()) : source.factAddress(fact);
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

  void addPolicyVersion(String logRecord, LogRecord.PolicyVersion version) {
    policyVersions.add(logRecord);
    policyInForce = new PolicyInForce(logRecord, version);
  }

  /**
   * A user policy version and its log record.
   *
   * @param logRecord the URN of the log record of the version
   */
  public record PolicyInForce(String logRecord, LogRecord.PolicyVersion version) {}

  /**
   * The user policy version in force after the records applied so far: the latest one applied, or
   * the latest one in the store.
   *
   * @return empty only for a domain whose log holds no policy version yet
   */
  public Optional<PolicyInForce> policyInForce() {
    if (policyInForce == null) {
      policyInForce =
          source
              .latestPolicyVersion()
              .map(
                  positioned ->
                      new PolicyInForce(
                          Urns.logRecord(domain, positioned.position()),
                          (LogRecord.PolicyVersion) positioned.record()))
              .orElse(null);
    }
    return Optional.ofNullable(policyInForce);
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
   * The rows to delete: those loaded that the applied records no longer derive. Only a dependency
   * reference that an acceptance dropped and no confirmation keeps disappears this way.
   */
  public List<MaterializedRow> removedRows() {
    var current = rows().map(MaterializedRow::key).collect(Collectors.toSet());
    return loaded.values().stream().filter(row -> !current.contains(row.key())).toList();
  }

  /**
   * The identity of an edge whose references are resolved, with each end in its {@linkplain
   * #canonicalEnd canonical form} and the ends of an undirected edge sorted, so two proposals of
   * the same edge give equal identities.
   */
  public static EdgeIdentity edgeIdentity(ElementIdentity.Edge edge) {
    var from = canonicalEnd(edge.fromEnd().stream().map(Ref::urnValue).toList());
    var to = canonicalEnd(edge.toEnd().stream().map(Ref::urnValue).toList());
    var swap = !edge.directed() && from.compareTo(to) > 0;
    return new EdgeIdentity(
        swap ? to : from,
        swap ? from : to,
        edge.directed(),
        edge.identityKey(),
        LogRecordJson.toStored(edge));
  }

  /**
   * The canonical form of an edge end stored and compared: a JSON array of the URNs of its
   * elements, sorted and without duplicates, so the order in which a set of Fields is named does
   * not matter.
   */
  public static String canonicalEnd(Collection<String> urns) {
    return LogRecordJson.writeStrings(urns.stream().sorted().distinct().toList());
  }
}
