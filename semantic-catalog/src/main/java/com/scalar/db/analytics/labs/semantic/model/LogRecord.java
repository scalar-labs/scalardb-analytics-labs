/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.AnswerType;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ConfirmationPath;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.InputKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.PolicyResult;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Preset;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.RetirementReason;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Transition;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One record of a domain's append-only log (see {@code docs/semantic-catalog/record-model.md}, "The
 * log").
 *
 * <p>A commit holds one or more log records in order, each at its own {@linkplain LogPosition log
 * position}. Records that create something are identified by a URN: an {@link Element}, a {@link
 * FactAddress}, or a {@link Fact} by the {@linkplain Named name-based URN} the record holds, and an
 * {@link InputResource} or an {@link InputObservation} by the URN issued from that position.
 * {@linkplain FactEvent Fact Events} change how one Fact is treated, and each produces a Fact
 * Revision, and a {@link ResolveConflict} records a deciding party's choice in a conflict. Log
 * records are never updated or deleted; everything materialized for reading is derived from them.
 *
 * <p>A record built for a commit request may refer to records of the same request by temporary
 * numbers; a record stored in the log refers to other records only by URN.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "record")
@JsonSubTypes({
  @JsonSubTypes.Type(value = LogRecord.Element.class, name = "element"),
  @JsonSubTypes.Type(value = LogRecord.FactAddress.class, name = "fact-address"),
  @JsonSubTypes.Type(value = LogRecord.Fact.class, name = "fact"),
  @JsonSubTypes.Type(value = LogRecord.InputResource.class, name = "input-resource"),
  @JsonSubTypes.Type(value = LogRecord.InputObservation.class, name = "input-observation"),
  @JsonSubTypes.Type(value = LogRecord.ObservationEvent.class, name = "observation-event"),
  @JsonSubTypes.Type(value = LogRecord.PolicyVersion.class, name = "policy-version"),
  @JsonSubTypes.Type(value = LogRecord.Accept.class, name = "accept"),
  @JsonSubTypes.Type(value = LogRecord.ProposeRetire.class, name = "propose-retire"),
  @JsonSubTypes.Type(value = LogRecord.EvaluatePolicy.class, name = "evaluate-policy"),
  @JsonSubTypes.Type(value = LogRecord.Answer.class, name = "answer"),
  @JsonSubTypes.Type(value = LogRecord.Confirm.class, name = "confirm"),
  @JsonSubTypes.Type(value = LogRecord.Reject.class, name = "reject"),
  @JsonSubTypes.Type(value = LogRecord.Retire.class, name = "retire"),
  @JsonSubTypes.Type(value = LogRecord.ResolveConflict.class, name = "resolve-conflict")
})
public sealed interface LogRecord {
  /**
   * A record that creates a record identified by a name-based URN: an element, a Fact Address, or a
   * Fact. The store issues the URN when it writes the commit and keeps it in the log record, so
   * replaying the log reads the URNs issued rather than building them again, and a later change to
   * how new URNs are built leaves earlier ones as they were.
   */
  sealed interface Named extends LogRecord {
    /**
     * The URN issued for the record created; the store sets it when the commit is written, so a
     * commit request leaves it absent.
     */
    @Nullable String urn();

    /**
     * The URN issued for the record created.
     *
     * @throws IllegalStateException when the record has not been written yet
     */
    default String issuedUrn() {
      var urn = urn();
      if (urn == null) {
        throw new IllegalStateException("A " + getClass().getSimpleName() + " not yet written");
      }
      return urn;
    }
  }

  /**
   * A new element: a node or an edge of the labeled property graph. A proposal that the {@linkplain
   * ElementIdentity identity} finds to be as an existing element is not written.
   */
  record Element(@Nullable String urn, ElementIdentity identity) implements Named {
    /** A proposed element, for a commit request. */
    public Element(ElementIdentity identity) {
      this(null, identity);
    }
  }

  /**
   * A new Fact Address: what is being judged, independently of any value.
   *
   * @param element the element the statement is about
   * @param predicate the predicate, from the closed core, a Generator's declaration, or a
   *     free-description predicate
   * @param scope the applicability scope within which values are compared
   * @param predicateDefinition the natural-language definition of a free-description predicate,
   *     which has no identity of its own; absent for other predicates
   */
  record FactAddress(
      @Nullable String urn,
      Ref element,
      String predicate,
      Scope scope,
      @Nullable String predicateDefinition)
      implements Named {
    /** A proposed Fact Address, for a commit request. */
    public FactAddress(
        Ref element, String predicate, Scope scope, @Nullable String predicateDefinition) {
      this(null, element, predicate, scope, predicateDefinition);
    }

    /**
     * Whether the Fact Address is found by its element and predicate: its scope is empty and its
     * predicate is not free-description. Any other Fact Address is the same as another only when
     * the proposer points to it.
     */
    @JsonIgnore
    public boolean foundByPredicate() {
      return scope.isEmpty() && predicateDefinition == null;
    }
  }

  /**
   * A new Fact: an immutable statement that binds a value to one Fact Address. Different values for
   * the same Fact Address are different Facts.
   *
   * @param address the Fact Address
   * @param value the value, whose shape the predicate defines
   * @param identityKey the identity key of a closed-range predicate's value, which makes two Facts
   *     at one Fact Address the same; absent for other predicates, whose Facts are the same only
   *     when the proposer points to them
   */
  record Fact(@Nullable String urn, Ref address, JsonNode value, @Nullable IdentityKey identityKey)
      implements Named {
    /** A proposed Fact, for a commit request. */
    public Fact(Ref address, JsonNode value, @Nullable IdentityKey identityKey) {
      this(null, address, value, identityKey);
    }
  }

  /**
   * A new Input Resource: a source of input whose changes are tracked, such as one table's physical
   * schema, one registered document, or one piece of usage feedback.
   *
   * @param inputKind what kind of input this is, which decides how it is identified
   * @param tableIdentifier the Physical Catalog's resource identifier of the table whose physical
   *     schema this is; two Input Resources with the same one are the same. Present for a physical
   *     schema and only for it; every other Input Resource is new when it is registered.
   */
  record InputResource(InputKind inputKind, @Nullable String tableIdentifier) implements LogRecord {
    public InputResource {
      if ((inputKind == InputKind.PHYSICAL_SCHEMA) != (tableIdentifier != null)) {
        throw new IllegalArgumentException(
            "A physical schema, and only a physical schema, has a table identifier");
      }
    }

    public static InputResource physicalSchema(String tableIdentifier) {
      return new InputResource(InputKind.PHYSICAL_SCHEMA, tableIdentifier);
    }

    public static InputResource document() {
      return new InputResource(InputKind.DOCUMENT, null);
    }

    public static InputResource usageFeedback() {
      return new InputResource(InputKind.USAGE_FEEDBACK, null);
    }
  }

  /**
   * A new Input Observation: the content of an Input Resource as observed at one commit.
   *
   * @param inputResource the Input Resource observed
   * @param previous the previous observation of the same Input Resource; the store sets it when the
   *     commit is written, so a commit request leaves it absent
   * @param content the content observed; for usage feedback, a {@link UsageFeedback} in its stored
   *     JSON form
   */
  record InputObservation(Ref inputResource, @Nullable Ref previous, JsonNode content)
      implements LogRecord {
    /** The observation of a usage-feedback Input Resource, for a commit request. */
    public static InputObservation of(Ref inputResource, UsageFeedback feedback) {
      return new InputObservation(inputResource, null, LogRecordJson.toStored(feedback));
    }
  }

  /**
   * The Observation Event recording one Input Observation, written in the same commit.
   *
   * @param observation the Input Observation recorded
   * @param content what the event records about the observation
   */
  record ObservationEvent(Ref observation, JsonNode content) implements LogRecord {}

  /**
   * A user policy version. It is in the log so that every policy evaluation names the version in
   * effect, and the log decides the order of version changes and evaluations. A domain starts with
   * {@link #DEFAULT}.
   *
   * @param preset when machine judgement adopts
   * @param countsDeclaredUnenforced whether {@code declared-unenforced} counts as a deterministic
   *     validation method
   * @param setBy the semantics owner who set the version
   * @param reason why the version was set
   */
  record PolicyVersion(Preset preset, boolean countsDeclaredUnenforced, String setBy, String reason)
      implements LogRecord {
    /** The version a new domain starts with: {@code auto}, not counting unenforced declarations. */
    public static final PolicyVersion DEFAULT =
        new PolicyVersion(
            Preset.AUTO, false, "semantic-catalog", "The default policy of a new domain");
  }

  /**
   * A Fact Event: an event, with its reason, that changes how one Fact is treated (see {@code
   * docs/semantic-catalog/record-model.md}, "Fact Events and states"). Applying a Fact's events in
   * log order gives its Fact Record, and each event produces a Fact Revision.
   */
  sealed interface FactEvent extends LogRecord {
    /** The Fact the event targets. */
    Ref fact();
  }

  /**
   * An acceptance of a Fact as a candidate. The first one makes the Fact a Fact Record; a later one
   * is an additional acceptance. One that brings a new dependency reference returns a {@code
   * rejected} or {@code retired} Fact to {@code candidate}.
   *
   * <p>An acceptance states the Fact's dependency references, validation result, and provenance as
   * they are from then on, replacing those of earlier acceptances. An additional acceptance
   * therefore restates every dependency it keeps.
   *
   * @param dependencies the complete set of dependency references in effect after the acceptance
   * @param checks the validation result: the checks made and what they covered. An Input
   *     Observation a check references is also among the dependencies.
   * @param inputsRead what the Generator read
   * @param provenance the Generator, its version, and the build task
   */
  record Accept(
      Ref fact,
      List<Dependency> dependencies,
      List<Check> checks,
      JsonNode inputsRead,
      JsonNode provenance)
      implements FactEvent {
    public Accept {
      dependencies = List.copyOf(dependencies);
      checks = List.copyOf(checks);
    }
  }

  /**
   * A proposal to retire a Fact. Against a {@code confirmed} Fact it opens a retirement proposal;
   * against a {@code candidate} it can only lead to a rejection.
   *
   * <p>A proposal leaves the Fact's dependency references, validation result, and provenance as
   * they are: those change only with an acceptance.
   *
   * @param checks the validation result of the proposal, including any refuting check; a check
   *     references an Input Observation or a Fact Revision that exists, which need not be a
   *     dependency of the Fact
   */
  record ProposeRetire(
      Ref fact,
      RetirementReason reason,
      List<Check> checks,
      JsonNode inputsRead,
      JsonNode provenance)
      implements FactEvent {
    public ProposeRetire {
      checks = List.copyOf(checks);
    }
  }

  /**
   * A policy evaluation: whether a transition proceeds by machine judgement or needs consent.
   *
   * @param policyVersion the log record of the user policy version evaluated
   * @param transition what is evaluated: confirming a candidate, or retiring a Fact with an open
   *     retirement proposal
   * @param evaluator how the evaluation was made: inline, or by the policy evaluation model
   * @param decidedBy the evaluating party
   */
  record EvaluatePolicy(
      Ref fact,
      Ref policyVersion,
      Transition transition,
      PolicyResult result,
      Evaluator evaluator,
      String decidedBy,
      String reason)
      implements FactEvent {
    public EvaluatePolicy {
      if (evaluator instanceof Evaluator.Model model
          && model.reviewMaterial() != null
          && result != PolicyResult.REQUEST_CONSENT) {
        throw new IllegalArgumentException(
            "Review material comes only with an evaluation that requests consent");
      }
    }
  }

  /** How a policy evaluation was made. */
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "by")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = Evaluator.Inline.class, name = "inline"),
    @JsonSubTypes.Type(value = Evaluator.Model.class, name = "model")
  })
  sealed interface Evaluator {
    /** Inline evaluation, on deterministic signals and without a model. */
    record Inline() implements Evaluator {}

    /**
     * The policy evaluation model, which weighs the signals of a candidate as a whole.
     *
     * @param version the model and the prompt, recorded as a Generator's version is
     * @param reviewMaterial the material it built for the deciding party when it requested consent
     */
    record Model(JsonNode version, @Nullable JsonNode reviewMaterial) implements Evaluator {}
  }

  /**
   * A human answer. The answer and the transition it causes are separate events.
   *
   * @param transition what is answered: the confirmation of a candidate, or a retirement proposal
   * @param decidedBy the deciding party
   * @param authorityCheck the reference to the check of the deciding party's authority
   * @param wording the wording presented and answered
   * @param reviewPresentation the review presentation the answer was given in, if any
   */
  record Answer(
      Ref fact,
      Transition transition,
      AnswerType answerType,
      String decidedBy,
      String authorityCheck,
      String wording,
      @Nullable String reviewPresentation)
      implements FactEvent {}

  /**
   * The confirmation of a candidate.
   *
   * @param basis the log record of the policy evaluation choosing machine judgement, or of the
   *     consent answer, that the confirmation rests on
   */
  record Confirm(Ref fact, ConfirmationPath path, Ref basis) implements FactEvent {}

  /** The rejection of a candidate. */
  record Reject(Ref fact, Basis basis) implements FactEvent {}

  /** The retirement of a confirmed Fact. */
  record Retire(Ref fact, Basis basis) implements FactEvent {}

  /**
   * A deciding party's resolution of a conflict: which of the eligible Facts at one Fact Address is
   * presented. It is the only part of the selection written to the log, because no rule can
   * reproduce it; every other selection is derived. It targets a Fact Address, not one Fact, so it
   * is not a Fact Event.
   *
   * <p>It is accepted only when the chosen and passed-over Facts are exactly the eligible Facts at
   * the previous commit, two or more. The passed-over Facts stay displaced while the chosen Fact is
   * presentable.
   *
   * @param chosen the Fact presented from now on
   * @param passedOver the other eligible Facts
   * @param decidedBy the deciding party
   * @param authorityCheck the reference to the check of the deciding party's authority
   * @param wording the wording presented and answered
   * @param reviewPresentation the review presentation the resolution was made in, if any
   */
  record ResolveConflict(
      Ref factAddress,
      Ref chosen,
      List<Ref> passedOver,
      String decidedBy,
      String authorityCheck,
      String wording,
      @Nullable String reviewPresentation)
      implements LogRecord {
    public ResolveConflict {
      passedOver = List.copyOf(passedOver);
    }
  }

  /** What a rejection or a retirement rests on. */
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "basis")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = Basis.Event.class, name = "event"),
    @JsonSubTypes.Type(value = Basis.Cascade.class, name = "cascade")
  })
  sealed interface Basis {
    /**
     * A Fact Event of the same Fact.
     *
     * @param logRecord the log record of the event: for a rejection, a rejection or correction
     *     answer or a retirement proposal; for a retirement, a policy evaluation choosing machine
     *     judgement or a consent answer to the open retirement proposal
     */
    record Event(Ref logRecord) implements Basis {}

    /**
     * The rejection or retirement of a premise dependency of the target.
     *
     * @param fact the Fact rejected or retired
     */
    record Cascade(Ref fact) implements Basis {}
  }

  /** The record kind stored with the record. */
  default String kind() {
    return switch (this) {
      case Element ignored -> "element";
      case FactAddress ignored -> "fact-address";
      case Fact ignored -> "fact";
      case InputResource ignored -> "input-resource";
      case InputObservation ignored -> "input-observation";
      case ObservationEvent ignored -> "observation-event";
      case PolicyVersion ignored -> "policy-version";
      case Accept ignored -> "accept";
      case ProposeRetire ignored -> "propose-retire";
      case EvaluatePolicy ignored -> "evaluate-policy";
      case Answer ignored -> "answer";
      case Confirm ignored -> "confirm";
      case Reject ignored -> "reject";
      case Retire ignored -> "retire";
      case ResolveConflict ignored -> "resolve-conflict";
    };
  }
}
