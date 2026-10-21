/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.scalar.db.analytics.labs.semantic.CatalogState.FactRecord;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorResult.Candidate;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorResult.RetirementProposal;
import com.scalar.db.analytics.labs.semantic.model.Check;
import com.scalar.db.analytics.labs.semantic.model.DependencyRef;
import com.scalar.db.analytics.labs.semantic.model.Facts;
import com.scalar.db.analytics.labs.semantic.model.LogEntry;
import com.scalar.db.analytics.labs.semantic.model.NormalForms;
import com.scalar.db.analytics.labs.semantic.model.Predicates;
import com.scalar.db.analytics.labs.semantic.model.Provenance;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.AnswerType;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ConfirmationPath;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.PolicyResult;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Preset;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Target;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Makes the judgements of sections 4 and 5 of the domain model and appends what they decide to one
 * commit: acceptance, user policy evaluation, confirmation by machine judgement, answers, and the
 * cascading transitions of section 4.5.
 */
final class Judge {
  private final CatalogState state;
  private final Commit commit;

  Judge(CatalogState state, Commit commit) {
    this.state = state;
    this.commit = commit;
  }

  /** The outcome of accepting one candidate: the Fact, or why it was not recorded. */
  record Acceptance(String fact, boolean recorded, @Nullable String reason) {}

  Acceptance accept(Candidate candidate, Provenance provenance) {
    var domain = state.domain();
    var address =
        Facts.address(domain, candidate.element(), candidate.predicate(), candidate.scope());
    var factUrn = Urns.fact(domain, address, NormalForms.of(candidate.value()));
    var element = state.elementRecords().get(candidate.element());
    var descriptor = element == null ? candidate.descriptor() : element.descriptor;
    if (descriptor == null) {
      return new Acceptance(
          factUrn, false, "The element is unknown and the candidate does not state its existence.");
    }
    if (!descriptor.kind().accepts(candidate.predicate())) {
      return new Acceptance(
          factUrn, false, "The predicate is not one of the element kind's predicates.");
    }
    if (element == null && !candidate.predicate().equals(Predicates.EXISTS)) {
      return new Acceptance(
          factUrn, false, "The element is unknown and the candidate does not state its existence.");
    }
    var dependencies = new ArrayList<DependencyRef>();
    for (var dependency : candidate.dependencies()) {
      var resolved = resolve(dependency);
      if (resolved == null) {
        return new Acceptance(
            factUrn, false, "A dependency reference points to an unknown Fact or observation.");
      }
      dependencies.add(resolved);
    }
    var checks = new ArrayList<Check>();
    for (var check : candidate.checks()) {
      var rationale = check.rationale() == null ? null : resolve(check.rationale());
      checks.add(new Check(check.method(), check.items(), check.result(), rationale));
    }
    if (formsCycle(factUrn, dependencies)) {
      return new Acceptance(factUrn, false, "The dependency references form a cycle.");
    }
    var existing = state.factRecord(factUrn);
    if (existing != null) {
      if (existing.dependencies.containsAll(dependencies) && existing.checks.containsAll(checks)) {
        return new Acceptance(
            factUrn, false, "The same Fact was proposed again with the same rationale.");
      }
      if ((existing.state == FactState.REJECTED || existing.state == FactState.RETIRED)
          && !hasNewDependency(existing, dependencies)) {
        return new Acceptance(
            factUrn,
            false,
            "The Fact was judged before and the proposal has no new dependency reference.");
      }
    }
    commit.append(
        new LogEntry.Accept(
            factUrn,
            address,
            candidate.element(),
            candidate.predicate(),
            candidate.scope(),
            NormalForms.normalize(candidate.value()),
            existing == null && candidate.predicate().equals(Predicates.EXISTS) ? descriptor : null,
            dependencies,
            checks,
            candidate.basis(),
            provenance));
    var fact = state.requireFact(factUrn);
    if (fact.state == FactState.CANDIDATE && state.dependenciesConfirmed(fact)) {
      evaluateConfirmation(fact);
    }
    return new Acceptance(factUrn, true, null);
  }

  /** Resolves a reference to a Fact given by its URN to the Fact's latest revision. */
  private @Nullable DependencyRef resolve(DependencyRef dependency) {
    return switch (dependency) {
      case DependencyRef.OnFact onFact -> {
        var fact = state.factRecord(onFact.fact());
        if (fact == null) {
          yield null;
        }
        yield onFact.factRevision().contains("@")
            ? onFact
            : new DependencyRef.OnFact(fact.latestRevision().urn());
      }
      case DependencyRef.OnObservation onObservation ->
          state.observation(onObservation.inputObservation()).isPresent() ? onObservation : null;
    };
  }

  private boolean formsCycle(String factUrn, List<DependencyRef> dependencies) {
    var pending = new ArrayDeque<String>();
    dependencies.stream()
        .filter(DependencyRef.OnFact.class::isInstance)
        .map(dependency -> ((DependencyRef.OnFact) dependency).fact())
        .forEach(pending::add);
    var seen = new HashSet<String>();
    while (!pending.isEmpty()) {
      var next = pending.pop();
      if (next.equals(factUrn)) {
        return true;
      }
      if (seen.add(next)) {
        var fact = state.factRecord(next);
        if (fact != null) {
          pending.addAll(fact.factDependencies());
        }
      }
    }
    return false;
  }

  /**
   * Whether a proposal brings a dependency reference not held at the Fact's latest judgement. A
   * reference to an Input Observation is new only when the digest of its selected range changed.
   */
  private static boolean hasNewDependency(FactRecord fact, List<DependencyRef> dependencies) {
    var known = new HashSet<String>();
    fact.dependenciesAtLastJudgement.forEach(dependency -> known.add(key(dependency)));
    return dependencies.stream().anyMatch(dependency -> !known.contains(key(dependency)));
  }

  private static String key(DependencyRef dependency) {
    return switch (dependency) {
      case DependencyRef.OnFact onFact -> onFact.factRevision();
      case DependencyRef.OnObservation onObservation ->
          onObservation.inputResource()
              + "|"
              + onObservation.selector()
              + "|"
              + onObservation.digest();
    };
  }

  // User policy

  /** The result of evaluating a Fact under a setting, without recording it. */
  record Decision(PolicyResult result, String reason) {}

  Decision decideConfirmation(FactRecord fact, Preset preset, boolean countDeclaredUnenforced) {
    return decideConfirmation(state, fact, preset, countDeclaredUnenforced, commit.number);
  }

  static Decision decideConfirmation(
      CatalogState state,
      FactRecord fact,
      Preset preset,
      boolean countDeclaredUnenforced,
      long commitNumber) {
    switch (preset) {
      case FULL_AUTO -> {
        return new Decision(PolicyResult.PROCEED, "The full-auto preset confirms every candidate.");
      }
      case MANUAL -> {
        return new Decision(
            PolicyResult.REQUEST_CONSENT, "The manual preset asks for consent to every candidate.");
      }
      case AUTO -> {}
    }
    var element = state.elementRecord(fact.element).descriptor;
    var items =
        Predicates.validationItems(element.kind(), fact.predicate, element.identifyingOrEmpty());
    var checks = fact.checks;
    if (checks.stream().anyMatch(check -> check.result() == Check.Result.REFUTED)) {
      return new Decision(PolicyResult.REQUEST_CONSENT, "A check refutes the candidate.");
    }
    var unsupported = new ArrayList<String>();
    for (var item : items) {
      var covered =
          checks.stream()
              .anyMatch(
                  check ->
                      check.result() == Check.Result.SUPPORTED
                          && deterministic(check.method(), countDeclaredUnenforced)
                          && check.items().contains(item));
      if (!covered) {
        unsupported.add(item);
      }
    }
    if (!unsupported.isEmpty()) {
      return new Decision(
          PolicyResult.REQUEST_CONSENT,
          "Not every validation item is supported by a deterministic check: "
              + String.join(", ", unsupported)
              + ".");
    }
    var address = state.address(fact.address);
    var selected = address == null ? null : address.selectionAt(commitNumber - 1).selected();
    if (selected != null && !selected.equals(fact.urn)) {
      return new Decision(
          PolicyResult.REQUEST_CONSENT, "Another Fact is selected at the same Fact Address.");
    }
    return new Decision(
        PolicyResult.PROCEED, "Every validation item is supported by a deterministic check.");
  }

  static boolean deterministic(Check.Method method, boolean countDeclaredUnenforced) {
    return method == Check.Method.DECLARED_ENFORCED
        || method == Check.Method.DATA_CHECK
        || (countDeclaredUnenforced && method == Check.Method.DECLARED_UNENFORCED);
  }

  void evaluateConfirmation(FactRecord fact) {
    var policy = state.policyAt(commit.number);
    var decision =
        decideConfirmation(
            fact, policy.version().preset(), policy.version().countDeclaredUnenforced());
    var id =
        commit.append(
            new LogEntry.EvaluatePolicy(
                fact.urn,
                policy.id(),
                Target.CONFIRMATION,
                decision.result(),
                "policy:" + presetName(policy.version().preset()),
                decision.reason()));
    if (decision.result() == PolicyResult.PROCEED) {
      confirm(fact, ConfirmationPath.MACHINE, id);
    }
  }

  private Decision decideRetirement(
      FactRecord fact, Preset preset, boolean countDeclaredUnenforced) {
    switch (preset) {
      case FULL_AUTO -> {
        return new Decision(
            PolicyResult.PROCEED, "The full-auto preset retires on every proposal.");
      }
      case MANUAL -> {
        return new Decision(
            PolicyResult.REQUEST_CONSENT,
            "The manual preset asks for consent to every retirement.");
      }
      case AUTO -> {}
    }
    var checks = fact.retirementChecks == null ? List.<Check>of() : fact.retirementChecks;
    if (checks.stream().anyMatch(check -> check.result() == Check.Result.REFUTED)) {
      return new Decision(
          PolicyResult.REQUEST_CONSENT, "A check refutes the reason for retirement.");
    }
    var supported =
        checks.stream()
            .anyMatch(
                check ->
                    check.result() == Check.Result.SUPPORTED
                        && deterministic(check.method(), countDeclaredUnenforced));
    return supported
        ? new Decision(
            PolicyResult.PROCEED,
            "The reason for retirement is supported by a deterministic check.")
        : new Decision(
            PolicyResult.REQUEST_CONSENT,
            "The reason for retirement is not supported by a deterministic check.");
  }

  private void evaluateRetirement(FactRecord fact) {
    var policy = state.policyAt(commit.number);
    var decision =
        decideRetirement(
            fact, policy.version().preset(), policy.version().countDeclaredUnenforced());
    var id =
        commit.append(
            new LogEntry.EvaluatePolicy(
                fact.urn,
                policy.id(),
                Target.RETIREMENT,
                decision.result(),
                "policy:" + presetName(policy.version().preset()),
                decision.reason()));
    if (decision.result() == PolicyResult.PROCEED) {
      if (fact.state == FactState.CONFIRMED) {
        retire(fact, id, null);
      } else {
        reject(fact, id, null);
      }
    }
  }

  static String presetName(Preset preset) {
    return switch (preset) {
      case FULL_AUTO -> "full-auto";
      case AUTO -> "auto";
      case MANUAL -> "manual";
    };
  }

  // Transitions and cascades

  private void confirm(FactRecord fact, ConfirmationPath path, String basis) {
    commit.append(new LogEntry.Confirm(fact.urn, path, basis));
    for (var dependentUrn : List.copyOf(state.dependentsOf(fact.urn))) {
      var dependent = state.requireFact(dependentUrn);
      if (dependent.state == FactState.CANDIDATE
          && dependent.factDependencies().contains(fact.urn)
          && state.dependenciesConfirmed(dependent)) {
        evaluateConfirmation(dependent);
      }
    }
  }

  private void reject(FactRecord fact, @Nullable String basis, @Nullable String cause) {
    commit.append(new LogEntry.Reject(fact.urn, basis, cause));
    cascadeEnded(fact);
  }

  private void retire(FactRecord fact, @Nullable String basis, @Nullable String cause) {
    commit.append(new LogEntry.Retire(fact.urn, basis, cause));
    cascadeEnded(fact);
  }

  private void cascadeEnded(FactRecord fact) {
    for (var dependentUrn : List.copyOf(state.dependentsOf(fact.urn))) {
      var dependent = state.requireFact(dependentUrn);
      if (dependent.state == FactState.CANDIDATE
          && dependent.factDependencies().contains(fact.urn)) {
        reject(dependent, null, fact.urn);
      } else if (dependent.state == FactState.CONFIRMED
          && dependent.confirmedDependencies.stream()
              .anyMatch(
                  dependency ->
                      dependency instanceof DependencyRef.OnFact onFact
                          && onFact.fact().equals(fact.urn))) {
        retire(dependent, null, fact.urn);
      }
    }
  }

  // Retirement proposals

  Acceptance proposeRetirement(RetirementProposal proposal, Provenance provenance) {
    var fact = state.factRecord(proposal.fact());
    if (fact == null) {
      return new Acceptance(proposal.fact(), false, "The Fact is unknown.");
    }
    if (fact.state != FactState.CANDIDATE && fact.state != FactState.CONFIRMED) {
      return new Acceptance(proposal.fact(), false, "The Fact is already rejected or retired.");
    }
    if (fact.openRetirementProposal != null) {
      return new Acceptance(proposal.fact(), false, "A retirement proposal is already open.");
    }
    var dependencies = new ArrayList<DependencyRef>();
    for (var dependency : proposal.dependencies()) {
      var resolved = resolve(dependency);
      if (resolved != null) {
        dependencies.add(resolved);
      }
    }
    commit.append(
        new LogEntry.ProposeRetire(
            fact.urn, proposal.reason(), dependencies, proposal.checks(), provenance));
    evaluateRetirement(fact);
    return new Acceptance(fact.urn, true, null);
  }

  // Answers

  /** The transition one answer caused, or why it caused none. */
  record AnswerOutcome(
      String answerEvent, String transition, @Nullable String reasonForNoTransition) {}

  AnswerOutcome answer(
      FactRecord fact,
      Target target,
      AnswerType type,
      String principal,
      String authorityCheck,
      String wording,
      @Nullable String presentation) {
    var before = fact.state;
    var id =
        commit.append(
            new LogEntry.Answer(
                fact.urn, target, type, principal, authorityCheck, wording, presentation));
    if (target == Target.CONFIRMATION) {
      if (before != FactState.CANDIDATE) {
        return new AnswerOutcome(id, "none", "already-judged");
      }
      switch (type) {
        case CONSENT -> {
          if (!state.dependenciesConfirmed(fact)) {
            return new AnswerOutcome(id, "none", "dependencies-not-confirmed");
          }
          confirm(fact, ConfirmationPath.HUMAN, id);
          return new AnswerOutcome(id, "confirmed", null);
        }
        case REJECTION, CORRECTION -> {
          reject(fact, id, null);
          return new AnswerOutcome(id, "rejected", null);
        }
        case UNKNOWN -> {
          return new AnswerOutcome(id, "none", "unknown");
        }
      }
    }
    switch (type) {
      case CONSENT -> {
        if (before == FactState.CONFIRMED) {
          retire(fact, id, null);
          return new AnswerOutcome(id, "retired", null);
        }
        reject(fact, id, null);
        return new AnswerOutcome(id, "rejected", null);
      }
      case REJECTION, CORRECTION -> {
        return new AnswerOutcome(id, "none", "retirement-declined");
      }
      case UNKNOWN -> {
        return new AnswerOutcome(id, "none", "unknown");
      }
    }
    throw new IllegalStateException("Unreachable");
  }

  /** Re-evaluates every candidate that can be evaluated, after the user policy version changed. */
  List<String> reevaluate() {
    var evaluated = new ArrayList<String>();
    for (var fact : List.copyOf(state.factRecords().values())) {
      if (fact.state == FactState.CANDIDATE && state.dependenciesConfirmed(fact)) {
        evaluateConfirmation(fact);
        evaluated.add(fact.urn);
      }
    }
    return evaluated;
  }

  static Set<String> transitionKinds() {
    return Set.of("confirmed", "rejected", "retired");
  }
}
