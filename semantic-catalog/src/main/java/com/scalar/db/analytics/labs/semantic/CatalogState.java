/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.generator.CatalogView;
import com.scalar.db.analytics.labs.semantic.model.Check;
import com.scalar.db.analytics.labs.semantic.model.DependencyRef;
import com.scalar.db.analytics.labs.semantic.model.ElementDescriptor;
import com.scalar.db.analytics.labs.semantic.model.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.LogEntry;
import com.scalar.db.analytics.labs.semantic.model.NormalForms;
import com.scalar.db.analytics.labs.semantic.model.Provenance;
import com.scalar.db.analytics.labs.semantic.model.Scope;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.AnswerType;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ConfirmationPath;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ObservationKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Preset;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Target;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The state derived from one domain's log.
 *
 * <p>Applying log records in order is the only way the state changes, so replaying the whole log
 * from an empty state reproduces it. Applying records does no judgement: the decisions recorded as
 * policy evaluations, confirmations, and cascades are made by {@link Judge} before the records are
 * appended. At the end of each commit, the selection of every Fact Address the commit touched is
 * derived by the rules of section 6.2 and kept with the range of commits it is valid for, so any
 * past point can be read without replaying again.
 */
final class CatalogState implements CatalogView {
  private final String domain;
  private long commit;
  private final Map<String, ElementRecord> elements = new LinkedHashMap<>();
  private final Map<String, FactRecord> facts = new LinkedHashMap<>();
  private final Map<String, AddressRecord> addresses = new LinkedHashMap<>();
  private final Map<String, ObservationView> observations = new LinkedHashMap<>();
  private final Map<String, List<ObservationView>> observationsByResource = new HashMap<>();
  private final List<PolicyRecord> policies = new ArrayList<>();
  private final Map<String, Set<String>> dependents = new HashMap<>();
  private final Map<String, AnswerView> answers = new LinkedHashMap<>();
  private final Map<String, LogEntry> entries = new HashMap<>();
  private final Set<String> touchedAddresses = new LinkedHashSet<>();
  private final Set<String> touchedFacts = new LinkedHashSet<>();

  CatalogState(String domain) {
    this.domain = domain;
  }

  @Override
  public String domain() {
    return domain;
  }

  @Override
  public long commit() {
    return commit;
  }

  // Applying the log

  void apply(String id, long commitNumber, LogEntry entry) {
    entries.put(id, entry);
    switch (entry) {
      case LogEntry.Observe observe -> applyObserve(id, commitNumber, observe);
      case LogEntry.PolicyVersion version ->
          policies.add(new PolicyRecord(id, commitNumber, version));
      case LogEntry.Accept accept -> applyAccept(id, commitNumber, accept);
      case LogEntry.ProposeRetire proposal -> {
        var fact = requireFact(proposal.fact());
        fact.openRetirementProposal = id;
        fact.retirementChecks = proposal.checks();
        fact.revise(id, commitNumber);
      }
      case LogEntry.EvaluatePolicy evaluation -> {
        var fact = requireFact(evaluation.fact());
        if (evaluation.target() == Target.CONFIRMATION) {
          fact.latestConfirmationEvaluation = evaluation;
          fact.latestConfirmationEvaluationId = id;
        }
        fact.revise(id, commitNumber);
      }
      case LogEntry.Answer answer -> applyAnswer(id, commitNumber, answer);
      case LogEntry.Confirm confirm -> {
        var fact = requireFact(confirm.fact());
        fact.setState(FactState.CONFIRMED, commitNumber);
        fact.periods.add(new long[] {commitNumber, -1});
        fact.confirmedDependencies = List.copyOf(fact.dependencies);
        fact.confirmationPath = confirm.path();
        fact.confirmationBasis = confirm.basis();
        fact.decidingParty = decidingParty(confirm.basis());
        fact.policyVersion = policyVersionOf(confirm.basis());
        fact.lastChecked = commitNumber;
        fact.revise(id, commitNumber);
        touchedAddresses.add(fact.address);
      }
      case LogEntry.Reject reject -> {
        var fact = requireFact(reject.fact());
        fact.setState(FactState.REJECTED, commitNumber);
        fact.closeJudgement();
        fact.revise(id, commitNumber);
        touchedAddresses.add(fact.address);
      }
      case LogEntry.Retire retire -> {
        var fact = requireFact(retire.fact());
        fact.setState(FactState.RETIRED, commitNumber);
        fact.periods.getLast()[1] = commitNumber;
        fact.closeJudgement();
        fact.revise(id, commitNumber);
        touchedAddresses.add(fact.address);
      }
      case LogEntry.Select select -> {
        var address = Objects.requireNonNull(addresses.get(select.address()), "address");
        address.selections.add(new SelectionEvent(commitNumber, select));
        touchedAddresses.add(address.urn);
      }
    }
  }

  private void applyObserve(String id, long commitNumber, LogEntry.Observe observe) {
    var view =
        new ObservationView(
            observe.urn(),
            id,
            observe.inputResource(),
            observe.kind(),
            observe.content(),
            observe.version(),
            observe.question(),
            observe.digest(),
            commitNumber);
    observations.put(observe.urn(), view);
    observationsByResource
        .computeIfAbsent(observe.inputResource(), key -> new ArrayList<>())
        .add(view);
  }

  private void applyAccept(String id, long commitNumber, LogEntry.Accept accept) {
    if (accept.descriptor() != null && !elements.containsKey(accept.element())) {
      elements.put(accept.element(), new ElementRecord(accept.descriptor(), commitNumber));
    }
    var fact = facts.get(accept.fact());
    if (fact == null) {
      fact = new FactRecord(accept, commitNumber);
      facts.put(fact.urn, fact);
      addresses
          .computeIfAbsent(
              accept.address(),
              key -> new AddressRecord(key, accept.element(), accept.predicate(), accept.scope()))
          .facts
          .add(fact.urn);
      elementRecord(accept.element()).facts.add(fact.urn);
    } else if (fact.state == FactState.REJECTED || fact.state == FactState.RETIRED) {
      fact.setState(FactState.CANDIDATE, commitNumber);
    }
    var hadNewDependency = false;
    for (var dependency : accept.dependencies()) {
      if (!fact.dependencies.contains(dependency)) {
        fact.dependencies.add(dependency);
        hadNewDependency = true;
      }
      if (dependency instanceof DependencyRef.OnFact onFact) {
        dependents.computeIfAbsent(onFact.fact(), key -> new LinkedHashSet<>()).add(fact.urn);
      }
    }
    if (hadNewDependency) {
      fact.unknownAnswered = false;
    }
    for (var check : accept.checks()) {
      if (!fact.checks.contains(check)) {
        fact.checks.add(check);
      }
    }
    fact.basis.addAll(accept.basis());
    fact.provenance = accept.provenance();
    fact.lastChecked = commitNumber;
    fact.revise(id, commitNumber);
    touchedAddresses.add(fact.address);
  }

  private void applyAnswer(String id, long commitNumber, LogEntry.Answer answer) {
    var fact = requireFact(answer.fact());
    answers.put(
        id,
        new AnswerView(
            id,
            answer.fact(),
            answer.target(),
            answer.answerType(),
            answer.principal(),
            answer.wording()));
    fact.answers.add(id);
    if (answer.target() == Target.RETIREMENT
        && (answer.answerType() == AnswerType.REJECTION
            || answer.answerType() == AnswerType.CORRECTION)) {
      fact.openRetirementProposal = null;
    }
    if (answer.answerType() == AnswerType.UNKNOWN) {
      fact.unknownAnswered = true;
      fact.dependenciesAtLastJudgement = Set.copyOf(fact.dependencies);
    }
    fact.revise(id, commitNumber);
  }

  /** Derives the selection of every Fact Address the commit touched, then closes the commit. */
  void endCommit(long commitNumber) {
    for (var urn : touchedAddresses) {
      deriveSelection(Objects.requireNonNull(addresses.get(urn)), commitNumber);
    }
    touchedAddresses.clear();
    touchedFacts.clear();
    commit = commitNumber;
  }

  private void deriveSelection(AddressRecord address, long c) {
    var eligible = new ArrayList<String>();
    for (var urn : address.facts) {
      if (presentable(urn, c) && !displaced(address, urn, c)) {
        eligible.add(urn);
      }
    }
    var previous = address.selectionAt(c - 1).selected();
    String selected;
    if (eligible.isEmpty()) {
      selected = null;
    } else if (eligible.size() == 1) {
      selected = eligible.getFirst();
    } else if (previous != null && eligible.contains(previous)) {
      selected = previous;
    } else {
      selected = null;
      if (previous != null && presentable(previous, c)) {
        var replacing = eligible.stream().filter(urn -> dependsOn(urn, previous)).toList();
        selected = replacing.size() == 1 ? replacing.getFirst() : previous;
      } else if (previous != null) {
        var replaced =
            requireFact(previous).confirmedDependencies.stream()
                .filter(DependencyRef.OnFact.class::isInstance)
                .map(dependency -> ((DependencyRef.OnFact) dependency).fact())
                .filter(eligible::contains)
                .findFirst();
        selected = replaced.orElse(null);
      }
    }
    address.record(new Selection(c, -1, selected, List.copyOf(eligible)));
  }

  private boolean presentable(String factUrn, long c) {
    for (var period : requireFact(factUrn).periods) {
      if (period[0] <= c && (period[1] < 0 || c < period[1])) {
        return true;
      }
    }
    return false;
  }

  private boolean displaced(AddressRecord address, String factUrn, long c) {
    for (var other : address.facts) {
      if (!other.equals(factUrn) && presentable(other, c) && dependsOn(other, factUrn)) {
        return true;
      }
    }
    for (var event : address.selections) {
      if (event.commit <= c
          && event.select.notSelected().contains(factUrn)
          && presentable(event.select.selected(), c)) {
        return true;
      }
    }
    return false;
  }

  /** Whether a Fact's confirmed dependency references include a revision of another Fact. */
  private boolean dependsOn(String factUrn, String otherUrn) {
    return requireFact(factUrn).confirmedDependencies.stream()
        .anyMatch(
            dependency ->
                dependency instanceof DependencyRef.OnFact onFact
                    && onFact.fact().equals(otherUrn));
  }

  // Reads used by judgement and the public API

  FactRecord requireFact(String urn) {
    return Objects.requireNonNull(facts.get(urn), () -> "Unknown Fact " + urn);
  }

  @Nullable FactRecord factRecord(String urn) {
    return facts.get(urn);
  }

  @Nullable AddressRecord address(String urn) {
    return addresses.get(urn);
  }

  Map<String, FactRecord> factRecords() {
    return facts;
  }

  Map<String, AddressRecord> addressRecords() {
    return addresses;
  }

  Map<String, ElementRecord> elementRecords() {
    return elements;
  }

  Set<String> dependentsOf(String factUrn) {
    return dependents.getOrDefault(factUrn, Set.of());
  }

  @Nullable LogEntry entry(String id) {
    return entries.get(id);
  }

  PolicyRecord policyAt(long c) {
    PolicyRecord current = null;
    for (var policy : policies) {
      if (policy.commit <= c) {
        current = policy;
      }
    }
    return current == null
        ? new PolicyRecord(
            "default", 0, new LogEntry.PolicyVersion(Preset.AUTO, false, "system", "default"))
        : current;
  }

  ElementRecord elementRecord(String urn) {
    return Objects.requireNonNull(elements.get(urn), () -> "Unknown element " + urn);
  }

  /** Whether every Fact the latest revision depends on is confirmed. */
  boolean dependenciesConfirmed(FactRecord fact) {
    return fact.factDependencies().stream()
        .allMatch(urn -> facts.containsKey(urn) && facts.get(urn).state == FactState.CONFIRMED);
  }

  private @Nullable String decidingParty(String basis) {
    var entry = entries.get(basis);
    return switch (entry) {
      case LogEntry.Answer answer -> answer.principal();
      case LogEntry.EvaluatePolicy evaluation -> evaluation.decidingParty();
      case null, default -> null;
    };
  }

  private @Nullable String policyVersionOf(String basis) {
    return entries.get(basis) instanceof LogEntry.EvaluatePolicy evaluation
        ? evaluation.policyVersion()
        : policyAt(Long.MAX_VALUE).id;
  }

  // CatalogView

  @Override
  public Optional<ElementView> element(String urn) {
    return Optional.ofNullable(elements.get(urn)).map(this::view);
  }

  @Override
  public List<ElementView> elements(ElementKind kind) {
    return elements.values().stream()
        .filter(element -> element.descriptor.kind() == kind)
        .map(this::view)
        .toList();
  }

  @Override
  public List<ElementView> children(String parentUrn) {
    return elements.values().stream()
        .filter(element -> parentUrn.equals(element.descriptor.parent()))
        .map(this::view)
        .toList();
  }

  ElementView view(ElementRecord element) {
    var existence =
        element.facts.stream()
            .map(facts::get)
            .filter(fact -> fact.predicate.equals("exists") && fact.scope.unrestricted())
            .map(fact -> fact.state)
            .reduce((first, second) -> first == FactState.CONFIRMED ? first : second)
            .orElse(null);
    return new ElementView(element.descriptor, existence);
  }

  @Override
  public Optional<FactView> fact(String urn) {
    return Optional.ofNullable(facts.get(urn)).map(FactRecord::view);
  }

  @Override
  public List<FactView> facts(String elementUrn) {
    var element = elements.get(elementUrn);
    if (element == null) {
      return List.of();
    }
    return element.facts.stream().map(facts::get).map(FactRecord::view).toList();
  }

  @Override
  public Optional<FactView> selected(String elementUrn, String predicate) {
    var address =
        addresses.get(Urns.factAddress(domain, elementUrn, predicate, Scope.UNRESTRICTED));
    if (address == null) {
      return Optional.empty();
    }
    var selected = address.selectionAt(commit).selected();
    return selected == null ? Optional.empty() : fact(selected);
  }

  @Override
  public Optional<ObservationView> observation(String urn) {
    return Optional.ofNullable(observations.get(urn));
  }

  @Override
  public Optional<ObservationView> latestObservation(String inputResource) {
    var list = observationsByResource.get(inputResource);
    return list == null || list.isEmpty() ? Optional.empty() : Optional.of(list.getLast());
  }

  @Override
  public List<ObservationView> observations(String inputResource) {
    return List.copyOf(observationsByResource.getOrDefault(inputResource, List.of()));
  }

  /** The latest observation of an Input Resource as of a commit. */
  Optional<ObservationView> latestObservation(String inputResource, long c) {
    var list = observationsByResource.getOrDefault(inputResource, List.of());
    ObservationView latest = null;
    for (var observation : list) {
      if (observation.commit() <= c) {
        latest = observation;
      }
    }
    return Optional.ofNullable(latest);
  }

  @Override
  public List<ObservationView> latestObservations(ObservationKind kind) {
    return observationsByResource.values().stream()
        .map(List::getLast)
        .filter(observation -> observation.kind() == kind)
        .toList();
  }

  @Override
  public Optional<AnswerView> answer(String logRecordId) {
    return Optional.ofNullable(answers.get(logRecordId));
  }

  @Override
  public List<AnswerView> answers(String factUrn) {
    var fact = facts.get(factUrn);
    return fact == null ? List.of() : fact.answers.stream().map(answers::get).toList();
  }

  // Records

  record PolicyRecord(String id, long commit, LogEntry.PolicyVersion version) {}

  record SelectionEvent(long commit, LogEntry.Select select) {}

  /**
   * The selected Fact and the eligible Facts of a Fact Address, valid from one commit until the
   * commit before {@code to}, or onward when {@code to} is negative.
   */
  record Selection(long from, long to, @Nullable String selected, List<String> eligible) {
    boolean conflict() {
      return eligible.size() > 1;
    }
  }

  static final class ElementRecord {
    final ElementDescriptor descriptor;
    final long created;
    final List<String> facts = new ArrayList<>();

    ElementRecord(ElementDescriptor descriptor, long created) {
      this.descriptor = descriptor;
      this.created = created;
    }
  }

  static final class AddressRecord {
    private static final Selection NONE = new Selection(0, -1, null, List.of());

    final String urn;
    final String element;
    final String predicate;
    final Scope scope;
    final Set<String> facts = new LinkedHashSet<>();
    final List<SelectionEvent> selections = new ArrayList<>();
    final List<Selection> history = new ArrayList<>();

    AddressRecord(String urn, String element, String predicate, Scope scope) {
      this.urn = urn;
      this.element = element;
      this.predicate = predicate;
      this.scope = scope;
    }

    Selection selectionAt(long c) {
      for (var i = history.size() - 1; i >= 0; i--) {
        var selection = history.get(i);
        if (selection.from <= c) {
          return selection;
        }
      }
      return NONE;
    }

    void record(Selection selection) {
      if (!history.isEmpty()) {
        var last = history.getLast();
        if (Objects.equals(last.selected, selection.selected)
            && last.eligible.equals(selection.eligible)) {
          return;
        }
        history.set(
            history.size() - 1,
            new Selection(last.from, selection.from, last.selected, last.eligible));
      } else if (selection.selected == null && selection.eligible.isEmpty()) {
        return;
      }
      history.add(selection);
    }
  }

  /** One version of a Fact, as produced by one Fact Event. */
  record Revision(
      String urn,
      String event,
      long commit,
      FactState state,
      List<DependencyRef> dependencies,
      List<Check> checks,
      @Nullable List<Check> retirementChecks,
      @Nullable Provenance provenance,
      @Nullable ConfirmationPath confirmationPath,
      @Nullable String confirmationBasis,
      @Nullable String decidingParty,
      @Nullable String policyVersion,
      long lastChecked) {}

  static final class FactRecord {
    final String urn;
    final String address;
    final String element;
    final String predicate;
    final Scope scope;
    final JsonNode value;
    final String normalForm;
    final String generator;
    FactState state = FactState.CANDIDATE;
    final List<long[]> stateHistory = new ArrayList<>();
    final List<Revision> revisions = new ArrayList<>();
    final List<DependencyRef> dependencies = new ArrayList<>();
    final List<Check> checks = new ArrayList<>();
    final List<String> basis = new ArrayList<>();
    final List<String> answers = new ArrayList<>();
    final List<long[]> periods = new ArrayList<>();
    List<DependencyRef> confirmedDependencies = List.of();
    Set<DependencyRef> dependenciesAtLastJudgement = Set.of();
    @Nullable Provenance provenance;
    LogEntry.@Nullable EvaluatePolicy latestConfirmationEvaluation;
    @Nullable String latestConfirmationEvaluationId;
    @Nullable String openRetirementProposal;
    @Nullable List<Check> retirementChecks;
    @Nullable ConfirmationPath confirmationPath;
    @Nullable String confirmationBasis;
    @Nullable String decidingParty;
    @Nullable String policyVersion;
    boolean unknownAnswered;
    long lastChecked;

    FactRecord(LogEntry.Accept accept, long commitNumber) {
      urn = accept.fact();
      address = accept.address();
      element = accept.element();
      predicate = accept.predicate();
      scope = accept.scope();
      value = accept.value();
      normalForm = NormalForms.of(accept.value());
      generator = accept.provenance().generator();
      stateHistory.add(new long[] {commitNumber, FactState.CANDIDATE.ordinal()});
    }

    void setState(FactState next, long commitNumber) {
      state = next;
      stateHistory.add(new long[] {commitNumber, next.ordinal()});
    }

    void closeJudgement() {
      dependenciesAtLastJudgement = Set.copyOf(dependencies);
      openRetirementProposal = null;
    }

    void revise(String event, long commitNumber) {
      revisions.add(
          new Revision(
              Urns.factRevision(urn, event),
              event,
              commitNumber,
              state,
              List.copyOf(dependencies),
              List.copyOf(checks),
              retirementChecks == null ? null : List.copyOf(retirementChecks),
              provenance,
              confirmationPath,
              confirmationBasis,
              decidingParty,
              policyVersion,
              lastChecked));
    }

    /** The state as of a commit, or null when the Fact did not exist yet. */
    @Nullable FactState stateAt(long c) {
      FactState result = null;
      for (var change : stateHistory) {
        if (change[0] <= c) {
          result = FactState.values()[(int) change[1]];
        }
      }
      return result;
    }

    /** The latest revision as of a commit, or null when the Fact did not exist yet. */
    @Nullable Revision revisionAt(long c) {
      Revision result = null;
      for (var revision : revisions) {
        if (revision.commit() <= c) {
          result = revision;
        }
      }
      return result;
    }

    Revision latestRevision() {
      return revisions.getLast();
    }

    List<String> factDependencies() {
      return dependencies.stream()
          .filter(DependencyRef.OnFact.class::isInstance)
          .map(dependency -> ((DependencyRef.OnFact) dependency).fact())
          .distinct()
          .toList();
    }

    FactView view() {
      return new FactView(
          urn,
          address,
          element,
          predicate,
          scope,
          value,
          state,
          latestRevision().urn(),
          generator,
          factDependencies());
    }
  }
}
