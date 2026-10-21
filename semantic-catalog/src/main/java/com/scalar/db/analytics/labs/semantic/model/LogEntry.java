/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.AnswerType;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ConfirmationPath;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ObservationKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.PolicyResult;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Preset;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.RetirementReason;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Target;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One record of a domain's append-only log: an observation, a user policy version, or a Fact Event.
 * A log record's identifier is {@code c<commit>.<ordinal>}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "record")
@JsonSubTypes({
  @JsonSubTypes.Type(value = LogEntry.Observe.class, name = "observe"),
  @JsonSubTypes.Type(value = LogEntry.PolicyVersion.class, name = "policy-version"),
  @JsonSubTypes.Type(value = LogEntry.Accept.class, name = "accept"),
  @JsonSubTypes.Type(value = LogEntry.ProposeRetire.class, name = "propose-retire"),
  @JsonSubTypes.Type(value = LogEntry.EvaluatePolicy.class, name = "evaluate-policy"),
  @JsonSubTypes.Type(value = LogEntry.Answer.class, name = "answer"),
  @JsonSubTypes.Type(value = LogEntry.Confirm.class, name = "confirm"),
  @JsonSubTypes.Type(value = LogEntry.Reject.class, name = "reject"),
  @JsonSubTypes.Type(value = LogEntry.Retire.class, name = "retire"),
  @JsonSubTypes.Type(value = LogEntry.Select.class, name = "select")
})
public sealed interface LogEntry {
  /** A Fact Event, which targets one Fact or, for a selection, one Fact Address. */
  sealed interface FactEvent extends LogEntry {}

  /**
   * An Input Observation together with its Observation Event.
   *
   * @param version the source's version of the input, such as a table metadata version
   * @param question the question URN for observations made at question time
   */
  record Observe(
      String urn,
      String inputResource,
      ObservationKind kind,
      String digest,
      JsonNode content,
      @Nullable String version,
      @Nullable String question)
      implements LogEntry {}

  record PolicyVersion(
      Preset preset, boolean countDeclaredUnenforced, String principal, String reason)
      implements LogEntry {}

  /**
   * An acceptance of a candidate.
   *
   * @param descriptor the element's identity, carried by existence Facts
   * @param basis log record identifiers of answers the candidate was produced from
   */
  record Accept(
      String fact,
      String address,
      String element,
      String predicate,
      Scope scope,
      JsonNode value,
      @Nullable ElementDescriptor descriptor,
      List<DependencyRef> dependencies,
      List<Check> checks,
      List<String> basis,
      Provenance provenance)
      implements FactEvent {}

  record ProposeRetire(
      String fact,
      RetirementReason reason,
      List<DependencyRef> dependencies,
      List<Check> checks,
      Provenance provenance)
      implements FactEvent {}

  /**
   * @param policyVersion the log record identifier of the user policy version evaluated
   */
  record EvaluatePolicy(
      String fact,
      String policyVersion,
      Target target,
      PolicyResult result,
      String decidingParty,
      String reason)
      implements FactEvent {}

  record Answer(
      String fact,
      Target target,
      AnswerType answerType,
      String principal,
      String authorityCheck,
      String wording,
      @Nullable String presentation)
      implements FactEvent {}

  /**
   * @param basis the policy evaluation or answer Fact Event the confirmation is based on
   */
  record Confirm(String fact, ConfirmationPath path, String basis) implements FactEvent {}

  /**
   * @param basis the answer or policy evaluation the rejection is based on
   * @param cause the Fact whose rejection or retirement cascaded into this one
   */
  record Reject(String fact, @Nullable String basis, @Nullable String cause) implements FactEvent {}

  record Retire(String fact, @Nullable String basis, @Nullable String cause) implements FactEvent {}

  record Select(
      String address,
      String selected,
      List<String> notSelected,
      String principal,
      String authorityCheck,
      String wording)
      implements FactEvent {}
}
