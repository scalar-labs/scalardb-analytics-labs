/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.scalar.db.analytics.labs.semantic.model.Dependency;
import com.scalar.db.analytics.labs.semantic.model.FactRevision;
import com.scalar.db.analytics.labs.semantic.model.Knowledge;
import com.scalar.db.analytics.labs.semantic.model.KnowledgeRequest;
import com.scalar.db.analytics.labs.semantic.model.LogPosition;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.LogRecordJson;
import com.scalar.db.analytics.labs.semantic.model.Review;
import com.scalar.db.analytics.labs.semantic.model.SemanticCatalogException;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Transition;
import com.scalar.db.analytics.labs.semantic.policy.PolicyEvaluationModel;
import com.scalar.db.analytics.labs.semantic.policy.PolicyEvaluator;
import com.scalar.db.analytics.labs.semantic.projection.FactRecord;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.AddressSelectionRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.ElementRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.FactAddressRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.FactRecordRow;
import com.scalar.db.analytics.labs.semantic.projection.PositionedRecord;
import com.scalar.db.analytics.labs.semantic.projection.Projection;
import com.scalar.db.analytics.labs.semantic.storage.StoreTransaction;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The Catalog Snapshot of one domain at one commit: what consumers read as the catalog.
 *
 * <p>The catalog presents, for each Fact Address, the Fact selected at the commit; elements, Fact
 * Addresses, and Facts that are not selected are material for judgement, not catalog content. A
 * snapshot is read within one {@code REPEATABLE READ} transaction and is valid only inside the
 * reader it is passed to.
 *
 * <p>A snapshot never replays the log. Selections come from the materialized selection history. A
 * Fact's state and content come from its materialized current Fact Record when the Fact has not
 * changed since the commit; a Fact changed since has only its own events folded up to the commit.
 * Reads of many Facts, such as a request for every predicate of every Field of a Dataset, run one
 * query per stage for the whole set, never one per Fact or per target; the events of the Facts
 * changed since the commit are read in one query too.
 */
public final class CatalogSnapshot {
  private final StoreTransaction transaction;
  private final String domain;
  private final long commit;
  private @Nullable String policyInForce;

  CatalogSnapshot(StoreTransaction transaction, String domain, long commit) {
    this.transaction = transaction;
    this.domain = domain;
    this.commit = commit;
  }

  public String domain() {
    return domain;
  }

  public long commit() {
    return commit;
  }

  public String urn() {
    return Urns.catalogSnapshot(domain, commit);
  }

  // Finding elements

  /**
   * The elements whose existence Fact is selected at this commit (see {@code
   * docs/semantic-catalog/public-api.md}, "Finding elements").
   *
   * @param kind only elements of this kind; all kinds when absent
   * @param parent only the elements with this parent, such as the Fields of a Dataset; any parent
   *     when absent
   */
  public List<Knowledge.Element> findElements(@Nullable ElementKind kind, @Nullable String parent) {
    if (commit == 0) {
      return List.of();
    }
    return transaction.presentedElements(domain, kind, parent, commit).stream()
        .map(row -> new Knowledge.Element(row.element(), row.kind(), row.parent()))
        .toList();
  }

  // Requesting knowledge

  /**
   * Answers a sequence of requests for knowledge at this commit (see {@code
   * docs/semantic-catalog/public-api.md}, "Requesting knowledge"). For every Fact Address of the
   * requested predicate on each target, whatever its applicability scope, it returns the selected
   * Fact or a status with its judgement material.
   *
   * @return one answer per request, in request order
   * @throws SemanticCatalogException when a request names an element that does not exist at this
   *     commit
   */
  public List<Knowledge.Answer> request(List<KnowledgeRequest> requests) {
    var targets = resolveTargets(requests);
    var elements = new LinkedHashSet<String>();
    targets.values().forEach(elements::addAll);
    var addresses = new HashMap<String, List<FactAddressRow>>();
    for (var address : transaction.namedAddressesOf(domain, elements, commit)) {
      addresses
          .computeIfAbsent(address.element() + " " + address.predicate(), key -> new ArrayList<>())
          .add(address);
    }
    var addressUrns = new LinkedHashSet<String>();
    addresses.values().forEach(rows -> rows.forEach(row -> addressUrns.add(row.factAddress())));
    var reader = new Reader();
    reader.loadAddresses(addressUrns);
    reader.loadPresentedObservations(addressUrns);

    var answers = new ArrayList<Knowledge.Answer>();
    for (var request : requests) {
      var atAddresses = new ArrayList<Knowledge.AtAddress>();
      for (var element : targets.getOrDefault(request, List.of())) {
        for (var address : addresses.getOrDefault(element + " " + request.predicate(), List.of())) {
          atAddresses.add(
              new Knowledge.AtAddress(
                  address.factAddress(),
                  address.element(),
                  address.predicate(),
                  address.scopeDescription(),
                  address.scopeEntities(),
                  reader.knowledge(address.factAddress())));
        }
      }
      answers.add(new Knowledge.Answer(request, atAddresses));
    }
    return answers;
  }

  private Map<KnowledgeRequest, List<String>> resolveTargets(List<KnowledgeRequest> requests) {
    var named = new LinkedHashSet<String>();
    for (var request : requests) {
      if (request.target() instanceof KnowledgeRequest.Target.Element element) {
        named.add(element.element());
      }
    }
    var existing = new HashSet<String>();
    if (commit > 0) {
      transaction.elementsAt(domain, named, commit).forEach(row -> existing.add(row.element()));
    }
    var edgeQuestions = new ArrayList<StoreTransaction.EdgeQuestion>();
    for (var request : requests) {
      if (request.target() instanceof KnowledgeRequest.Target.Edge edge) {
        edgeQuestions.add(
            new StoreTransaction.EdgeQuestion(
                edge.kind(),
                Projection.canonicalEnd(edge.from()),
                Projection.canonicalEnd(edge.to())));
      }
    }
    var edges =
        commit == 0
            ? Map.<Integer, List<ElementRow>>of()
            : transaction.edgesBetween(domain, edgeQuestions, commit);
    var targets = new LinkedHashMap<KnowledgeRequest, List<String>>();
    var edgeIndex = 0;
    for (var request : requests) {
      if (request.target() instanceof KnowledgeRequest.Target.Edge) {
        targets.put(
            request,
            edges.getOrDefault(edgeIndex++, List.of()).stream().map(ElementRow::element).toList());
        continue;
      }
      targets.put(
          request,
          switch (request.target()) {
            case KnowledgeRequest.Target.Element element -> {
              if (!existing.contains(element.element())) {
                throw new SemanticCatalogException(
                    "Target not found: element " + element.element() + " at commit " + commit);
              }
              yield List.of(element.element());
            }
            case KnowledgeRequest.Target.Edge ignored ->
                throw new IllegalStateException("Edges are resolved above");
          });
    }
    return targets;
  }

  // Review

  /**
   * The review list at this commit (see {@code docs/semantic-catalog/public-api.md}, "Retrieving
   * the review list"): candidates and open retirement proposals whose latest evaluation requested
   * consent, and conflicting Fact Addresses, grouped per table with the domain-wide items in a
   * group of their own. Items answered unknown with no new dependency reference since are left out.
   *
   * @return the groups, tables in the order of their canonical form and the domain-wide group last;
   *     a group's items ordered by the commit at which the need for judgement arose
   */
  public List<Review.Group> reviewList(Review.Query query) {
    if (commit == 0) {
      return List.of();
    }
    var reader = new Reader();
    var asked = transaction.factsAskedForConsent(domain, commit);
    reader.keep(transaction.factRecordsOf(domain, asked));
    var waiting =
        asked.stream()
            .filter(
                fact -> {
                  var record = reader.records.get(fact);
                  return record != null
                      && record.awaitingConsent(policyInForce())
                      && !record.unknownAnswerPending();
                })
            .toList();
    var conflicts = transaction.conflictsAt(domain, commit);
    var addressUrns = new LinkedHashSet<String>();
    waiting.forEach(fact -> addressUrns.add(reader.addressOf(fact)));
    conflicts.forEach(conflict -> addressUrns.add(conflict.factAddress()));
    reader.loadAddresses(addressUrns);
    var addresses = new HashMap<String, FactAddressRow>();
    for (var row : transaction.addressesAt(domain, addressUrns, commit)) {
      addresses.put(row.factAddress(), row);
    }
    var tables = new Tables(addresses.values().stream().map(FactAddressRow::element).toList());
    var logs = transaction.factLogsOf(domain, waiting, commit);
    var presented = new LinkedHashSet<>(waiting);
    conflicts.forEach(conflict -> presented.addAll(conflict.eligibleFacts()));
    var presentations = transaction.presentationsOf(domain, presented);
    var waitingSet = Set.copyOf(waiting);

    var items = new ArrayList<Review.Item>();
    for (var fact : waiting) {
      var record = reader.requireRecord(fact);
      var address = Objects.requireNonNull(addresses.get(reader.addressOf(fact)));
      var target = tables.target(address);
      var earlier = presentations.getOrDefault(fact, List.of());
      var log = logs.getOrDefault(fact, List.of());
      if (record.is(FactState.CANDIDATE)) {
        var evaluation = evaluation(log, record, Transition.CONFIRM);
        var premises = new ArrayList<Knowledge.RevisionContent>();
        var premiseItems = new ArrayList<String>();
        for (var premise : reader.premisesOf(fact)) {
          if (reader.records.containsKey(premise)) {
            premises.add(reader.content(premise));
            if (waitingSet.contains(premise)) {
              premiseItems.add(premise);
            }
          }
        }
        var selection = reader.selections.get(address.factAddress());
        var selected = selection == null ? null : selection.selectedFact();
        items.add(
            new Review.Candidate(
                target,
                reader.content(fact),
                evaluation,
                premises,
                premiseItems,
                reader.cascade(fact, FactState.CANDIDATE),
                selected != null && !selected.equals(fact) ? selected : null,
                Urns.position(evaluation.logRecord()).commit(),
                earlier));
      } else {
        var evaluation = evaluation(log, record, Transition.RETIRE);
        var proposalAt = record.openRetirementProposal().orElseThrow();
        var proposal =
            (LogRecord.ProposeRetire)
                log.stream()
                    .filter(positioned -> positioned.position().equals(proposalAt))
                    .findFirst()
                    .orElseThrow()
                    .record();
        items.add(
            new Review.Retirement(
                target,
                reader.content(fact),
                Urns.logRecord(domain, proposalAt),
                proposal,
                evaluation,
                reader.cascade(fact, FactState.CONFIRMED),
                Urns.position(evaluation.logRecord()).commit(),
                earlier));
      }
    }
    for (var conflict : conflicts) {
      var address = Objects.requireNonNull(addresses.get(conflict.factAddress()));
      var earlier = new ArrayList<Long>();
      for (var fact : conflict.eligibleFacts()) {
        earlier.addAll(presentations.getOrDefault(fact, List.of()));
      }
      var selected = conflict.selectedFact();
      items.add(
          new Review.Conflict(
              tables.target(address),
              conflict.eligibleFacts().stream().map(reader::content).toList(),
              selected == null ? null : reader.content(selected),
              conflict.fromCommit(),
              earlier.stream().distinct().sorted().toList()));
    }
    return group(items, tables, query);
  }

  /**
   * The user policy version in force at this commit; a consent request made under an earlier one no
   * longer stands, because setting a version sends it back to policy evaluation.
   */
  private String policyInForce() {
    if (policyInForce == null) {
      policyInForce =
          transaction
              .policyVersionAt(domain, commit)
              .orElseThrow(
                  () -> new IllegalStateException("Domain " + domain + " has no policy version"));
    }
    return policyInForce;
  }

  /**
   * What the policy evaluation model reads to evaluate a Fact at this commit: for a candidate, its
   * confirmation with its inputs read and its premises expanded; for a confirmed Fact with an open
   * retirement proposal, that retirement.
   *
   * @return the subject, or empty when the Fact is neither
   */
  public Optional<PolicyEvaluationModel.Subject> policyEvaluationSubject(String fact) {
    if (commit == 0) {
      return Optional.empty();
    }
    var reader = new Reader();
    reader.keep(transaction.factRecordsOf(domain, List.of(fact)));
    var record = reader.records.get(fact);
    if (record == null) {
      return Optional.empty();
    }
    var addressUrn = reader.addressOf(fact);
    reader.loadAddresses(List.of(addressUrn));
    var address =
        transaction.addressesAt(domain, List.of(addressUrn), commit).stream()
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No Fact Address " + addressUrn));
    var target = new Tables(List.of(address.element())).target(address);
    var log = transaction.factLogsOf(domain, List.of(fact), commit).getOrDefault(fact, List.of());
    if (record.is(FactState.CANDIDATE)) {
      var acceptedAt = record.latestAcceptance().orElseThrow();
      var acceptance =
          (LogRecord.Accept)
              log.stream()
                  .filter(positioned -> positioned.position().equals(acceptedAt))
                  .findFirst()
                  .orElseThrow(() -> new IllegalStateException("No acceptance at " + acceptedAt))
                  .record();
      var premises =
          reader.premisesOf(fact).stream()
              .filter(reader.records::containsKey)
              .map(reader::content)
              .toList();
      return Optional.of(
          new PolicyEvaluationModel.Subject.Confirmation(
              target, reader.content(fact), acceptance.inputsRead(), premises));
    }
    var proposalAt = record.openRetirementProposal();
    if (record.is(FactState.CONFIRMED) && proposalAt.isPresent()) {
      var proposal =
          log.stream()
              .filter(positioned -> positioned.position().equals(proposalAt.get()))
              .findFirst()
              .orElseThrow(() -> new IllegalStateException("No proposal at " + proposalAt.get()))
              .record();
      return Optional.of(
          new PolicyEvaluationModel.Subject.Retirement(
              target,
              reader.content(fact),
              Urns.logRecord(domain, proposalAt.get()),
              (LogRecord.ProposeRetire) proposal));
    }
    return Optional.empty();
  }

  /** The latest evaluation of a transition, read from the Fact's log. */
  private Review.Evaluation evaluation(
      List<PositionedRecord> log, FactRecord record, Transition transition) {
    var at = record.latestEvaluation(transition).orElseThrow().at();
    var evaluation =
        log.stream()
            .filter(positioned -> positioned.position().equals(at))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No evaluation at " + at));
    return new Review.Evaluation(
        Urns.logRecord(domain, at), (LogRecord.EvaluatePolicy) evaluation.record());
  }

  private static List<Review.Group> group(
      List<Review.Item> items, Tables tables, Review.Query query) {
    var byTable = new java.util.TreeMap<String, List<Review.Item>>();
    var domainWide = new ArrayList<Review.Item>();
    for (var item : items) {
      var target = item.target();
      if ((query.kind() != null && item.kind() != query.kind())
          || (query.element() != null && !query.element().equals(target.element()))
          || (query.factAddress() != null && !query.factAddress().equals(target.factAddress()))) {
        continue;
      }
      var itemTables = tables.of(target.element());
      if (itemTables.isEmpty()) {
        if (query.position().root()) {
          domainWide.add(item);
        }
        continue;
      }
      for (var table : itemTables) {
        if (query.position().contains(table)) {
          byTable.computeIfAbsent(table, key -> new ArrayList<>()).add(item);
        }
      }
    }
    var order =
        java.util.Comparator.comparingLong(Review.Item::needArose)
            .thenComparing(item -> item.target().factAddress());
    var groups = new ArrayList<Review.Group>();
    byTable.forEach(
        (table, list) -> groups.add(new Review.Group(table, list.stream().sorted(order).toList())));
    if (!domainWide.isEmpty()) {
      groups.add(new Review.Group(null, domainWide.stream().sorted(order).toList()));
    }
    return groups;
  }

  /**
   * The tables each element belongs to for review: a Dataset's own, a Field's Dataset's, and those
   * at the ends of an identifier correspondence. Other elements belong to the domain as a whole.
   */
  private final class Tables {
    private final Map<String, ElementRow> elements = new HashMap<>();

    Tables(Collection<String> targets) {
      load(targets);
      var more = new LinkedHashSet<String>();
      for (var row : List.copyOf(elements.values())) {
        if (row.parent() != null) {
          more.add(row.parent());
        }
        if (row.edge() != null && row.kind() == ElementKind.IDENTIFIER_CORRESPONDENCE) {
          more.addAll(LogRecordJson.readStrings(row.edge().endA()));
          more.addAll(LogRecordJson.readStrings(row.edge().endB()));
        }
      }
      load(more);
      var parents = new LinkedHashSet<String>();
      for (var row : List.copyOf(elements.values())) {
        if (row.parent() != null) {
          parents.add(row.parent());
        }
      }
      load(parents);
    }

    private void load(Collection<String> urns) {
      var missing = urns.stream().filter(urn -> !elements.containsKey(urn)).toList();
      for (var row : transaction.elementsAt(domain, missing, commit)) {
        elements.put(row.element(), row);
      }
    }

    List<String> of(String element) {
      var row = elements.get(element);
      if (row == null) {
        return List.of();
      }
      return switch (row.kind()) {
        case DATASET, FIELD -> tableOf(element).stream().toList();
        case IDENTIFIER_CORRESPONDENCE -> {
          var ends = new LinkedHashSet<String>();
          var edge = row.edge();
          if (edge != null) {
            for (var end : List.of(edge.endA(), edge.endB())) {
              for (var field : LogRecordJson.readStrings(end)) {
                tableOf(field).ifPresent(ends::add);
              }
            }
          }
          yield List.copyOf(ends);
        }
        default -> List.of();
      };
    }

    private Optional<String> tableOf(String element) {
      var row = elements.get(element);
      if (row == null) {
        return Optional.empty();
      }
      if (row.kind() == ElementKind.DATASET) {
        return Optional.ofNullable(row.technicalReference());
      }
      return row.parent() == null ? Optional.empty() : tableOf(row.parent());
    }

    Review.Target target(FactAddressRow address) {
      var row = elements.get(address.element());
      if (row == null) {
        throw new IllegalStateException("Element " + address.element() + " is not loaded");
      }
      return new Review.Target(
          address.element(),
          row.kind(),
          row.technicalReference(),
          address.factAddress(),
          address.predicate(),
          address.scopeDescription(),
          address.scopeEntities());
    }
  }

  /**
   * What a proposed user policy version would do to the evaluable candidates at this commit, which
   * must be the latest (see {@code docs/semantic-catalog/public-api.md}, "Checking a user policy
   * version").
   */
  Review.PolicyCheck policyCheck(LogRecord.PolicyVersion proposed) {
    var current =
        transaction
            .projectionSource(domain)
            .latestPolicyVersion()
            .orElseThrow(() -> new IllegalStateException("Domain " + domain + " has no policy"));
    var reader = new Reader();
    var candidates = transaction.candidateRecords(domain);
    reader.keep(candidates);
    var premises = new LinkedHashSet<String>();
    candidates.forEach(row -> premises.addAll(reader.requireRecord(row.fact()).factDependencies()));
    premises.removeAll(reader.records.keySet());
    reader.keep(transaction.factRecordsOf(domain, premises));
    var addresses = candidates.stream().map(FactRecordRow::factAddress).distinct().toList();
    for (var selection : transaction.selectionsAt(domain, addresses, commit)) {
      reader.selections.put(selection.factAddress(), selection);
    }
    var adoptedInline = new ArrayList<String>();
    var toModel = new ArrayList<String>();
    for (var row : candidates) {
      var fact = row.fact();
      var evaluable =
          reader.premisesOf(fact).stream()
              .allMatch(
                  premise -> {
                    var record = reader.records.get(premise);
                    return record != null
                        && (record.is(FactState.CONFIRMED)
                            || (record.is(FactState.CANDIDATE)
                                && record.awaitingConsent(policyInForce())));
                  });
      if (!evaluable) {
        continue;
      }
      var selection = reader.selections.get(row.factAddress());
      var anotherSelected =
          selection != null
              && selection.selectedFact() != null
              && !selection.selectedFact().equals(fact);
      switch (PolicyEvaluator.evaluateConfirmation(
          proposed, reader.requireRecord(fact).checks(), anotherSelected)) {
        case PolicyEvaluator.Decision.Adopt ignored -> adoptedInline.add(fact);
        case PolicyEvaluator.Decision.Undecided ignored -> toModel.add(fact);
        case PolicyEvaluator.Decision.RequestConsent ignored -> {}
      }
    }
    return new Review.PolicyCheck(
        urn(),
        Urns.logRecord(domain, current.position()),
        (LogRecord.PolicyVersion) current.record(),
        adoptedInline,
        toModel);
  }

  // Fact Records and Fact Revisions

  /**
   * A Fact Record's state at this commit.
   *
   * @return empty when the Fact did not exist or was not accepted by this commit
   */
  public Optional<FactState> factState(String fact) {
    return recordAt(fact, endOfCommit()).flatMap(FactRecord::state);
  }

  /**
   * The Fact's latest Fact Revision at this commit.
   *
   * @return empty when the Fact did not exist or was not accepted by this commit
   */
  public Optional<FactRevision> latestRevision(String fact) {
    return recordAt(fact, endOfCommit()).flatMap(FactRecord::revision);
  }

  /**
   * A Fact Revision by URN.
   *
   * @return empty when no Fact Event at or before this commit produced it
   */
  public Optional<FactRevision> revision(String revision) {
    if (!inDomain(revision, Urns.FACT_REVISION)) {
      return Optional.empty();
    }
    var event = Urns.revisionEvent(revision);
    if (event.commit() > commit) {
      return Optional.empty();
    }
    return recordAt(Urns.revisionFact(revision), event)
        .flatMap(FactRecord::revision)
        .filter(found -> found.event().equals(event));
  }

  /**
   * A Fact Revision by URN with what a read at this commit adds: whether new candidates exist at
   * its Fact Address, and whether an input it depends on has a newer observation.
   *
   * @return empty when no Fact Event at or before this commit produced it
   */
  public Optional<Knowledge.RevisionContent> revisionContent(String revision) {
    return revision(revision)
        .map(
            found -> {
              var reader = new Reader();
              reader.loadAddresses(List.of(found.factAddress()));
              return reader.content(found);
            });
  }

  /**
   * The Fact Record as it stood after the Fact's last event at or before a position. The current
   * record answers when its latest event is at or before the position; otherwise the Fact changed
   * since, and its events are folded up to the position.
   */
  private Optional<FactRecord> recordAt(String fact, LogPosition upTo) {
    if (commit == 0 || !inDomain(fact, Urns.FACT)) {
      return Optional.empty();
    }
    return transaction.factRecord(domain, fact).flatMap(row -> recordAt(row, upTo));
  }

  private Optional<FactRecord> recordAt(FactRecordRow current, LogPosition upTo) {
    if (current.latestEvent().compareTo(upTo) <= 0) {
      return Optional.of(
          FactRecord.restore(LogRecordJson.fromStored(current.record(), FactRecord.Stored.class)));
    }
    var records = transaction.factRecords(domain, current.fact(), upTo.commit());
    if (records.isEmpty()) {
      return Optional.empty();
    }
    var folded = FactRecord.fold(records, upTo);
    return folded.state().isPresent() ? Optional.of(folded) : Optional.empty();
  }

  private LogPosition endOfCommit() {
    return new LogPosition(commit, Integer.MAX_VALUE);
  }

  private boolean inDomain(String urn, String kind) {
    return Urns.kind(urn).equals(kind) && Urns.domain(urn).equals(domain);
  }

  /**
   * The Fact Records, selections, and observations one read needs at this commit, loaded a set at a
   * time and kept for the rest of the read.
   */
  private final class Reader {
    private final Map<String, FactRecord> records = new HashMap<>();
    private final Map<String, String> addressOfFact = new HashMap<>();
    private final Map<String, List<String>> factsAtAddress = new HashMap<>();
    private final Map<String, AddressSelectionRow> selections = new HashMap<>();
    private final Map<String, LogPosition> latestObservations = new HashMap<>();
    private final Set<String> loadedResources = new HashSet<>();

    /** Loads the selection and the Fact Records of every Fact at some Fact Addresses. */
    void loadAddresses(Collection<String> addresses) {
      if (commit == 0 || addresses.isEmpty()) {
        return;
      }
      for (var selection : transaction.selectionsAt(domain, addresses, commit)) {
        selections.put(selection.factAddress(), selection);
      }
      for (var address : addresses) {
        factsAtAddress.putIfAbsent(address, new ArrayList<>());
      }
      var rows = transaction.factRecordsAt(domain, addresses);
      keep(rows);
      for (var row : rows) {
        if (records.containsKey(row.fact())) {
          factsAtAddress
              .computeIfAbsent(row.factAddress(), key -> new ArrayList<>())
              .add(row.fact());
        }
      }
      var premises = new LinkedHashSet<String>();
      for (var address : addresses) {
        for (var fact : factsAtAddress.getOrDefault(address, List.of())) {
          var record = records.get(fact);
          if (record != null && record.is(FactState.CANDIDATE)) {
            premises.addAll(record.factDependencies());
          }
        }
      }
      premises.removeAll(records.keySet());
      keep(transaction.factRecordsOf(domain, premises));
    }

    /**
     * Keeps the records of some Facts at this commit, leaving out Facts not accepted by then. A
     * Fact unchanged since the commit is restored from its current record. The Facts changed since
     * have their events read in one query and folded up to the commit.
     */
    private void keep(List<FactRecordRow> rows) {
      var end = endOfCommit();
      var changed = new ArrayList<String>();
      for (var row : rows) {
        addressOfFact.put(row.fact(), row.factAddress());
        if (row.latestEvent().compareTo(end) <= 0) {
          records.put(
              row.fact(),
              FactRecord.restore(LogRecordJson.fromStored(row.record(), FactRecord.Stored.class)));
        } else {
          changed.add(row.fact());
        }
      }
      transaction
          .factLogsOf(domain, changed, commit)
          .forEach(
              (fact, log) -> {
                var folded = FactRecord.fold(log, end);
                if (folded.state().isPresent()) {
                  records.put(fact, folded);
                }
              });
    }

    Knowledge knowledge(String address) {
      var selection = selections.get(address);
      var facts = factsAtAddress.getOrDefault(address, List.of());
      var candidates = factsIn(facts, FactState.CANDIDATE);
      if (selection != null && selection.conflict()) {
        var selected = selection.selectedFact();
        return new Knowledge.Conflict(
            selection.eligibleFacts().stream().map(this::content).toList(),
            selected == null ? null : content(selected));
      }
      if (selection != null && selection.selectedFact() != null) {
        return new Knowledge.Selected(content(selection.selectedFact()), candidates);
      }
      if (!candidates.isEmpty()) {
        return new Knowledge.AwaitingJudgement(
            candidates.stream().map(fact -> new Knowledge.Candidate(fact, waiting(fact))).toList());
      }
      var ended = new ArrayList<String>(factsIn(facts, FactState.REJECTED));
      ended.addAll(factsIn(facts, FactState.RETIRED));
      ended.sort(null);
      return new Knowledge.MissingKnowledge(ended);
    }

    private List<String> factsIn(List<String> facts, FactState state) {
      return facts.stream().filter(fact -> requireRecord(fact).is(state)).toList();
    }

    private Knowledge.Waiting waiting(String candidate) {
      var record = requireRecord(candidate);
      var address = addressOfFact.get(candidate);
      for (var dependency : record.factDependencies()) {
        var premise = records.get(dependency);
        var premiseAddress = addressOfFact.get(dependency);
        if (premiseAddress != null
            && !premiseAddress.equals(address)
            && (premise == null || !premise.is(FactState.CONFIRMED))) {
          return Knowledge.Waiting.DEPENDENCIES;
        }
      }
      return record.awaitingConsent(policyInForce())
          ? Knowledge.Waiting.CONSENT
          : Knowledge.Waiting.POLICY_EVALUATION;
    }

    private Knowledge.RevisionContent content(String fact) {
      return content(
          requireRecord(fact)
              .revision()
              .orElseThrow(() -> new IllegalStateException("Fact " + fact + " has no revision")));
    }

    Knowledge.RevisionContent content(FactRevision revision) {
      loadObservations(revision.dependencies());
      var newCandidates =
          factsAtAddress.getOrDefault(revision.factAddress(), List.of()).stream()
              .anyMatch(
                  fact ->
                      !fact.equals(revision.fact()) && requireRecord(fact).is(FactState.CANDIDATE));
      var newerObservation =
          revision.dependencies().stream()
              .anyMatch(
                  dependency ->
                      dependency instanceof Dependency.OnInput onInput
                          && Optional.ofNullable(
                                  latestObservations.get(onInput.inputResource().urnValue()))
                              .map(
                                  latest ->
                                      latest.compareTo(
                                              Urns.position(onInput.observation().urnValue()))
                                          > 0)
                              .orElse(false));
      return new Knowledge.RevisionContent(revision, newCandidates, newerObservation);
    }

    /**
     * Loads, in one query, the latest observations of the inputs the presented Facts at some Fact
     * Addresses depend on: the selected and eligible ones.
     */
    void loadPresentedObservations(Collection<String> addresses) {
      var dependencies = new ArrayList<Dependency>();
      for (var address : addresses) {
        var selection = selections.get(address);
        if (selection == null) {
          continue;
        }
        var presented = new LinkedHashSet<>(selection.eligibleFacts());
        if (selection.selectedFact() != null) {
          presented.add(selection.selectedFact());
        }
        for (var fact : presented) {
          var record = records.get(fact);
          if (record != null) {
            dependencies.addAll(record.dependencies());
          }
        }
      }
      loadObservations(dependencies);
    }

    /** Loads the latest observations of the inputs some dependencies name that are not loaded. */
    private void loadObservations(Collection<Dependency> dependencies) {
      var resources = new LinkedHashSet<String>();
      for (var dependency : dependencies) {
        if (dependency instanceof Dependency.OnInput onInput) {
          resources.add(onInput.inputResource().urnValue());
        }
      }
      resources.removeAll(loadedResources);
      if (resources.isEmpty()) {
        return;
      }
      latestObservations.putAll(transaction.latestObservationsAt(domain, resources, commit));
      loadedResources.addAll(resources);
    }

    /** The Fact Address of a loaded Fact. */
    String addressOf(String fact) {
      var address = addressOfFact.get(fact);
      if (address == null) {
        throw new IllegalStateException("Fact " + fact + " is not loaded at commit " + commit);
      }
      return address;
    }

    /** The premise dependencies of a loaded Fact: the Facts it depends on at other addresses. */
    List<String> premisesOf(String fact) {
      var address = addressOfFact.get(fact);
      return requireRecord(fact).factDependencies().stream()
          .filter(
              dependency -> {
                var at = addressOfFact.get(dependency);
                return at != null && !at.equals(address);
              })
          .toList();
    }

    /**
     * The Facts in a state that a rejection or retirement of a Fact would move with it, directly or
     * through others: the candidates resting on it, or the confirmed Facts confirmed resting on it.
     */
    List<String> cascade(String fact, FactState state) {
      var source = transaction.projectionSource(domain);
      var found = new LinkedHashSet<String>();
      var pending = new ArrayDeque<String>();
      pending.add(fact);
      while (!pending.isEmpty()) {
        var next = pending.poll();
        var dependents =
            List.copyOf(
                state == FactState.CONFIRMED
                    ? source.confirmedDependents(next)
                    : source.dependents(next));
        keep(
            transaction.factRecordsOf(
                domain, dependents.stream().filter(d -> !records.containsKey(d)).toList()));
        for (var dependent : dependents) {
          var record = records.get(dependent);
          if (record == null || !record.is(state) || found.contains(dependent)) {
            continue;
          }
          var dependencies =
              state == FactState.CONFIRMED
                  ? record.confirmationFactDependencies()
                  : record.factDependencies();
          var address = addressOfFact.get(dependent);
          if (dependencies.contains(next) && !addressOf(next).equals(address)) {
            found.add(dependent);
            pending.add(dependent);
          }
        }
      }
      return List.copyOf(found);
    }

    private FactRecord requireRecord(String fact) {
      var record = records.get(fact);
      if (record == null) {
        throw new IllegalStateException("Fact " + fact + " is not loaded at commit " + commit);
      }
      return record;
    }
  }
}
