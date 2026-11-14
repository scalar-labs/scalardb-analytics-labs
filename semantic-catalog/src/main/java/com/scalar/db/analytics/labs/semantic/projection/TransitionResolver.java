/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.projection;

import com.scalar.db.analytics.labs.semantic.model.LogPosition;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.LogRecord.Basis;
import com.scalar.db.analytics.labs.semantic.model.LogRecord.FactEvent;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.AnswerType;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ConfirmationPath;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.PolicyResult;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Transition;
import com.scalar.db.analytics.labs.semantic.policy.PolicyEvaluator;
import com.scalar.db.analytics.labs.semantic.policy.PolicyEvaluator.Decision;
import com.scalar.db.analytics.labs.semantic.projection.Projection.FactSlice;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the transitions a commit causes, to a fixed point, inside the commit (see {@code
 * docs/semantic-catalog/construction.md}, "Commits and cascades").
 *
 * <p>After the records of a request are applied, it looks at every Fact they touched and, in turn,
 * at the Facts depending on each Fact that moved. For each it appends at most one record at a time
 * and applies it with the same validating {@link Projector}, until no Fact moves:
 *
 * <ul>
 *   <li>a candidate whose premise is rejected or retired is rejected, and a confirmed Fact whose
 *       premise is retired is retired;
 *   <li>a candidate that a retirement proposal of this commit targets is rejected;
 *   <li>a rejection or correction answer rejects its candidate, and a consent answer confirms it
 *       once every premise is confirmed; a consent to a retirement proposal retires the Fact;
 *   <li>an evaluable candidate or an open retirement proposal without a standing evaluation is
 *       evaluated inline by {@link PolicyEvaluator}, and an adoption takes effect once every
 *       premise is confirmed.
 * </ul>
 *
 * <p>What inline evaluation leaves undecided under {@code auto} waits for the policy evaluation
 * model, which records its evaluation in a later commit.
 */
public final class TransitionResolver {
  /** The party recorded on inline evaluations. */
  public static final String INLINE_EVALUATION = "inline-evaluation";

  private final String domain;
  private final Projection projection;
  private final @Nullable Projector projector;
  private final long commit;
  private int nextOrdinal;
  private final Map<String, LogPosition> proposalsAgainstCandidates = new HashMap<>();
  private final List<PositionedRecord> appended = new ArrayList<>();
  private final Set<String> undecided = new LinkedHashSet<>();

  /**
   * What the transitions of one commit did.
   *
   * @param records the records appended after the request's, in log order
   * @param confirmed the Facts the appended records confirmed
   * @param rejected the Facts the appended records rejected
   * @param retired the Facts the appended records retired
   * @param awaitingModel the Facts inline evaluation left undecided, which wait for the policy
   *     evaluation model
   */
  public record Outcome(
      List<PositionedRecord> records,
      List<String> confirmed,
      List<String> rejected,
      List<String> retired,
      List<String> awaitingModel) {
    /** Nothing moved. */
    public static final Outcome NONE =
        new Outcome(List.of(), List.of(), List.of(), List.of(), List.of());

    public Outcome {
      records = List.copyOf(records);
      confirmed = List.copyOf(confirmed);
      rejected = List.copyOf(rejected);
      retired = List.copyOf(retired);
      awaitingModel = List.copyOf(awaitingModel);
    }
  }

  private TransitionResolver(
      Projection projection, @Nullable Projector projector, long commit, int next) {
    this.domain = projection.domain();
    this.projection = projection;
    this.projector = projector;
    this.commit = commit;
    this.nextOrdinal = next;
  }

  /**
   * Resolves the transitions of a commit whose request records the projector has applied.
   *
   * @param request the request's records, as applied
   */
  public static Outcome resolve(
      Projection projection, Projector projector, long commit, List<PositionedRecord> request) {
    var next = request.isEmpty() ? 1 : request.getLast().position().ordinal() + 1;
    var resolver = new TransitionResolver(projection, projector, commit, next);
    var touched = new LinkedHashSet<String>();
    for (var positioned : request) {
      if (positioned.record() instanceof FactEvent event) {
        var fact = event.fact().urnValue();
        touched.add(fact);
        if (event instanceof LogRecord.ProposeRetire) {
          resolver.proposalsAgainstCandidates.put(fact, positioned.position());
        }
        if (event instanceof LogRecord.EvaluatePolicy) {
          // An evaluation recorded by the policy evaluation model that requests consent moves no
          // Fact itself, but makes the candidates resting on this one evaluable.
          touched.addAll(resolver.followers(fact));
        }
      }
    }
    return resolver.run(touched);
  }

  /**
   * Resolves the transitions of some Facts in a commit of their own, with no request records: each
   * takes its next step, such as the inline evaluation it lacks, and the Facts depending on any
   * that move follow. A policy re-evaluation settles every candidate and every confirmed Fact with
   * an open retirement proposal: an evaluation made under an earlier user policy version no longer
   * stands, so each one without a standing answer is evaluated again under the version in force
   * (see {@code docs/semantic-catalog/construction.md}, "Choosing the confirmation path").
   */
  public static Outcome settle(
      Projection projection, Projector projector, long commit, List<String> facts) {
    return new TransitionResolver(projection, projector, commit, 1).run(new LinkedHashSet<>(facts));
  }

  /** Whether a Fact waits for the policy evaluation model, on the projection's current state. */
  public enum ModelWait {
    /**
     * Nothing about the Fact waits for an evaluation: it was judged, answered, or not evaluable.
     */
    NOT_AWAITING,
    /** It lacks a standing evaluation, but inline evaluation now decides it. */
    DECIDED_INLINE,
    /** It lacks a standing evaluation, and inline evaluation leaves it undecided. */
    AWAITING
  }

  /**
   * Whether the policy evaluation model is to evaluate a Fact: a candidate's confirmation, or a
   * confirmed Fact's open retirement proposal, with no standing evaluation or answer.
   */
  public static ModelWait modelWait(Projection projection, String fact) {
    var resolver = new TransitionResolver(projection, null, 0, 0);
    var slice = projection.fact(fact).orElse(null);
    if (slice == null || slice.record().state().isEmpty()) {
      return ModelWait.NOT_AWAITING;
    }
    var record = slice.record();
    Transition transition;
    if (record.is(FactState.CANDIDATE)) {
      var premises = resolver.premises(slice, record.factDependencies());
      if (!resolver.evaluable(premises)
          || resolver
              .currentAnswer(slice, premises)
              .filter(answer -> answer.answer().answerType() != AnswerType.UNKNOWN)
              .isPresent()
          || record
              .latestEvaluation(Transition.CONFIRM)
              .filter(e -> resolver.standingEvaluation(slice, premises, e))
              .isPresent()) {
        return ModelWait.NOT_AWAITING;
      }
      transition = Transition.CONFIRM;
    } else if (record.is(FactState.CONFIRMED) && record.openRetirementProposal().isPresent()) {
      var proposal = record.openRetirementProposal().get();
      if (record.latestAnswer(Transition.RETIRE).filter(at -> after(at, proposal)).isPresent()
          || resolver.standingRetireEvaluation(record, proposal).isPresent()) {
        return ModelWait.NOT_AWAITING;
      }
      transition = Transition.RETIRE;
    } else {
      return ModelWait.NOT_AWAITING;
    }
    return resolver.decide(slice, transition) instanceof Decision.Undecided
        ? ModelWait.AWAITING
        : ModelWait.DECIDED_INLINE;
  }

  private Outcome run(Set<String> touched) {
    var pending = new ArrayDeque<>(touched);
    var queued = new LinkedHashSet<>(touched);
    while (!pending.isEmpty()) {
      var fact = pending.poll();
      queued.remove(fact);
      var next = step(requireFact(fact));
      if (next.isEmpty()) {
        continue;
      }
      append(next.get());
      for (var follow : followers(fact)) {
        if (queued.add(follow)) {
          pending.add(follow);
        }
      }
    }
    var confirmed = new ArrayList<String>();
    var rejected = new ArrayList<String>();
    var retired = new ArrayList<String>();
    for (var positioned : appended) {
      switch (positioned.record()) {
        case LogRecord.Confirm confirm -> confirmed.add(confirm.fact().urnValue());
        case LogRecord.Reject rejection -> rejected.add(rejection.fact().urnValue());
        case LogRecord.Retire retirement -> retired.add(retirement.fact().urnValue());
        default -> {}
      }
    }
    var awaitingModel =
        undecided.stream()
            .filter(fact -> requireFact(fact).record().state().isPresent())
            .filter(fact -> stillUndecided(requireFact(fact)))
            .toList();
    return new Outcome(appended, confirmed, rejected, retired, awaitingModel);
  }

  /**
   * The Fact itself, to take its next step, and the Facts depending on it: those whose dependency
   * references in effect name it, and the confirmed Facts confirmed with a reference to it, which a
   * later acceptance may have dropped but which still cascade.
   */
  private List<String> followers(String fact) {
    var followers = new LinkedHashSet<String>();
    followers.add(fact);
    followers.addAll(projection.dependents(fact));
    followers.addAll(projection.confirmedDependents(fact));
    return List.copyOf(followers);
  }

  private void append(LogRecord record) {
    var positioned = new PositionedRecord(new LogPosition(commit, nextOrdinal++), record);
    Objects.requireNonNull(projector, "A resolver that only inspects appends nothing")
        .apply(positioned);
    appended.add(positioned);
  }

  // One step of one Fact

  private Optional<LogRecord> step(FactSlice slice) {
    var record = slice.record();
    var state = record.state();
    if (state.isEmpty()) {
      return Optional.empty();
    }
    return switch (state.get()) {
      case CANDIDATE -> candidateStep(slice);
      case CONFIRMED -> confirmedStep(slice);
      case REJECTED, RETIRED -> Optional.empty();
    };
  }

  private Optional<LogRecord> candidateStep(FactSlice slice) {
    var record = slice.record();
    var fact = Ref.urn(slice.row().fact());
    var premises = premises(slice, record.factDependencies());
    for (var premise : premises) {
      var premiseRecord = requireFact(premise).record();
      if (premiseRecord.is(FactState.REJECTED) || premiseRecord.is(FactState.RETIRED)) {
        return Optional.of(new LogRecord.Reject(fact, new Basis.Cascade(Ref.urn(premise))));
      }
    }
    var proposal = proposalsAgainstCandidates.get(slice.row().fact());
    if (proposal != null && afterJudgementStart(record, proposal)) {
      return Optional.of(new LogRecord.Reject(fact, new Basis.Event(logRecord(proposal))));
    }
    var premisesConfirmed =
        premises.stream()
            .allMatch(premise -> requireFact(premise).record().is(FactState.CONFIRMED));
    var answer = currentAnswer(slice, premises);
    if (answer.isPresent()) {
      var at = answer.get().at();
      switch (answer.get().answer().answerType()) {
        case REJECTION, CORRECTION -> {
          return Optional.of(new LogRecord.Reject(fact, new Basis.Event(logRecord(at))));
        }
        case CONSENT -> {
          // A consent given while a premise awaits consent takes effect once the premise is
          // confirmed, as an adoption pending its premises does.
          return premisesConfirmed
              ? Optional.of(
                  new LogRecord.Confirm(fact, ConfirmationPath.HUMAN_CONSENT, logRecord(at)))
              : Optional.empty();
        }
        case UNKNOWN -> {}
      }
    }
    if (!evaluable(premises)) {
      return Optional.empty();
    }
    var evaluation = record.latestEvaluation(Transition.CONFIRM);
    if (evaluation.isEmpty() || !standingEvaluation(slice, premises, evaluation.get())) {
      return evaluate(slice, Transition.CONFIRM);
    }
    if (evaluation.get().result() == PolicyResult.MACHINE_JUDGEMENT && premisesConfirmed) {
      return Optional.of(
          new LogRecord.Confirm(
              fact, ConfirmationPath.MACHINE_JUDGEMENT, logRecord(evaluation.get().at())));
    }
    return Optional.empty();
  }

  private Optional<LogRecord> confirmedStep(FactSlice slice) {
    var record = slice.record();
    var fact = Ref.urn(slice.row().fact());
    for (var premise : premises(slice, record.confirmationFactDependencies())) {
      if (requireFact(premise).record().is(FactState.RETIRED)) {
        return Optional.of(new LogRecord.Retire(fact, new Basis.Cascade(Ref.urn(premise))));
      }
    }
    var proposal = record.openRetirementProposal();
    if (proposal.isEmpty()) {
      return Optional.empty();
    }
    var answerAt = record.latestAnswer(Transition.RETIRE).filter(at -> after(at, proposal.get()));
    if (answerAt.isPresent()) {
      var answer = (LogRecord.Answer) requireEvent(slice, answerAt.get());
      if (answer.answerType() == AnswerType.CONSENT) {
        return Optional.of(new LogRecord.Retire(fact, new Basis.Event(logRecord(answerAt.get()))));
      }
      if (answer.answerType() == AnswerType.UNKNOWN) {
        return Optional.empty();
      }
    }
    var evaluation = standingRetireEvaluation(record, proposal.get());
    if (evaluation.isEmpty()) {
      return evaluate(slice, Transition.RETIRE);
    }
    if (evaluation.get().result() == PolicyResult.MACHINE_JUDGEMENT) {
      return Optional.of(
          new LogRecord.Retire(fact, new Basis.Event(logRecord(evaluation.get().at()))));
    }
    return Optional.empty();
  }

  /** Inline evaluation of a transition, or nothing when it leaves the transition undecided. */
  private Optional<LogRecord> evaluate(FactSlice slice, Transition transition) {
    var policy = policy();
    var decision = decide(slice, transition);
    var result =
        switch (decision) {
          case Decision.Adopt ignored -> PolicyResult.MACHINE_JUDGEMENT;
          case Decision.RequestConsent ignored -> PolicyResult.REQUEST_CONSENT;
          case Decision.Undecided ignored -> null;
        };
    if (result == null) {
      undecided.add(slice.row().fact());
      return Optional.empty();
    }
    undecided.remove(slice.row().fact());
    return Optional.of(
        new LogRecord.EvaluatePolicy(
            Ref.urn(slice.row().fact()),
            Ref.urn(policy.logRecord()),
            transition,
            result,
            new LogRecord.Evaluator.Inline(),
            INLINE_EVALUATION,
            decision.reason()));
  }

  private Projection.PolicyInForce policy() {
    return projection
        .policyInForce()
        .orElseThrow(
            () -> new IllegalStateException("Domain " + domain + " has no policy version"));
  }

  /** What inline evaluation decides about a transition on the current state. */
  private Decision decide(FactSlice slice, Transition transition) {
    var policy = policy();
    var record = slice.record();
    return switch (transition) {
      case CONFIRM ->
          PolicyEvaluator.evaluateConfirmation(
              policy.version(), record.checks(), anotherFactSelected(slice));
      case RETIRE -> {
        var proposal =
            (LogRecord.ProposeRetire)
                requireEvent(slice, record.openRetirementProposal().orElseThrow());
        yield PolicyEvaluator.evaluateRetirement(policy.version(), proposal.checks());
      }
    };
  }

  private boolean stillUndecided(FactSlice slice) {
    var record = slice.record();
    if (record.is(FactState.CANDIDATE)) {
      var premises = premises(slice, record.factDependencies());
      return evaluable(premises)
          && record
              .latestEvaluation(Transition.CONFIRM)
              .map(e -> !standingEvaluation(slice, premises, e))
              .orElse(true);
    }
    return record.is(FactState.CONFIRMED)
        && record
            .openRetirementProposal()
            .map(proposal -> standingRetireEvaluation(record, proposal).isEmpty())
            .orElse(false);
  }

  // Conditions

  /**
   * Whether a candidate is evaluable: every premise is confirmed or is a candidate whose latest
   * evaluation requested consent.
   */
  private boolean evaluable(Set<String> premises) {
    for (var premise : premises) {
      var premiseRecord = requireFact(premise).record();
      if (!premiseRecord.is(FactState.CONFIRMED)
          && !(premiseRecord.is(FactState.CANDIDATE)
              && premiseRecord.awaitingConsent(policy().logRecord()))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether an evaluation or an answer of a candidate still stands: made in its current judgement,
   * after its latest acceptance, and after the current judgement of every premise began.
   */
  private boolean standing(FactRecord record, Set<String> premises, LogPosition at) {
    if (!afterJudgementStart(record, at)
        || !record.latestAcceptance().map(accepted -> after(at, accepted)).orElse(false)) {
      return false;
    }
    for (var premise : premises) {
      if (!requireFact(premise).record().judgementStart().map(s -> after(at, s)).orElse(false)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether an evaluation of a candidate's confirmation still stands: it {@linkplain #standing
   * stands} as an answer would, and it was made under the user policy version in force. After a
   * version is set, an earlier evaluation neither takes effect nor holds the candidate back; the
   * re-evaluation, or any commit that reaches the candidate first, evaluates it again.
   */
  private boolean standingEvaluation(
      FactSlice slice, Set<String> premises, FactRecord.Evaluation evaluation) {
    return standing(slice.record(), premises, evaluation.at())
        && evaluation.under(policy().logRecord());
  }

  /**
   * The evaluation of a confirmed Fact's open retirement proposal that still stands: made after the
   * proposal and under the user policy version in force.
   */
  private Optional<FactRecord.Evaluation> standingRetireEvaluation(
      FactRecord record, LogPosition proposal) {
    return record
        .latestEvaluation(Transition.RETIRE)
        .filter(e -> after(e.at(), proposal) && e.under(policy().logRecord()));
  }

  private record PositionedAnswer(LogPosition at, LogRecord.Answer answer) {}

  /** The latest answer about the candidate's confirmation, if it still stands. */
  private Optional<PositionedAnswer> currentAnswer(FactSlice slice, Set<String> premises) {
    var record = slice.record();
    return record
        .latestAnswer(Transition.CONFIRM)
        .filter(at -> standing(record, premises, at))
        .map(at -> new PositionedAnswer(at, (LogRecord.Answer) requireEvent(slice, at)));
  }

  /** Whether another Fact is selected at the Fact Address in the previous commit's snapshot. */
  private boolean anotherFactSelected(FactSlice slice) {
    var address = projection.address(slice.row().factAddress()).orElseThrow();
    // Selections change only at the end of a commit, so the current one is the previous commit's.
    return address
        .currentSelection()
        .map(selection -> selection.selectedFact())
        .map(selected -> !selected.equals(slice.row().fact()))
        .orElse(false);
  }

  private static boolean afterJudgementStart(FactRecord record, LogPosition at) {
    return record.judgementStart().map(start -> after(at, start)).orElse(false);
  }

  private static boolean after(LogPosition at, LogPosition other) {
    return at.compareTo(other) > 0;
  }

  /** The Facts among some a Fact depends on that are premise dependencies: at another address. */
  private Set<String> premises(FactSlice slice, Set<String> dependencies) {
    var premises = new LinkedHashSet<String>();
    for (var dependency : dependencies) {
      var address = projection.factAddress(dependency).orElseThrow();
      if (!address.equals(slice.row().factAddress())) {
        premises.add(dependency);
      }
    }
    return premises;
  }

  private FactEvent requireEvent(FactSlice slice, LogPosition at) {
    return projection
        .event(slice.row().fact(), at)
        .orElseThrow(() -> new IllegalStateException("No event at " + at));
  }

  private FactSlice requireFact(String fact) {
    return projection
        .fact(fact)
        .orElseThrow(() -> new IllegalStateException("No Fact " + fact + " in the projection"));
  }

  private Ref logRecord(LogPosition at) {
    return Ref.urn(Urns.logRecord(domain, at));
  }
}
