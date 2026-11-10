/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;

/**
 * One record of a domain's append-only log (see {@code docs/semantic-catalog/record-model.md}, "The
 * log").
 *
 * <p>A commit holds one or more log records in order, each at its own {@linkplain LogPosition log
 * position}. Records that create something ({@link Element}, {@link FactAddress}, {@link Fact},
 * {@link InputResource}, {@link InputObservation}) are identified by the URN issued from that
 * position. Log records are never updated or deleted; everything materialized for reading is
 * derived from them.
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
  @JsonSubTypes.Type(value = LogRecord.PolicyVersion.class, name = "policy-version")
})
public sealed interface LogRecord {
  /**
   * A new element: a node or an edge of the labeled property graph. A proposal that the {@linkplain
   * ElementIdentity identity} finds to be as an existing element is not written.
   */
  record Element(ElementIdentity identity) implements LogRecord {}

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
      Ref element, String predicate, Scope scope, @Nullable String predicateDefinition)
      implements LogRecord {}

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
  record Fact(Ref address, JsonNode value, @Nullable IdentityKey identityKey)
      implements LogRecord {}

  /**
   * A new Input Resource: a source of input whose changes are tracked, such as one table's physical
   * schema or one registered document.
   *
   * @param kind what kind of input this is, such as a physical schema or a document
   * @param tableIdentifier the Physical Catalog's resource identifier of the table whose physical
   *     schema this is; two Input Resources with the same one are the same
   * @param question the URN of the question at which missing knowledge was found; two Input
   *     Resources with the same one are the same
   */
  record InputResource(String kind, @Nullable String tableIdentifier, @Nullable String question)
      implements LogRecord {}

  /**
   * A new Input Observation: the content of an Input Resource as observed at one commit.
   *
   * @param inputResource the Input Resource observed
   * @param previous the previous observation of the same Input Resource; the store sets it when the
   *     commit is written, so a commit request leaves it absent
   * @param content the content observed
   */
  record InputObservation(Ref inputResource, @Nullable Ref previous, JsonNode content)
      implements LogRecord {}

  /**
   * The Observation Event recording one Input Observation, written in the same commit.
   *
   * @param observation the Input Observation recorded
   * @param content what the event records about the observation
   */
  record ObservationEvent(Ref observation, JsonNode content) implements LogRecord {}

  /**
   * A user policy version. It is in the log so that every policy evaluation names the version in
   * effect, and the log decides the order of version changes and evaluations.
   *
   * @param content the version, which policy evaluation interprets and the store does not
   */
  record PolicyVersion(JsonNode content) implements LogRecord {}

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
    };
  }
}
