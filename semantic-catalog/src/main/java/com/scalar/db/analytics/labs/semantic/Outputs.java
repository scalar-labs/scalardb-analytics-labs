/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.model.Check;
import com.scalar.db.analytics.labs.semantic.model.DependencyRef;
import com.scalar.db.analytics.labs.semantic.model.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Provenance;
import com.scalar.db.analytics.labs.semantic.model.Scope;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ConfirmationPath;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.PresentationKind;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The outputs of the public API's operations and reads. */
public final class Outputs {
  private Outputs() {}

  /**
   * What every operation returns besides its own output.
   *
   * @param snapshot the Catalog Snapshot of the commit written, or null when nothing was written
   * @param tasks build tasks created, or existing ones a trigger was added to
   */
  public record Effects(@Nullable String snapshot, List<String> tasks) {}

  /**
   * @param recorded {@code observation-and-derived}, {@code derived-only}, {@code observation}, or
   *     {@code nothing}
   */
  public record ObservationRegistered(
      @Nullable String inputResource,
      @Nullable String inputObservation,
      String recorded,
      Effects effects) {}

  public record AnswerResult(
      String fact, String answerEvent, String transition, @Nullable String reasonForNoTransition) {}

  public record AnswersSubmitted(
      List<AnswerResult> answers, List<CascadedTransition> cascaded, Effects effects) {}

  public record CascadedTransition(String fact, String transition) {}

  public record SelectionMade(String selectionEvent, @Nullable String selected, Effects effects) {}

  public record PolicyVersionSet(String policyVersion, Effects effects) {}

  public record PresentationRegistered(String presentation) {}

  /**
   * @param result the commits written, accepted Facts, candidates not accepted and why, and missing
   *     inputs
   */
  public record TaskRun(
      String task,
      BuildTask.State state,
      @Nullable JsonNode result,
      @Nullable String waitingReason,
      @Nullable String failureReason,
      Effects effects) {}

  /**
   * Fact Revision content (section 2.6).
   *
   * @param elementName a readable name that places the element, such as {@code
   *     source.namespace.table.column} for a Field; a prototype addition for presentation
   */
  public record FactRevisionContent(
      String factRevision,
      String fact,
      String factAddress,
      String element,
      ElementKind elementKind,
      String elementLabel,
      String elementName,
      String predicate,
      JsonNode value,
      FactState state,
      Scope scope,
      List<DependencyRef> dependencies,
      List<Check> checks,
      List<String> unconfirmedItems,
      @Nullable Provenance provenance,
      @Nullable ConfirmationPath confirmationPath,
      @Nullable String confirmationBasis,
      @Nullable String policyVersion,
      @Nullable String decidingParty,
      long lastConfirmedOrChecked,
      boolean inaccessibleReferences,
      @Nullable Boolean newCandidates,
      @Nullable Boolean newerObservations) {}

  public record ElementSummary(
      String element,
      ElementKind kind,
      String label,
      @Nullable String parent,
      @Nullable String from,
      @Nullable String to,
      @Nullable JsonNode identifying) {}

  public record ElementsFound(String snapshot, List<ElementSummary> elements) {}

  /** The response to one Fact Address of a knowledge request. */
  public record AddressResponse(
      String factAddress,
      Scope scope,
      @Nullable String overlap,
      @Nullable FactRevisionContent selected,
      List<FactRevisionContent> newCandidates,
      @Nullable String status,
      List<FactRevisionContent> material,
      List<String> rejectedOrRetired,
      @Nullable String awaiting) {}

  /**
   * @param status {@code missing} when the element has no Fact Address with the predicate
   */
  public record RequestResponse(
      String element,
      String elementLabel,
      String predicate,
      List<AddressResponse> addresses,
      @Nullable String status) {}

  public record KnowledgeResponse(String snapshot, List<RequestResponse> responses) {}

  public record ReviewItem(
      PresentationKind kind,
      String factAddress,
      List<FactRevisionContent> facts,
      @Nullable String policyEvaluation,
      List<String> presentations) {}

  public record ReviewQueue(String snapshot, List<ReviewItem> items) {}

  public record PolicyPreview(
      String currentVersion, String snapshot, int wouldConfirm, List<String> facts) {}

  public record Verification(boolean consistent, long commits, List<String> differences) {}

  public record SnapshotSummary(
      String snapshot,
      long commit,
      long elements,
      long confirmed,
      long candidates,
      long awaitingReview,
      long conflicts,
      long createdTasks,
      long runningTasks,
      long failedTasks,
      String policyVersion) {}
}
