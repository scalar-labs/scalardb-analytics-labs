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
import com.scalar.db.analytics.labs.semantic.model.LogRecordJson;
import com.scalar.db.analytics.labs.semantic.model.SemanticCatalogException;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.projection.FactRecord;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.AddressSelectionRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.ElementRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.FactAddressRow;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.FactRecordRow;
import com.scalar.db.analytics.labs.semantic.projection.Projection;
import com.scalar.db.analytics.labs.semantic.storage.StoreTransaction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
      return record.awaitingConsent()
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

    private FactRecord requireRecord(String fact) {
      var record = records.get(fact);
      if (record == null) {
        throw new IllegalStateException("Fact " + fact + " is not loaded at commit " + commit);
      }
      return record;
    }
  }
}
