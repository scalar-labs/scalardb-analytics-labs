/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.scalar.db.analytics.labs.semantic.model.Check;
import com.scalar.db.analytics.labs.semantic.model.Dependency;
import com.scalar.db.analytics.labs.semantic.model.FactRevision;
import com.scalar.db.analytics.labs.semantic.model.LogPosition;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.LogRecord.FactEvent;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.AnswerType;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ConfirmationPath;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.PolicyResult;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Transition;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One Fact Record: how the Semantic Catalog treats one Fact, obtained by applying the Fact's events
 * in log order (see {@code docs/semantic-catalog/record-model.md}, "Fact Events and states").
 *
 * <p>Applying an event never judges it. Whether the state accepts the event, and whether the event
 * a transition rests on is there, is checked beforehand by the {@link Projector}, which reads what
 * this record keeps for the purpose: the current judgement's start, the latest evaluation and
 * answer for each transition, the open retirement proposal, and the dependencies at the latest
 * confirmation.
 *
 * <p>The record is materialized as its {@linkplain #stored() stored form} after every commit that
 * changes it, and a write or a read at the latest point {@linkplain #restore restores} it from
 * there instead of folding the Fact's events again. The stored form always equals the result of
 * folding the Fact's events from its creation, which the replay check verifies. A read at a point
 * before the Fact's latest event {@linkplain #fold folds} the events up to that point.
 *
 * <p>The record does not tell premise dependencies from replacement dependencies, because that
 * needs the Fact Addresses of the Facts depended on; the projector classifies them.
 */
public final class FactRecord {
  private final String fact;
  private final String factAddress;
  private final JsonNode value;
  private Set<Dependency> dependencies = Set.of();
  private List<Check> checks = List.of();
  private @Nullable FactState state;
  private @Nullable LogPosition latestEvent;
  private JsonNode provenance = NullNode.getInstance();
  private Set<Dependency> confirmationDependencies = Set.of();
  private Set<Dependency> dependenciesAtLastJudgement = Set.of();
  private @Nullable LogPosition openRetirementProposal;
  private @Nullable Evaluation latestConfirmEvaluation;
  private @Nullable Evaluation latestRetireEvaluation;
  private @Nullable LogPosition latestConfirmAnswer;
  private @Nullable LogPosition latestRetireAnswer;
  private boolean unknownAnswerPending;
  private @Nullable ConfirmationPath confirmationPath;
  private @Nullable String confirmationBasis;
  private long lastConfirmedOrChecked;
  private @Nullable LogPosition judgementStart;
  private @Nullable LogPosition latestConfirmation;

  /** A policy evaluation of the Fact, at its log position. */
  public record Evaluation(LogPosition at, PolicyResult result) {}

  /**
   * The complete content of a Fact Record after at least one event, in the form it is stored. It
   * holds everything later events and their validation need, so restoring it gives the same record
   * as folding the Fact's events.
   *
   * @param latestEvent the log position of the latest event applied
   */
  public record Stored(
      String fact,
      String factAddress,
      JsonNode value,
      FactState state,
      LogPosition latestEvent,
      List<Dependency> dependencies,
      List<Check> checks,
      JsonNode provenance,
      List<Dependency> confirmationDependencies,
      List<Dependency> dependenciesAtLastJudgement,
      @Nullable LogPosition openRetirementProposal,
      @Nullable Evaluation latestConfirmEvaluation,
      @Nullable Evaluation latestRetireEvaluation,
      @Nullable LogPosition latestConfirmAnswer,
      @Nullable LogPosition latestRetireAnswer,
      boolean unknownAnswerPending,
      @Nullable ConfirmationPath confirmationPath,
      @Nullable String confirmationBasis,
      long lastConfirmedOrChecked,
      LogPosition judgementStart,
      @Nullable LogPosition latestConfirmation) {
    public Stored {
      dependencies = List.copyOf(dependencies);
      checks = List.copyOf(checks);
      confirmationDependencies = List.copyOf(confirmationDependencies);
      dependenciesAtLastJudgement = List.copyOf(dependenciesAtLastJudgement);
    }
  }

  private FactRecord(String fact, String factAddress, JsonNode value) {
    this.fact = fact;
    this.factAddress = factAddress;
    this.value = value;
  }

  /** A Fact just created by its record, before its first acceptance. */
  public static FactRecord created(String domain, LogPosition at, LogRecord.Fact record) {
    return new FactRecord(
        Urns.of(domain, Urns.FACT, at), record.address().urnValue(), record.value());
  }

  /**
   * Folds a Fact's records in log order: the record creating it first, then its Fact Events. Reads
   * use it for a point before the Fact's latest event, which the stored record no longer shows.
   *
   * @param upTo the last log position to apply, inclusive
   */
  public static FactRecord fold(String domain, List<PositionedRecord> records, LogPosition upTo) {
    if (records.isEmpty() || !(records.getFirst().record() instanceof LogRecord.Fact creation)) {
      throw new IllegalArgumentException("A Fact's records start with the record creating it");
    }
    var record = created(domain, records.getFirst().position(), creation);
    for (var positioned : records.subList(1, records.size())) {
      if (positioned.position().compareTo(upTo) > 0) {
        break;
      }
      if (!(positioned.record() instanceof FactEvent event)) {
        throw new IllegalArgumentException("Not a Fact Event: " + positioned.record().kind());
      }
      record.apply(positioned.position(), event);
    }
    return record;
  }

  /** The Fact Record a stored form holds. */
  public static FactRecord restore(Stored stored) {
    var record = new FactRecord(stored.fact(), stored.factAddress(), stored.value());
    record.state = stored.state();
    record.latestEvent = stored.latestEvent();
    record.dependencies = ordered(stored.dependencies());
    record.checks = stored.checks();
    record.provenance = stored.provenance();
    record.confirmationDependencies = ordered(stored.confirmationDependencies());
    record.dependenciesAtLastJudgement = ordered(stored.dependenciesAtLastJudgement());
    record.openRetirementProposal = stored.openRetirementProposal();
    record.latestConfirmEvaluation = stored.latestConfirmEvaluation();
    record.latestRetireEvaluation = stored.latestRetireEvaluation();
    record.latestConfirmAnswer = stored.latestConfirmAnswer();
    record.latestRetireAnswer = stored.latestRetireAnswer();
    record.unknownAnswerPending = stored.unknownAnswerPending();
    record.confirmationPath = stored.confirmationPath();
    record.confirmationBasis = stored.confirmationBasis();
    record.lastConfirmedOrChecked = stored.lastConfirmedOrChecked();
    record.judgementStart = stored.judgementStart();
    record.latestConfirmation = stored.latestConfirmation();
    return record;
  }

  /**
   * The stored form of the record.
   *
   * @return empty before the first acceptance, when there is nothing to store
   */
  public Optional<Stored> stored() {
    if (state == null || latestEvent == null || judgementStart == null) {
      return Optional.empty();
    }
    return Optional.of(
        new Stored(
            fact,
            factAddress,
            value,
            state,
            latestEvent,
            List.copyOf(dependencies),
            checks,
            provenance,
            List.copyOf(confirmationDependencies),
            List.copyOf(dependenciesAtLastJudgement),
            openRetirementProposal,
            latestConfirmEvaluation,
            latestRetireEvaluation,
            latestConfirmAnswer,
            latestRetireAnswer,
            unknownAnswerPending,
            confirmationPath,
            confirmationBasis,
            lastConfirmedOrChecked,
            judgementStart,
            latestConfirmation));
  }

  private static Set<Dependency> ordered(List<Dependency> dependencies) {
    return Collections.unmodifiableSet(new LinkedHashSet<>(dependencies));
  }

  /** Applies the Fact's next event. */
  public void apply(LogPosition at, FactEvent event) {
    latestEvent = at;
    switch (event) {
      case LogRecord.Accept accept -> {
        if (state == null || state == FactState.REJECTED || state == FactState.RETIRED) {
          state = FactState.CANDIDATE;
          judgementStart = at;
        }
        if (bringsNewDependency(accept.dependencies())) {
          unknownAnswerPending = false;
        }
        dependencies = Collections.unmodifiableSet(new LinkedHashSet<>(accept.dependencies()));
        checks = accept.checks();
        provenance = accept.provenance();
        lastConfirmedOrChecked = at.commit();
      }
      case LogRecord.ProposeRetire ignored -> {
        if (state == FactState.CONFIRMED) {
          openRetirementProposal = at;
        }
      }
      case LogRecord.EvaluatePolicy evaluation -> {
        var recorded = new Evaluation(at, evaluation.result());
        switch (evaluation.transition()) {
          case CONFIRM -> latestConfirmEvaluation = recorded;
          case RETIRE -> latestRetireEvaluation = recorded;
        }
      }
      case LogRecord.Answer answer -> {
        switch (answer.transition()) {
          case CONFIRM -> latestConfirmAnswer = at;
          case RETIRE -> {
            latestRetireAnswer = at;
            if (answer.answerType() == AnswerType.REJECTION
                || answer.answerType() == AnswerType.CORRECTION) {
              openRetirementProposal = null;
            }
          }
        }
        if (answer.answerType() == AnswerType.UNKNOWN) {
          unknownAnswerPending = true;
          dependenciesAtLastJudgement = dependencies;
        }
      }
      case LogRecord.Confirm confirm -> {
        state = FactState.CONFIRMED;
        confirmationDependencies = dependencies;
        confirmationPath = confirm.path();
        confirmationBasis = confirm.basis().urnValue();
        lastConfirmedOrChecked = at.commit();
        latestConfirmation = at;
      }
      case LogRecord.Reject ignored -> {
        state = FactState.REJECTED;
        closeJudgement();
      }
      case LogRecord.Retire ignored -> {
        state = FactState.RETIRED;
        closeJudgement();
      }
    }
  }

  private void closeJudgement() {
    dependenciesAtLastJudgement = dependencies;
    openRetirementProposal = null;
  }

  public String fact() {
    return fact;
  }

  public String factAddress() {
    return factAddress;
  }

  /** The state, or empty before the first acceptance. */
  public Optional<FactState> state() {
    return Optional.ofNullable(state);
  }

  public boolean is(FactState expected) {
    return state == expected;
  }

  /** The dependency references in effect: those the latest acceptance stated. */
  public List<Dependency> dependencies() {
    return List.copyOf(dependencies);
  }

  /**
   * The dependency references at the latest confirmation: the ones that count for selection and
   * cascading. Later acceptances, which have not been through that judgement, do not change them.
   */
  public Set<Dependency> confirmationDependencies() {
    return confirmationDependencies;
  }

  /** The Facts the dependency references in effect name. */
  public Set<String> factDependencies() {
    return factsOf(dependencies);
  }

  /** The Facts the dependency references at the latest confirmation name. */
  public Set<String> confirmationFactDependencies() {
    return factsOf(confirmationDependencies);
  }

  private static Set<String> factsOf(Set<Dependency> references) {
    var facts = new LinkedHashSet<String>();
    for (var reference : references) {
      if (reference instanceof Dependency.OnFact onFact) {
        facts.add(onFact.fact().urnValue());
      }
    }
    return facts;
  }

  /**
   * Whether references bring a new dependency: one absent from the Fact Revision at the latest
   * rejection, retirement, or unknown answer.
   */
  public boolean bringsNewDependency(List<Dependency> references) {
    return references.stream()
        .anyMatch(reference -> !dependenciesAtLastJudgement.contains(reference));
  }

  /**
   * Where the current judgement of a candidate began: the acceptance that made it a candidate, from
   * no state, {@code rejected}, or {@code retired}. Evaluations and answers before it belong to an
   * earlier judgement.
   */
  public Optional<LogPosition> judgementStart() {
    return Optional.ofNullable(judgementStart);
  }

  /** The log position of the latest confirmation. */
  public Optional<LogPosition> latestConfirmation() {
    return Optional.ofNullable(latestConfirmation);
  }

  /** The retirement proposal against the confirmed Fact that no retirement or answer ended yet. */
  public Optional<LogPosition> openRetirementProposal() {
    return Optional.ofNullable(openRetirementProposal);
  }

  /** The latest policy evaluation of a transition. */
  public Optional<Evaluation> latestEvaluation(Transition transition) {
    return Optional.ofNullable(
        switch (transition) {
          case CONFIRM -> latestConfirmEvaluation;
          case RETIRE -> latestRetireEvaluation;
        });
  }

  /** The latest answer about a transition. */
  public Optional<LogPosition> latestAnswer(Transition transition) {
    return Optional.ofNullable(
        switch (transition) {
          case CONFIRM -> latestConfirmAnswer;
          case RETIRE -> latestRetireAnswer;
        });
  }

  /**
   * Whether an unknown answer was given and no new dependency reference arrived since; the Fact is
   * not presented in review again until one does.
   */
  public boolean unknownAnswerPending() {
    return unknownAnswerPending;
  }

  /**
   * Whether the latest policy evaluation requested consent that no answer has given yet: for a
   * candidate, about its confirmation within the current judgement; for a confirmed Fact, about its
   * open retirement proposal.
   */
  public boolean awaitingConsent() {
    if (state == FactState.CANDIDATE) {
      return requestsConsent(latestConfirmEvaluation, latestConfirmAnswer, judgementStart);
    }
    if (state == FactState.CONFIRMED && openRetirementProposal != null) {
      return requestsConsent(latestRetireEvaluation, latestRetireAnswer, openRetirementProposal);
    }
    return false;
  }

  private static boolean requestsConsent(
      @Nullable Evaluation evaluation, @Nullable LogPosition answer, @Nullable LogPosition since) {
    return evaluation != null
        && evaluation.result() == PolicyResult.REQUEST_CONSENT
        && (since == null || evaluation.at().compareTo(since) > 0)
        && (answer == null || answer.compareTo(evaluation.at()) < 0);
  }

  /**
   * How the Fact is treated now: its state and what review lists filter on.
   *
   * @throws IllegalStateException before the first acceptance
   */
  public MaterializedRow.Treatment treatment() {
    if (state == null) {
      throw new IllegalStateException("Fact " + fact + " is not accepted yet");
    }
    return new MaterializedRow.Treatment(
        state,
        awaitingConsent(),
        openRetirementProposal == null
            ? null
            : Urns.logRecord(Urns.domain(fact), openRetirementProposal),
        unknownAnswerPending);
  }

  /** The Fact Revision the latest applied event produced, or empty before the first event. */
  public Optional<FactRevision> revision() {
    if (latestEvent == null || state == null) {
      return Optional.empty();
    }
    return Optional.of(
        new FactRevision(
            Urns.factRevision(fact, latestEvent),
            fact,
            factAddress,
            value,
            state,
            latestEvent,
            List.copyOf(dependencies),
            List.copyOf(checks),
            provenance,
            confirmationPath,
            confirmationBasis,
            lastConfirmedOrChecked));
  }
}
