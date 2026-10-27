/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.scalar.db.analytics.labs.semantic.CatalogState.AddressRecord;
import com.scalar.db.analytics.labs.semantic.CatalogState.ElementRecord;
import com.scalar.db.analytics.labs.semantic.CatalogState.FactRecord;
import com.scalar.db.analytics.labs.semantic.CatalogState.Revision;
import com.scalar.db.analytics.labs.semantic.Outputs.AddressResponse;
import com.scalar.db.analytics.labs.semantic.Outputs.ElementSummary;
import com.scalar.db.analytics.labs.semantic.Outputs.ElementsFound;
import com.scalar.db.analytics.labs.semantic.Outputs.FactRevisionContent;
import com.scalar.db.analytics.labs.semantic.Outputs.KnowledgeResponse;
import com.scalar.db.analytics.labs.semantic.Outputs.PolicyPreview;
import com.scalar.db.analytics.labs.semantic.Outputs.RequestResponse;
import com.scalar.db.analytics.labs.semantic.Outputs.ReviewItem;
import com.scalar.db.analytics.labs.semantic.Outputs.ReviewQueue;
import com.scalar.db.analytics.labs.semantic.Outputs.SnapshotSummary;
import com.scalar.db.analytics.labs.semantic.SemanticCatalogException.Kind;
import com.scalar.db.analytics.labs.semantic.model.Check;
import com.scalar.db.analytics.labs.semantic.model.DependencyRef;
import com.scalar.db.analytics.labs.semantic.model.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.NormalForms;
import com.scalar.db.analytics.labs.semantic.model.Predicates;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.PolicyResult;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.PresentationKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Preset;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Target;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The public API's reads (section 4). A read takes a Catalog Snapshot URN or latest, writes
 * nothing, and returns the URN of the Catalog Snapshot it read.
 *
 * <p>Provisional: whether a candidate awaits consent, and its open retirement proposal, are read
 * from the latest state rather than reconstructed for a past point.
 */
public final class SnapshotReads {
  /** The predicate that requests every predicate of an element; a prototype extension. */
  public static final String ALL_PREDICATES = "*";

  private final CatalogState state;
  private final Collection<BuildTask> tasks;
  private final Collection<ReviewPresentation> presentations;

  SnapshotReads(
      CatalogState state,
      Collection<BuildTask> tasks,
      Collection<ReviewPresentation> presentations) {
    this.state = state;
    this.tasks = tasks;
    this.presentations = presentations;
  }

  /** The commit a point names: a Catalog Snapshot URN, or null for latest. */
  public long point(@Nullable String snapshot) {
    if (snapshot == null || snapshot.isBlank() || snapshot.equals("latest")) {
      return state.commit();
    }
    var commit = Urns.snapshotCommit(state.domain(), snapshot);
    if (commit < 0) {
      throw SemanticCatalogException.invalid("Not a Catalog Snapshot URN of this domain.");
    }
    if (commit > state.commit()) {
      throw new SemanticCatalogException(
          Kind.POINT_OUT_OF_RANGE, "The Catalog Snapshot is not written yet.");
    }
    return commit;
  }

  private String snapshot(long commit) {
    return Urns.snapshot(state.domain(), commit);
  }

  // 4.1 Finding elements

  public ElementsFound findElements(
      @Nullable String snapshot,
      @Nullable ElementKind kind,
      @Nullable String parent,
      @Nullable String writtenForm) {
    var c = point(snapshot);
    List<ElementRecord> candidates;
    if (writtenForm != null) {
      var wanted = normalizeTerm(writtenForm);
      candidates = new ArrayList<>();
      for (var term : state.elementRecords().values()) {
        if (term.descriptor.kind() != ElementKind.BUSINESS_TERM || !exists(term, c)) {
          continue;
        }
        var form = selectedValue(term.descriptor.urn(), Predicates.WRITTEN_FORM, c);
        if (form == null || !normalizeTerm(form.path("text").asText()).equals(wanted)) {
          continue;
        }
        var referenced = selectedValue(term.descriptor.urn(), Predicates.REFERENCED_ELEMENT, c);
        var target =
            referenced == null
                ? null
                : state.elementRecords().get(referenced.path("element").asText());
        if (target != null && exists(target, c)) {
          candidates.add(target);
        }
      }
    } else {
      candidates = new ArrayList<>(state.elementRecords().values());
    }
    var found =
        candidates.stream()
            .filter(element -> exists(element, c))
            .filter(element -> kind == null || element.descriptor.kind() == kind)
            .filter(element -> parent == null || parent.equals(element.descriptor.parent()))
            .distinct()
            .map(SnapshotReads::summary)
            .toList();
    return new ElementsFound(snapshot(c), found);
  }

  private static String normalizeTerm(String text) {
    return text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }

  static ElementSummary summary(ElementRecord element) {
    var descriptor = element.descriptor;
    return new ElementSummary(
        descriptor.urn(),
        descriptor.kind(),
        descriptor.label(),
        descriptor.parent(),
        descriptor.from(),
        descriptor.to(),
        descriptor.identifying());
  }

  /** Whether an element's existence Fact is selected as of a commit. */
  private boolean exists(ElementRecord element, long c) {
    return selectedValue(element.descriptor.urn(), Predicates.EXISTS, c) != null;
  }

  private com.fasterxml.jackson.databind.@Nullable JsonNode selectedValue(
      String element, String predicate, long c) {
    var address =
        state.address(
            Urns.factAddress(
                state.domain(),
                element,
                predicate,
                com.scalar.db.analytics.labs.semantic.model.Scope.UNRESTRICTED));
    if (address == null) {
      return null;
    }
    var selected = address.selectionAt(c).selected();
    return selected == null ? null : state.requireFact(selected).value;
  }

  // 4.2 Requesting knowledge

  /**
   * One request: an element URN and a predicate, or an edge kind and both endpoints in the edge's
   * direction.
   */
  public record Request(
      @Nullable String element,
      @Nullable ElementKind edgeKind,
      @Nullable String from,
      @Nullable String to,
      String predicate) {}

  public KnowledgeResponse requestKnowledge(
      @Nullable String snapshot, List<Request> requests, @Nullable List<String> entities) {
    var c = point(snapshot);
    var responses = new ArrayList<RequestResponse>();
    for (var request : requests) {
      for (var element : targets(request, c)) {
        responses.addAll(respond(element, request.predicate(), entities, c));
      }
      if (request.element() == null && targets(request, c).isEmpty()) {
        responses.add(
            new RequestResponse(
                request.from() + " -> " + request.to(),
                "",
                request.predicate(),
                List.of(),
                "missing"));
      }
    }
    return new KnowledgeResponse(snapshot(c), responses);
  }

  private List<ElementRecord> targets(Request request, long c) {
    if (request.element() != null) {
      var element = state.elementRecords().get(request.element());
      if (element == null || element.created > c) {
        throw SemanticCatalogException.notFound("Element not found: " + request.element());
      }
      return List.of(element);
    }
    if (request.edgeKind() == null || request.from() == null || request.to() == null) {
      throw SemanticCatalogException.invalid(
          "A request needs an element URN, or an edge kind and both ends.");
    }
    var edges = new ArrayList<ElementRecord>();
    for (var element : state.elementRecords().values()) {
      var descriptor = element.descriptor;
      if (descriptor.kind() != request.edgeKind() || element.created > c) {
        continue;
      }
      var forward =
          request.from().equals(descriptor.from()) && request.to().equals(descriptor.to());
      var backward =
          !descriptor.directed()
              && request.from().equals(descriptor.to())
              && request.to().equals(descriptor.from());
      if (forward || backward) {
        edges.add(element);
      }
    }
    return edges;
  }

  private List<RequestResponse> respond(
      ElementRecord element, String predicate, @Nullable List<String> entities, long c) {
    var predicates = new ArrayList<String>();
    if (predicate.equals(ALL_PREDICATES)) {
      for (var address : state.addressRecords().values()) {
        if (address.element.equals(element.descriptor.urn())
            && !predicates.contains(address.predicate)
            && hasFactAt(address, c)) {
          predicates.add(address.predicate);
        }
      }
    } else {
      if (!element.descriptor.kind().accepts(predicate)) {
        throw SemanticCatalogException.invalid(
            "The predicate is not a predicate of the element's kind: " + predicate);
      }
      predicates.add(predicate);
    }
    var responses = new ArrayList<RequestResponse>();
    for (var name : predicates) {
      var addresses = new ArrayList<AddressResponse>();
      for (var address : state.addressRecords().values()) {
        if (address.element.equals(element.descriptor.urn())
            && address.predicate.equals(name)
            && hasFactAt(address, c)) {
          addresses.add(respond(address, entities, c));
        }
      }
      responses.add(
          new RequestResponse(
              element.descriptor.urn(),
              element.descriptor.label(),
              name,
              addresses,
              addresses.isEmpty() ? "missing" : null));
    }
    return responses;
  }

  private boolean hasFactAt(AddressRecord address, long c) {
    return address.facts.stream().anyMatch(urn -> state.requireFact(urn).stateAt(c) != null);
  }

  private AddressResponse respond(AddressRecord address, @Nullable List<String> entities, long c) {
    var selection = address.selectionAt(c);
    var candidates =
        address.facts.stream()
            .map(state::requireFact)
            .filter(fact -> fact.stateAt(c) == FactState.CANDIDATE)
            .toList();
    String overlap = null;
    if (entities != null) {
      overlap =
          address.scope.entities().isEmpty()
              ? "unrestricted"
              : address.scope.entities().stream().anyMatch(entities::contains)
                  ? "overlaps"
                  : "does-not-overlap";
    }
    if (selection.selected() != null && !selection.conflict()) {
      return new AddressResponse(
          address.urn,
          address.scope,
          overlap,
          content(state.requireFact(selection.selected()), c, true),
          candidates.stream().map(fact -> content(fact, c, true)).toList(),
          null,
          List.of(),
          List.of(),
          null);
    }
    if (selection.conflict()) {
      return new AddressResponse(
          address.urn,
          address.scope,
          overlap,
          selection.selected() == null
              ? null
              : content(state.requireFact(selection.selected()), c, true),
          List.of(),
          "conflict",
          selection.eligible().stream()
              .map(urn -> content(state.requireFact(urn), c, true))
              .toList(),
          List.of(),
          null);
    }
    if (!candidates.isEmpty()) {
      var awaitingConsent =
          candidates.stream()
              .anyMatch(
                  fact ->
                      state.dependenciesConfirmed(fact)
                          && fact.latestConfirmationEvaluation != null
                          && fact.latestConfirmationEvaluation.result()
                              == PolicyResult.REQUEST_CONSENT);
      return new AddressResponse(
          address.urn,
          address.scope,
          overlap,
          null,
          List.of(),
          "awaiting-judgement",
          candidates.stream().map(fact -> content(fact, c, true)).toList(),
          List.of(),
          awaitingConsent ? "consent" : "dependencies");
    }
    var ended =
        address.facts.stream()
            .filter(
                urn -> {
                  var factState = state.requireFact(urn).stateAt(c);
                  return factState == FactState.REJECTED || factState == FactState.RETIRED;
                })
            .toList();
    return new AddressResponse(
        address.urn, address.scope, overlap, null, List.of(), "missing", List.of(), ended, null);
  }

  // 4.3 Retrieving Fact Revisions

  /** A Fact Revision by its URN, or the latest revision of a Fact as of a point. */
  public FactRevisionContent factRevision(String urn, @Nullable String snapshot) {
    var factUrn = Urns.factOfRevision(urn);
    var fact = state.factRecord(factUrn);
    if (fact == null) {
      throw SemanticCatalogException.notFound("Fact not found.");
    }
    if (urn.contains("@")) {
      var revision =
          fact.revisions.stream()
              .filter(candidate -> candidate.urn().equals(urn))
              .findFirst()
              .orElseThrow(() -> SemanticCatalogException.notFound("Fact Revision not found."));
      return content(fact, revision, revision.commit(), false);
    }
    var c = point(snapshot);
    var revision = fact.revisionAt(c);
    if (revision == null) {
      throw SemanticCatalogException.notFound("The Fact does not exist at that point.");
    }
    return content(fact, revision, c, true);
  }

  FactRevisionContent content(FactRecord fact, long c, boolean pointInfo) {
    var revision = Objects.requireNonNull(fact.revisionAt(c));
    return content(fact, revision, c, pointInfo);
  }

  private FactRevisionContent content(
      FactRecord fact, Revision revision, long c, boolean pointInfo) {
    var element = state.elementRecord(fact.element).descriptor;
    var items =
        Predicates.validationItems(element.kind(), fact.predicate, element.identifyingOrEmpty());
    var unconfirmed =
        items.stream()
            .filter(
                item ->
                    revision.checks().stream()
                        .noneMatch(
                            check ->
                                check.result() == Check.Result.SUPPORTED
                                    && check.items().contains(item)))
            .toList();
    Boolean newCandidates = null;
    Boolean newerObservations = null;
    if (pointInfo) {
      var address = Objects.requireNonNull(state.address(fact.address));
      newCandidates =
          address.facts.stream()
              .filter(urn -> !urn.equals(fact.urn))
              .anyMatch(urn -> state.requireFact(urn).stateAt(c) == FactState.CANDIDATE);
      newerObservations =
          revision.dependencies().stream()
              .filter(DependencyRef.OnObservation.class::isInstance)
              .map(DependencyRef.OnObservation.class::cast)
              .anyMatch(
                  dependency ->
                      state
                          .latestObservation(dependency.inputResource(), c)
                          .map(latest -> !latest.urn().equals(dependency.inputObservation()))
                          .orElse(false));
    }
    return new FactRevisionContent(
        revision.urn(),
        fact.urn,
        fact.address,
        fact.element,
        element.kind(),
        element.label(),
        name(element),
        fact.predicate,
        fact.value,
        revision.state(),
        fact.scope,
        revision.dependencies(),
        revision.checks(),
        unconfirmed,
        revision.provenance(),
        revision.confirmationPath(),
        revision.confirmationBasis(),
        revision.policyVersion(),
        revision.decidingParty(),
        revision.lastChecked(),
        false,
        newCandidates,
        newerObservations);
  }

  /** A readable name that places an element: a Field under its Dataset, an edge by its ends. */
  private String name(com.scalar.db.analytics.labs.semantic.model.ElementDescriptor element) {
    var parent = element.parent() == null ? null : state.elementRecords().get(element.parent());
    if (parent != null) {
      return parent.descriptor.label() + "." + element.label();
    }
    return element.kind().wireName() + " " + element.label();
  }

  // 4.4 The list awaiting review

  public ReviewQueue reviewQueue(
      @Nullable String snapshot,
      @Nullable PresentationKind kind,
      @Nullable String target,
      @Nullable String question) {
    return reviewQueue(snapshot, kind, target, question, false);
  }

  /**
   * The list awaiting review.
   *
   * @param unpresentedOnly keep only the items no review presentation has targeted yet, so an agent
   *     can present what newly became reviewable; a prototype addition
   */
  public ReviewQueue reviewQueue(
      @Nullable String snapshot,
      @Nullable PresentationKind kind,
      @Nullable String target,
      @Nullable String question,
      boolean unpresentedOnly) {
    var c = point(snapshot);
    var items = new ArrayList<SortableItem>();
    for (var fact : state.factRecords().values()) {
      var factState = fact.stateAt(c);
      if (factState == FactState.CANDIDATE && awaitsConsent(fact, new HashSet<>())) {
        items.add(
            item(PresentationKind.CONFIRMATION, fact, fact.latestConfirmationEvaluationId, c));
      }
      if ((factState == FactState.CONFIRMED || factState == FactState.CANDIDATE)
          && fact.openRetirementProposal != null) {
        var evaluation = latestRetirementEvaluation(fact);
        if (evaluation != null && evaluation.result == PolicyResult.REQUEST_CONSENT) {
          items.add(item(PresentationKind.RETIREMENT, fact, evaluation.id, c));
        }
      }
    }
    for (var address : state.addressRecords().values()) {
      var selection = address.selectionAt(c);
      if (selection.conflict()) {
        var facts =
            selection.eligible().stream()
                .map(urn -> content(state.requireFact(urn), c, true))
                .toList();
        items.add(
            new SortableItem(
                selection.from(),
                new ReviewItem(
                    PresentationKind.CONFLICT,
                    address.urn,
                    facts,
                    null,
                    presentationsFor(address.urn))));
      }
    }
    var filtered =
        items.stream()
            .filter(entry -> kind == null || entry.item.kind() == kind)
            .filter(entry -> !unpresentedOnly || entry.item.presentations().isEmpty())
            .filter(
                entry ->
                    target == null
                        || entry.item.factAddress().equals(target)
                        || entry.item.facts().stream()
                            .anyMatch(
                                fact ->
                                    fact.element().equals(target) || fact.fact().equals(target)))
            .filter(
                entry ->
                    question == null
                        || presentations.stream()
                            .anyMatch(
                                p ->
                                    question.equals(p.question())
                                        && entry.item.presentations().contains(p.id())))
            .sorted(Comparator.comparingLong(SortableItem::commit))
            .map(SortableItem::item)
            .toList();
    return new ReviewQueue(snapshot(c), filtered);
  }

  /**
   * Whether a candidate awaits consent. A candidate whose dependencies are confirmed awaits it when
   * the user policy requested consent. A prototype addition: a candidate that depends on other
   * candidates awaiting consent is listed with them, when the policy will request consent once they
   * are confirmed, so an element and the Facts that depend on it are reviewed in one round and
   * answered in one submission, in dependency order.
   */
  private boolean awaitsConsent(FactRecord fact, Set<String> visited) {
    if (fact.state != FactState.CANDIDATE || fact.unknownAnswered || !visited.add(fact.urn)) {
      return false;
    }
    if (state.dependenciesConfirmed(fact)) {
      return fact.latestConfirmationEvaluation != null
          && fact.latestConfirmationEvaluation.result() == PolicyResult.REQUEST_CONSENT;
    }
    for (var urn : fact.factDependencies()) {
      var dependency = state.factRecord(urn);
      if (dependency == null
          || (dependency.state != FactState.CONFIRMED
              && !awaitsConsent(dependency, new HashSet<>(visited)))) {
        return false;
      }
    }
    var c = state.commit();
    var policy = state.policyAt(c).version();
    return Judge.decideConfirmation(
                state, fact, policy.preset(), policy.countDeclaredUnenforced(), c + 1)
            .result()
        == PolicyResult.REQUEST_CONSENT;
  }

  private record SortableItem(long commit, ReviewItem item) {}

  private record RetirementEvaluation(String id, long commit, PolicyResult result) {}

  private @Nullable RetirementEvaluation latestRetirementEvaluation(FactRecord fact) {
    RetirementEvaluation latest = null;
    for (var revision : fact.revisions) {
      if (state.entry(revision.event())
              instanceof
              com.scalar.db.analytics.labs.semantic.model.LogEntry.EvaluatePolicy evaluation
          && evaluation.target() == Target.RETIREMENT) {
        latest = new RetirementEvaluation(revision.event(), revision.commit(), evaluation.result());
      }
    }
    return latest;
  }

  private SortableItem item(
      PresentationKind kind, FactRecord fact, @Nullable String evaluation, long c) {
    var commit =
        evaluation == null
            ? fact.revisions.getLast().commit()
            : Long.parseLong(evaluation.substring(1, evaluation.indexOf('.')));
    return new SortableItem(
        commit,
        new ReviewItem(
            kind,
            fact.address,
            List.of(content(fact, c, true)),
            evaluation,
            presentationsFor(fact.urn)));
  }

  private List<String> presentationsFor(String target) {
    return presentations.stream()
        .filter(
            p ->
                p.targets().stream()
                    .anyMatch(t -> t.fact().equals(target) || t.address().equals(target)))
        .map(ReviewPresentation::id)
        .toList();
  }

  // 4.5 Build task state

  public List<BuildTask> tasks(@Nullable String id, BuildTask.@Nullable State filter) {
    return tasks.stream()
        .filter(task -> id == null || task.id().equals(id))
        .filter(task -> filter == null || task.state() == filter)
        .toList();
  }

  // 4.6 Checking a user policy version before applying it

  public PolicyPreview previewPolicy(Preset preset, boolean countDeclaredUnenforced) {
    var c = state.commit();
    var facts = new ArrayList<String>();
    for (var fact : state.factRecords().values()) {
      if (fact.state == FactState.CANDIDATE && state.dependenciesConfirmed(fact)) {
        var decision =
            Judge.decideConfirmation(state, fact, preset, countDeclaredUnenforced, c + 1);
        if (decision.result() == PolicyResult.PROCEED) {
          facts.add(fact.urn);
        }
      }
    }
    return new PolicyPreview(state.policyAt(c).id(), snapshot(c), facts.size(), facts);
  }

  /** Counts that describe the latest Catalog Snapshot; a prototype convenience read. */
  public SnapshotSummary summary() {
    var c = state.commit();
    var confirmed =
        state.factRecords().values().stream().filter(f -> f.state == FactState.CONFIRMED).count();
    var candidates =
        state.factRecords().values().stream().filter(f -> f.state == FactState.CANDIDATE).count();
    var conflicts =
        state.addressRecords().values().stream().filter(a -> a.selectionAt(c).conflict()).count();
    var elements = state.elementRecords().values().stream().filter(e -> exists(e, c)).count();
    return new SnapshotSummary(
        snapshot(c),
        c,
        elements,
        confirmed,
        candidates,
        reviewQueue(null, null, null, null).items().size(),
        conflicts,
        tasks.stream().filter(t -> t.state() == BuildTask.State.CREATED).count(),
        tasks.stream().filter(t -> t.state() == BuildTask.State.RUNNING).count(),
        tasks.stream().filter(t -> t.state() == BuildTask.State.FAILED).count(),
        state.policyAt(c).id());
  }

  /** The normal form of a value, for callers comparing values. */
  public static String normalForm(com.fasterxml.jackson.databind.JsonNode value) {
    return NormalForms.of(value);
  }
}
