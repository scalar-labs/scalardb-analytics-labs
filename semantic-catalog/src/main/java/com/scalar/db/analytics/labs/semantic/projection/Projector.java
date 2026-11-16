/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.projection;

import com.scalar.db.analytics.labs.semantic.model.Check;
import com.scalar.db.analytics.labs.semantic.model.CommitRejectedException;
import com.scalar.db.analytics.labs.semantic.model.Dependency;
import com.scalar.db.analytics.labs.semantic.model.ElementIdentity;
import com.scalar.db.analytics.labs.semantic.model.IdentityKey;
import com.scalar.db.analytics.labs.semantic.model.LogPosition;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.LogRecord.Basis;
import com.scalar.db.analytics.labs.semantic.model.LogRecord.FactEvent;
import com.scalar.db.analytics.labs.semantic.model.LogRecordJson;
import com.scalar.db.analytics.labs.semantic.model.PhysicalCatalogReference;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.SemanticCatalogException;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.UsageFeedback;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.AnswerType;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ConfirmationPath;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.InputKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.PolicyResult;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Transition;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.ElementRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.FactAddressRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.FactRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.InputResourceRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.PresentablePeriodRow;
import com.scalar.db.analytics.labs.semantic.projection.Projection.AddressSlice;
import com.scalar.db.analytics.labs.semantic.projection.Projection.FactSlice;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Applies log records to a {@link Projection}, one commit at a time, deriving the materialized rows
 * from them.
 *
 * <p>Writing a commit and replaying the log use the same projector, so the materialized records are
 * derived by the same rules on both paths and the replay check compares like with like. A
 * {@linkplain #validating validating} projector, used when writing a commit, first checks each
 * record against what the earlier records left: that every record it refers to exists and has the
 * kind the reference needs, that the Fact Record's state accepts the Fact Event, that the event a
 * transition rests on is there and belongs to the current judgement, and that dependencies form no
 * cycle among Facts. At the {@linkplain #end() end} of the commit it also checks that new elements
 * and Facts were accepted, and that no confirmed Fact is left with a premise retired in the commit.
 * A {@linkplain #replaying replaying} projector applies records already in the log and checks
 * nothing.
 *
 * <p>The Fact's current Fact Record row follows its latest event. Points are commits, so
 * presentable periods and the selection at each Fact Address a confirmation, a retirement, or a
 * conflict resolution touched are derived once per commit, from the Fact Records at its end. A
 * validating projector accepts a conflict resolution only when it names exactly the eligible Facts
 * of the previous commit, and checks that it leaves the chosen Fact as the one eligible Fact.
 *
 * <p>A dependency on a Fact at another Fact Address is a premise dependency; one on a Fact at the
 * same Fact Address is a replacement dependency. Only premise dependencies make confirmation wait,
 * make earlier judgements stale, and cascade rejection and retirement.
 */
public final class Projector {
  private final Projection projection;
  private final String domain;
  private final boolean validating;
  private long commit;
  private final Set<String> touchedFacts = new LinkedHashSet<>();
  private final Map<String, ElementIdentity> newElements = new LinkedHashMap<>();
  private final Set<String> newFacts = new LinkedHashSet<>();
  private final Set<String> retiredFacts = new LinkedHashSet<>();
  private final Set<String> selectionAffected = new LinkedHashSet<>();
  private final Map<String, String> resolutions = new LinkedHashMap<>();

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
    touchedFacts.clear();
    newElements.clear();
    newFacts.clear();
    retiredFacts.clear();
    selectionAffected.clear();
    resolutions.clear();
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
      case LogRecord.Element element -> applyElement(element);
      case LogRecord.FactAddress address -> applyFactAddress(address);
      case LogRecord.Fact fact -> applyFact(fact);
      case LogRecord.InputResource resource ->
          projection.addInputResource(
              new InputResourceRow(
                  Urns.of(domain, Urns.INPUT_RESOURCE, at),
                  resource.inputKind(),
                  resource.tableIdentifier(),
                  resource.dataCheck(),
                  commit));
      case LogRecord.InputObservation observation -> applyObservation(at, observation);
      case LogRecord.ObservationEvent event -> {
        if (validating
            && projection.observationResource(event.observation().urnValue()).isEmpty()) {
          throw missing("Input Observation", event.observation());
        }
      }
      case LogRecord.PolicyVersion version ->
          projection.addPolicyVersion(Urns.logRecord(domain, at), version);
      case FactEvent event -> applyFactEvent(at, event);
      case LogRecord.ResolveConflict resolution -> applyResolution(resolution);
    }
  }

  private void applyElement(LogRecord.Element element) {
    var urn = element.issuedUrn();
    if (validating) {
      requireIssued(urn, Urns.recordKind(element.identity().kind()));
      if (projection.element(urn).isPresent()) {
        throw reject("Element URN " + urn + " is already taken");
      }
    }
    var row =
        switch (element.identity()) {
          case ElementIdentity.Unique pointed ->
              new ElementRow(urn, pointed.kind(), null, null, commit, null);
          case ElementIdentity.PhysicalCatalogObject physical -> {
            var parent = physical.parent();
            if (validating
                && parent != null
                && physical.physicalCatalogReference()
                    instanceof PhysicalCatalogReference.Column column) {
              var parentRow = requireElement(parent);
              if (parentRow.kind() != ElementKind.DATASET) {
                throw reject("The parent of a Field is a Dataset, not " + parentRow.kind());
              }
              if (!column.table().canonical().equals(parentRow.physicalCatalogReference())) {
                throw reject(
                    "A Field's column "
                        + column.canonical()
                        + " belongs to its Dataset's table "
                        + parentRow.physicalCatalogReference());
              }
            }
            yield new ElementRow(
                urn,
                physical.kind(),
                parent == null ? null : parent.urnValue(),
                physical.physicalCatalogReference().canonical(),
                commit,
                null);
          }
          case ElementIdentity.Edge edge -> {
            if (validating) {
              validateEnds(edge);
            }
            yield new ElementRow(
                urn, edge.kind(), null, null, commit, Projection.edgeIdentity(edge));
          }
        };
    projection.addElement(row);
    newElements.put(urn, element.identity());
  }

  /**
   * Requires a physical-catalog-reference Fact of a Dataset or Field to name the physical object
   * the element was created for. A Physical Catalog reference never moves: a renamed table or
   * column is a new element, so the element's name-based URN stays true.
   */
  private void requireUnmovedPhysicalCatalogReference(FactAddressRow address, LogRecord.Fact fact) {
    if (!address.predicate().equals(Vocabulary.PHYSICAL_CATALOG_REFERENCE)
        || address.scopeDescription() != null
        || address.predicateDefinition() != null) {
      return;
    }
    var element = requireElement(Ref.urn(address.element()));
    var reference = element.physicalCatalogReference();
    if (reference == null) {
      return;
    }
    var key = fact.identityKey();
    if (key == null || !key.canonical().equals(IdentityKey.choice(reference).canonical())) {
      throw reject(
          "A Physical Catalog reference never moves: "
              + element.element()
              + " refers to "
              + reference
              + ", not "
              + (key == null ? "a value without an identity key" : key.canonical()));
    }
  }

  /** Requires a URN the commit issued to be of this domain and of the record kind it creates. */
  private void requireIssued(String urn, String recordKind) {
    if (!Urns.domain(urn).equals(domain) || !Urns.kind(urn).equals(recordKind)) {
      throw reject("A " + recordKind + " of domain " + domain + " is issued URN " + urn);
    }
  }

  /**
   * Checks that an edge's ends exist and have the kinds the edge kind needs. An identifier
   * correspondence names only Fields, and each side of each hop is the Fields of one Dataset,
   * because a Field set is what identifies an object in its Dataset. So each end is the Fields of
   * one Dataset, and a hop starts from the Dataset the previous hop arrived at.
   */
  private void validateEnds(ElementIdentity.Edge edge) {
    switch (edge) {
      case ElementIdentity.IdentifierCorrespondence correspondence -> {
        for (var field : correspondence.condition().fields()) {
          var row = requireElement(field);
          if (row.kind() != ElementKind.FIELD) {
            throw reject("An identifier correspondence matches Fields, not " + row.kind());
          }
        }
        String arrivedAt = null;
        for (var hop : correspondence.condition().hops()) {
          var from = oneDataset(hop.pairs().stream().map(pair -> pair.from().field()).toList());
          var to = oneDataset(hop.pairs().stream().map(pair -> pair.to().field()).toList());
          if (arrivedAt != null && !arrivedAt.equals(from)) {
            throw reject(
                "Each hop of a matching condition starts from the Dataset the previous hop arrived"
                    + " at");
          }
          arrivedAt = to;
        }
      }
      case ElementIdentity.ConceptRelationship relationship -> {
        for (var end : List.of(relationship.from(), relationship.to())) {
          var row = requireElement(end);
          if (row.kind() != ElementKind.CONCEPT) {
            throw reject("A concept relationship relates concepts, not " + row.kind());
          }
        }
      }
    }
  }

  /** The Dataset of a side's Fields, which must all belong to that one Dataset. */
  private String oneDataset(List<Ref> fields) {
    var datasets = fields.stream().map(field -> requireElement(field).parent()).distinct().toList();
    if (datasets.size() != 1 || datasets.getFirst() == null) {
      throw reject(
          "Each end and each step of an identifier correspondence is the Fields of one Dataset");
    }
    return datasets.getFirst();
  }

  private void applyFactAddress(LogRecord.FactAddress address) {
    var urn = address.issuedUrn();
    if (validating) {
      requireElement(address.element());
      if (address.predicate().isBlank()) {
        throw reject("A Fact Address names a predicate");
      }
      requireIssued(urn, Urns.FACT_ADDRESS);
      if (projection.address(urn).isPresent()) {
        throw reject("Fact Address URN " + urn + " is already taken");
      }
    }
    var scope = address.scope();
    var row =
        new FactAddressRow(
            urn,
            address.element().urnValue(),
            address.predicate(),
            scope.description(),
            scope.entities().stream().map(Ref::urnValue).toList(),
            address.predicateDefinition(),
            commit);
    projection.addAddress(new AddressSlice(row, List.of(), List.of(), List.of()));
  }

  private void applyFact(LogRecord.Fact fact) {
    var urn = fact.issuedUrn();
    var address = requireAddress(fact.address());
    if (validating) {
      requireIssued(urn, Urns.FACT);
      if (projection.fact(urn).isPresent()) {
        throw reject("Fact URN " + urn + " is already taken");
      }
      requireUnmovedPhysicalCatalogReference(address.row(), fact);
    }
    var key = fact.identityKey();
    var row =
        new FactRow(urn, address.row().factAddress(), key == null ? null : key.canonical(), commit);
    projection.addFact(new FactSlice(row, FactRecord.created(fact), List.of()));
    address.facts.add(row.fact());
    newFacts.add(row.fact());
  }

  private void applyObservation(LogPosition at, LogRecord.InputObservation observation) {
    var resource = observation.inputResource().urnValue();
    if (validating) {
      var resourceRow = requireInputResource(observation.inputResource());
      if (resourceRow.kind() == InputKind.USAGE_FEEDBACK) {
        validateFeedback(observation);
      }
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

  /**
   * Checks the observation of usage feedback: its content is {@link UsageFeedback}, its related
   * Facts exist, and it is the resource's only observation, because feedback is never revised.
   */
  private void validateFeedback(LogRecord.InputObservation observation) {
    if (observation.previous() != null) {
      throw reject("Usage feedback is never revised; register new feedback instead");
    }
    var malformed =
        "Usage feedback has content, a session purpose, and a summary of its session's context";
    var content = observation.content();
    if (content == null || !content.isObject()) {
      throw reject(malformed);
    }
    UsageFeedback feedback;
    try {
      feedback = LogRecordJson.fromStored(content, UsageFeedback.class);
    } catch (SemanticCatalogException e) {
      throw reject(malformed + ": " + e.getMessage());
    }
    for (var fact : feedback.relatedFacts()) {
      requireFact(fact);
    }
  }

  private void applyFactEvent(LogPosition at, FactEvent event) {
    var slice = requireFact(event.fact());
    var fact = slice.row().fact();
    if (validating) {
      validate(slice, event);
    }
    slice.record().apply(at, event);
    slice.events.put(at, event);
    touchedFacts.add(fact);
    if (event instanceof LogRecord.Confirm || event instanceof LogRecord.Retire) {
      selectionAffected.add(slice.row().factAddress());
    }
    if (event instanceof LogRecord.Retire) {
      retiredFacts.add(fact);
    }
  }

  private void applyResolution(LogRecord.ResolveConflict resolution) {
    var address = requireAddress(resolution.factAddress());
    var urn = address.row().factAddress();
    var chosen = resolution.chosen().urnValue();
    var passedOver = resolution.passedOver().stream().map(Ref::urnValue).toList();
    if (validating) {
      if (passedOver.isEmpty() || passedOver.contains(chosen)) {
        throw reject("A conflict resolution chooses one Fact and passes over at least one other");
      }
      // Selections change only at the end of a commit, so the current one is the previous commit's.
      var eligible =
          address
              .currentSelection()
              .map(MaterializedRow.AddressSelectionRow::eligibleFacts)
              .orElse(List.of());
      var named = new HashSet<>(passedOver);
      named.add(chosen);
      if (eligible.size() < 2 || !named.equals(new HashSet<>(eligible))) {
        throw reject(
            "A conflict resolution names exactly the eligible Facts of a conflicting Fact Address,"
                + " and "
                + urn
                + " has eligible Facts "
                + eligible);
      }
      resolutions.put(urn, chosen);
    }
    address.resolutions.add(new SelectionRule.Resolution(chosen, passedOver, commit));
    selectionAffected.add(urn);
  }

  // Validation of Fact Events

  private void validate(FactSlice slice, FactEvent event) {
    var record = slice.record();
    var state = record.state();
    var accepted =
        switch (state.orElse(null)) {
          case null -> event instanceof LogRecord.Accept;
          case CANDIDATE -> !(event instanceof LogRecord.Retire);
          case CONFIRMED ->
              !(event instanceof LogRecord.Confirm || event instanceof LogRecord.Reject);
          case REJECTED, RETIRED ->
              event instanceof LogRecord.Accept || event instanceof LogRecord.Answer;
        };
    if (!accepted) {
      throw reject(
          "Fact "
              + slice.row().fact()
              + (state.isPresent() ? " in state " + lower(state.get()) : " before its acceptance")
              + " does not accept "
              + event.kind());
    }
    switch (event) {
      case LogRecord.Accept accept -> {
        checkAcceptance(slice, accept);
        if ((record.is(FactState.REJECTED) || record.is(FactState.RETIRED))
            && !record.bringsNewDependency(accept.dependencies())) {
          throw reject(
              "An acceptance of "
                  + lower(state.orElseThrow())
                  + " Fact "
                  + slice.row().fact()
                  + " brings no new dependency reference");
        }
      }
      case LogRecord.ProposeRetire proposal -> checkReferences(proposal.checks());
      case LogRecord.EvaluatePolicy evaluation -> {
        if (!projection.isPolicyVersion(evaluation.policyVersion().urnValue())) {
          throw missing("user policy version", evaluation.policyVersion());
        }
        if (evaluation.transition() == Transition.CONFIRM) {
          if (!record.is(FactState.CANDIDATE)) {
            throw reject("Only a candidate is evaluated for confirmation");
          }
          requireEvaluable(slice);
        }
        if (evaluation.transition() == Transition.RETIRE
            && record.openRetirementProposal().isEmpty()) {
          throw reject("A retirement is evaluated only for an open retirement proposal");
        }
      }
      case LogRecord.Answer ignored -> {}
      case LogRecord.Confirm confirm -> validateConfirm(slice, confirm);
      case LogRecord.Reject rejection -> validateReject(slice, rejection);
      case LogRecord.Retire retirement -> validateRetire(slice, retirement);
    }
  }

  /** The log record of the user policy version in force. */
  private String policyInForce() {
    return projection
        .policyInForce()
        .orElseThrow(() -> reject("Domain " + domain + " has no user policy version"))
        .logRecord();
  }

  /**
   * Requires every premise dependency of a candidate to be confirmed: a candidate is neither
   * evaluated for confirmation nor confirmed before then.
   */
  private void requirePremisesConfirmed(FactSlice slice) {
    for (var premise : premises(slice, slice.record().factDependencies())) {
      if (!requireFact(Ref.urn(premise)).record().is(FactState.CONFIRMED)) {
        throw reject(
            "Fact "
                + slice.row().fact()
                + " has premise dependency "
                + premise
                + ", which is not confirmed");
      }
    }
  }

  /**
   * Requires a candidate to be evaluable: every premise dependency is confirmed or is a candidate
   * whose latest evaluation requested consent, so a candidate and the premises it waits on can
   * reach the deciding party together.
   */
  private void requireEvaluable(FactSlice slice) {
    for (var premise : premises(slice, slice.record().factDependencies())) {
      var premiseRecord = requireFact(Ref.urn(premise)).record();
      if (!premiseRecord.is(FactState.CONFIRMED)
          && !(premiseRecord.is(FactState.CANDIDATE)
              && premiseRecord.awaitingConsent(policyInForce()))) {
        throw reject(
            "Fact "
                + slice.row().fact()
                + " has premise dependency "
                + premise
                + ", which is neither confirmed nor awaiting consent");
      }
    }
  }

  private void validateConfirm(FactSlice slice, LogRecord.Confirm confirm) {
    var record = slice.record();
    requirePremisesConfirmed(slice);
    var basisAt = basisPosition(confirm.basis());
    var basis = requireEvent(slice, confirm.basis());
    var rests =
        switch (confirm.path()) {
          case MACHINE_JUDGEMENT ->
              basis instanceof LogRecord.EvaluatePolicy evaluation
                  && evaluation.transition() == Transition.CONFIRM
                  && evaluation.result() == PolicyResult.MACHINE_JUDGEMENT
                  && record
                      .latestEvaluation(Transition.CONFIRM)
                      .map(latest -> latest.at().equals(basisAt))
                      .orElse(false);
          case HUMAN_CONSENT ->
              basis instanceof LogRecord.Answer answer
                  && answer.transition() == Transition.CONFIRM
                  && answer.answerType() == AnswerType.CONSENT
                  && record.latestAnswer(Transition.CONFIRM).map(basisAt::equals).orElse(false);
        };
    if (!rests) {
      throw reject(
          "A confirmation by "
              + lower(confirm.path())
              + " rests on the latest "
              + (confirm.path() == ConfirmationPath.MACHINE_JUDGEMENT
                  ? "policy evaluation, choosing machine judgement,"
                  : "answer, a consent,")
              + " of the same Fact");
    }
    requireCurrentJudgement(slice, basisAt);
  }

  /**
   * Requires a judgement to belong to the candidate's current judgement: made after the acceptance
   * that made it a candidate, and after the current judgement of every premise dependency began. A
   * judgement made while a premise awaited consent therefore stands once the premise is confirmed,
   * while a premise that has since returned to {@code candidate} starts a new judgement and makes
   * its dependents' earlier judgements stale.
   */
  private void requireCurrentJudgement(FactSlice slice, LogPosition basisAt) {
    var record = slice.record();
    if (!record.judgementStart().map(start -> basisAt.compareTo(start) > 0).orElse(false)) {
      throw reject(
          "Basis "
              + Urns.logRecord(domain, basisAt)
              + " belongs to an earlier judgement of Fact "
              + slice.row().fact());
    }
    for (var premise : premises(slice, record.factDependencies())) {
      var premiseRecord = requireFact(Ref.urn(premise)).record();
      var madeAfter =
          premiseRecord.judgementStart().map(start -> basisAt.compareTo(start) > 0).orElse(false);
      if (!madeAfter) {
        throw reject(
            "Basis "
                + Urns.logRecord(domain, basisAt)
                + " was made before the current judgement of Fact "
                + premise
                + ", a premise dependency of "
                + slice.row().fact()
                + ", began");
      }
    }
  }

  private void validateReject(FactSlice slice, LogRecord.Reject rejection) {
    var record = slice.record();
    switch (rejection.basis()) {
      case Basis.Event onEvent -> {
        var basisAt = basisPosition(onEvent.logRecord());
        var basis = requireEvent(slice, onEvent.logRecord());
        if (basis instanceof LogRecord.ProposeRetire) {
          if (!record.judgementStart().map(start -> basisAt.compareTo(start) > 0).orElse(false)) {
            throw reject(
                "A rejection rests on a retirement proposal made against the current candidate");
          }
        } else if (basis instanceof LogRecord.Answer answer
            && answer.transition() == Transition.CONFIRM
            && (answer.answerType() == AnswerType.REJECTION
                || answer.answerType() == AnswerType.CORRECTION)) {
          requireCurrentJudgement(slice, basisAt);
        } else {
          throw reject(
              "A rejection rests on a rejection or correction answer or a retirement proposal");
        }
      }
      case Basis.Cascade cascade -> {
        var cause = cascade.fact().urnValue();
        var causeRecord = requireFact(cascade.fact()).record();
        if (!premises(slice, record.factDependencies()).contains(cause)
            || !(causeRecord.is(FactState.REJECTED) || causeRecord.is(FactState.RETIRED))) {
          throw reject(
              "A cascaded rejection rests on a rejected or retired premise dependency of the"
                  + " target");
        }
      }
    }
  }

  private void validateRetire(FactSlice slice, LogRecord.Retire retirement) {
    var record = slice.record();
    switch (retirement.basis()) {
      case Basis.Event onEvent -> {
        var proposal =
            record
                .openRetirementProposal()
                .orElseThrow(() -> reject("A retirement requires an open retirement proposal"));
        var basisAt = basisPosition(onEvent.logRecord());
        var basis = requireEvent(slice, onEvent.logRecord());
        var rests =
            basisAt.compareTo(proposal) > 0
                && (basis instanceof LogRecord.EvaluatePolicy evaluation
                        && evaluation.transition() == Transition.RETIRE
                        && evaluation.result() == PolicyResult.MACHINE_JUDGEMENT
                        && record
                            .latestEvaluation(Transition.RETIRE)
                            .map(latest -> latest.at().equals(basisAt))
                            .orElse(false)
                    || basis instanceof LogRecord.Answer answer
                        && answer.transition() == Transition.RETIRE
                        && answer.answerType() == AnswerType.CONSENT);
        if (!rests) {
          throw reject(
              "A retirement rests on a machine-judgement evaluation or a consent answer to the"
                  + " open retirement proposal");
        }
      }
      case Basis.Cascade cascade -> {
        var cause = cascade.fact().urnValue();
        if (!premises(slice, record.confirmationFactDependencies()).contains(cause)
            || !requireFact(cascade.fact()).record().is(FactState.RETIRED)) {
          throw reject(
              "A cascaded retirement rests on a retired premise dependency the target was"
                  + " confirmed with");
        }
      }
    }
  }

  /**
   * Checks the dependency set an acceptance states and its validation result: every reference
   * exists, no Fact depends on itself or forms a cycle, and every Input Observation a check
   * references is among the stated dependencies.
   */
  private void checkAcceptance(FactSlice slice, LogRecord.Accept accept) {
    var fact = slice.row().fact();
    var observations = new HashSet<String>();
    for (var dependency : accept.dependencies()) {
      switch (dependency) {
        case Dependency.OnFact onFact -> {
          var target = onFact.fact().urnValue();
          if (target.equals(fact)) {
            throw reject("Fact " + fact + " depends on itself");
          }
          requireRevision(onFact.revision(), target);
          if (reaches(target, fact)) {
            throw reject("A dependency of Fact " + fact + " on Fact " + target + " forms a cycle");
          }
        }
        case Dependency.OnInput onInput -> {
          var resource = requireInputResource(onInput.inputResource()).inputResource();
          var observed = projection.observationResource(onInput.observation().urnValue());
          if (!observed.map(resource::equals).orElse(false)) {
            throw missing("Input Observation of " + resource, onInput.observation());
          }
          observations.add(onInput.observation().urnValue());
        }
      }
    }
    for (var check : accept.checks()) {
      var reference = check.reference();
      if (reference != null
          && Urns.kind(reference.urnValue()).equals(Urns.INPUT_OBSERVATION)
          && !observations.contains(reference.urnValue())) {
        throw reject(
            "A check references Input Observation "
                + reference.urnValue()
                + ", not a dependency the acceptance states");
      }
    }
    checkReferences(accept.checks());
  }

  /** Requires what each check references to be an existing Input Observation or Fact Revision. */
  private void checkReferences(List<Check> checks) {
    for (var check : checks) {
      var reference = check.reference();
      if (reference == null) {
        continue;
      }
      var urn = reference.urnValue();
      switch (Urns.kind(urn)) {
        case Urns.INPUT_OBSERVATION -> {
          if (projection.observationResource(urn).isEmpty()) {
            throw missing("Input Observation", reference);
          }
        }
        case Urns.FACT_REVISION -> requireRevision(reference, Urns.revisionFact(urn));
        default ->
            throw reject("A check references an Input Observation or a Fact Revision: " + urn);
      }
    }
  }

  private void requireRevision(Ref revision, String fact) {
    var urn = revision.urnValue();
    if (!Urns.kind(urn).equals(Urns.FACT_REVISION)
        || !Urns.revisionFact(urn).equals(fact)
        || projection.event(fact, Urns.revisionEvent(urn)).isEmpty()) {
      throw missing("Fact Revision of " + fact, revision);
    }
  }

  /**
   * Whether a Fact already depends, directly or through other Facts, on the given Fact, following
   * dependency references of any kind. The search walks backward from {@code dependedOn} through
   * its dependents, which the store finds by index, so it never loads the dependency history behind
   * the Fact that gains a dependency.
   */
  private boolean reaches(String dependent, String dependedOn) {
    var visited = new HashSet<String>();
    var pending = new ArrayDeque<String>();
    pending.push(dependedOn);
    while (!pending.isEmpty()) {
      var fact = pending.pop();
      if (fact.equals(dependent)) {
        return true;
      }
      if (visited.add(fact)) {
        projection.dependents(fact).forEach(pending::push);
      }
    }
    return false;
  }

  /** The Facts among some a Fact depends on that are premise dependencies: at another address. */
  private Set<String> premises(FactSlice slice, Set<String> dependencies) {
    var premises = new LinkedHashSet<String>();
    for (var dependency : dependencies) {
      var address =
          projection
              .factAddress(dependency)
              .orElseThrow(() -> missing("Fact", Ref.urn(dependency)));
      if (!address.equals(slice.row().factAddress())) {
        premises.add(dependency);
      }
    }
    return premises;
  }

  private LogPosition basisPosition(Ref basis) {
    var urn = basis.urnValue();
    if (!Urns.kind(urn).equals(Urns.LOG_RECORD)) {
      throw reject("A basis refers to a log record: " + urn);
    }
    return Urns.position(urn);
  }

  private FactEvent requireEvent(FactSlice slice, Ref basis) {
    return projection
        .event(slice.row().fact(), basisPosition(basis))
        .orElseThrow(
            () ->
                reject("Basis " + basis.urnValue() + " is not an event of " + slice.row().fact()));
  }

  // End of a commit

  /**
   * Ends the current commit: checks what holds only for the commit as a whole, then updates the
   * presentable periods of the Facts the commit's events touched. Their current Fact Records follow
   * from the events applied.
   *
   * @throws com.scalar.db.analytics.labs.semantic.model.CommitRejectedException when a validating
   *     projector finds the commit invalid
   */
  public void end() {
    if (validating) {
      checkNewRecordsAccepted();
    }
    for (var fact : touchedFacts) {
      updatePeriods(requireFact(Ref.urn(fact)));
    }
    if (validating) {
      checkRetirementsCascaded();
    }
    for (var address : selectionAffected) {
      updateSelection(requireAddress(Ref.urn(address)));
    }
    if (validating) {
      checkResolutions();
    }
  }

  /**
   * Derives the selection at a Fact Address at the end of the commit, and closes the current row
   * and adds a new one when it changed.
   */
  private void updateSelection(AddressSlice address) {
    var facts = projection.selectionFacts(address);
    var current = address.currentSelection();
    var result =
        SelectionRule.select(
            facts,
            address.resolutions,
            current.map(MaterializedRow.AddressSelectionRow::selectedFact).orElse(null));
    if (current.isPresent()
        && Objects.equals(current.get().selectedFact(), result.selected())
        && current.get().eligibleFacts().equals(result.eligible())) {
      return;
    }
    var selections = address.selections;
    current.ifPresent(open -> selections.set(selections.size() - 1, open.closedAt(commit)));
    if (result.selected() != null || !result.eligible().isEmpty()) {
      selections.add(
          new MaterializedRow.AddressSelectionRow(
              address.row().factAddress(), commit, null, result.selected(), result.eligible()));
    }
  }

  /** A conflict resolution leaves the chosen Fact as the selected and only eligible Fact. */
  private void checkResolutions() {
    for (var entry : resolutions.entrySet()) {
      var current = requireAddress(Ref.urn(entry.getKey())).currentSelection();
      var chosen = entry.getValue();
      if (current.isEmpty()
          || !chosen.equals(current.get().selectedFact())
          || !current.get().eligibleFacts().equals(List.of(chosen))) {
        throw reject(
            "The resolution choosing "
                + chosen
                + " at "
                + entry.getKey()
                + " does not leave it as the one eligible Fact");
      }
    }
  }

  /**
   * A new Fact is accepted in the commit that creates it. A new element comes with the acceptance
   * of a Fact stating its existence, and a new Dataset or Field also with that of its
   * physical-catalog-reference Fact.
   */
  private void checkNewRecordsAccepted() {
    for (var fact : newFacts) {
      if (requireFact(Ref.urn(fact)).record().state().isEmpty()) {
        throw reject("New Fact " + fact + " is accepted in the commit that creates it");
      }
    }
    if (newElements.isEmpty()) {
      return;
    }
    // The identity keys of the Facts accepted at empty-scope Fact Addresses, per element and
    // predicate, collected once so each new element is checked without scanning the commit again.
    var accepted = new HashMap<String, Set<String>>();
    for (var fact : touchedFacts) {
      var slice = requireFact(Ref.urn(fact));
      var address = requireAddress(Ref.urn(slice.row().factAddress())).row();
      if (address.matchable() && slice.record().state().isPresent()) {
        var identityKey = slice.row().identityKey();
        accepted
            .computeIfAbsent(address.element() + " " + address.predicate(), key -> new HashSet<>())
            .add(identityKey == null ? "" : identityKey);
      }
    }
    for (var entry : newElements.entrySet()) {
      var element = entry.getKey();
      if (!accepted.containsKey(element + " " + Vocabulary.EXISTENCE)) {
        throw reject(
            "New element " + element + " comes with the acceptance of a Fact of its existence");
      }
      if (entry.getValue() instanceof ElementIdentity.PhysicalCatalogObject physical
          && !accepted
              .getOrDefault(element + " " + Vocabulary.PHYSICAL_CATALOG_REFERENCE, Set.of())
              .contains(
                  IdentityKey.choice(physical.physicalCatalogReference().canonical())
                      .canonical())) {
        throw reject(
            "New "
                + lower(physical.kind())
                + " "
                + element
                + " comes with the acceptance of its physical-catalog-reference Fact");
      }
    }
  }

  /**
   * When a Fact is retired, every confirmed Fact that has it as a premise is retired in the same
   * commit, so knowledge is never presented without its premise. A Fact that only replaced the
   * retired one keeps standing. Every Fact retired in the commit is checked, including one accepted
   * again later in the same commit.
   */
  private void checkRetirementsCascaded() {
    for (var fact : retiredFacts) {
      for (var dependent : projection.confirmedDependents(fact)) {
        var dependentSlice = requireFact(Ref.urn(dependent));
        if (dependentSlice.record().is(FactState.CONFIRMED)
            && premises(dependentSlice, dependentSlice.record().confirmationFactDependencies())
                .contains(fact)) {
          throw reject(
              "Confirmed Fact "
                  + dependent
                  + " has premise dependency "
                  + fact
                  + ", retired in this commit, and is not retired with it");
        }
      }
    }
  }

  private void updatePeriods(FactSlice slice) {
    var periods = slice.periods;
    var open = !periods.isEmpty() && periods.getLast().retiredCommit() == null;
    var confirmed = slice.record().is(FactState.CONFIRMED);
    if (confirmed && !open) {
      periods.add(new PresentablePeriodRow(slice.row().fact(), commit, null));
    } else if (!confirmed && open) {
      var period = periods.removeLast();
      periods.add(new PresentablePeriodRow(period.fact(), period.confirmedCommit(), commit));
    }
  }

  // References

  private FactSlice requireFact(Ref fact) {
    return projection.fact(fact.urnValue()).orElseThrow(() -> missing("Fact", fact));
  }

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

  private static String lower(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT).replace('_', ' ');
  }

  private static CommitRejectedException missing(String what, Ref reference) {
    return reject("Missing reference: no " + what + " " + reference.urnValue());
  }

  private static CommitRejectedException reject(String reason) {
    return new CommitRejectedException(reason);
  }
}
