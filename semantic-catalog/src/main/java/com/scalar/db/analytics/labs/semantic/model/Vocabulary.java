/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** The closed vocabularies that log records and public API calls use. */
public final class Vocabulary {
  private Vocabulary() {}

  public enum FactState {
    @JsonProperty("candidate")
    CANDIDATE,
    @JsonProperty("confirmed")
    CONFIRMED,
    @JsonProperty("rejected")
    REJECTED,
    @JsonProperty("retired")
    RETIRED
  }

  /** What a policy evaluation or an answer judges. */
  public enum Target {
    @JsonProperty("confirmation")
    CONFIRMATION,
    @JsonProperty("retirement")
    RETIREMENT
  }

  public enum PolicyResult {
    @JsonProperty("proceed")
    PROCEED,
    @JsonProperty("request-consent")
    REQUEST_CONSENT
  }

  public enum AnswerType {
    @JsonProperty("consent")
    CONSENT,
    @JsonProperty("rejection")
    REJECTION,
    @JsonProperty("correction")
    CORRECTION,
    @JsonProperty("unknown")
    UNKNOWN
  }

  public enum ConfirmationPath {
    @JsonProperty("machine")
    MACHINE,
    @JsonProperty("human")
    HUMAN
  }

  public enum Preset {
    @JsonProperty("full-auto")
    FULL_AUTO,
    @JsonProperty("auto")
    AUTO,
    @JsonProperty("manual")
    MANUAL
  }

  public enum RetirementReason {
    @JsonProperty("element-gone")
    ELEMENT_GONE,
    @JsonProperty("confirmation-wrong")
    CONFIRMATION_WRONG
  }

  /** The kinds of input an Input Observation holds. */
  public enum ObservationKind {
    @JsonProperty("physical-schema")
    PHYSICAL_SCHEMA,
    @JsonProperty("document")
    DOCUMENT,
    @JsonProperty("ruling")
    RULING,
    @JsonProperty("missing-knowledge")
    MISSING_KNOWLEDGE,
    @JsonProperty("user-correction")
    USER_CORRECTION,
    @JsonProperty("data")
    DATA
  }

  public enum PresentationKind {
    @JsonProperty("confirmation")
    CONFIRMATION,
    @JsonProperty("retirement")
    RETIREMENT,
    @JsonProperty("conflict")
    CONFLICT
  }
}
