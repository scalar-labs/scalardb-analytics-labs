/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.scalar.db.analytics.labs.semantic.model.Dependency;
import com.scalar.db.analytics.labs.semantic.model.ElementIdentity;
import com.scalar.db.analytics.labs.semantic.model.FactRevision;
import com.scalar.db.analytics.labs.semantic.model.Hierarchy;
import com.scalar.db.analytics.labs.semantic.model.Knowledge;
import com.scalar.db.analytics.labs.semantic.model.KnowledgeRequest;
import com.scalar.db.analytics.labs.semantic.model.Languages;
import com.scalar.db.analytics.labs.semantic.model.LogPosition;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.LogRecordJson;
import com.scalar.db.analytics.labs.semantic.model.Review;
import com.scalar.db.analytics.labs.semantic.model.SemanticCatalogException;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.CheckResult;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.InputKind;
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
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
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
  private final Languages languages;
  private final long commit;
  private @Nullable String policyInForce;

  /**
   * @param languages the languages the domain declares, for which a language-qualified predicate
   *     can be missing knowledge
   */
  CatalogSnapshot(StoreTransaction transaction, String domain, Languages languages, long commit) {
    this.transaction = transaction;
    this.domain = domain;
    this.languages = languages;
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
    return findElements(kind, parent, null);
  }

  /**
   * The elements whose existence Fact is selected at this commit, optionally only those a business
   * term refers to (see {@code docs/semantic-catalog/public-api.md}, "Finding elements").
   *
   * @param writtenForm only the elements that the selected referenced-element Facts of the business
   *     terms with this selected written form point to; the written form is matched exactly, never
   *     interpreted. Any element when absent.
   */
  public List<Knowledge.Element> findElements(
      @Nullable ElementKind kind, @Nullable String parent, @Nullable String writtenForm) {
    if (commit == 0) {
      return List.of();
    }
    var elements = transaction.presentedElements(domain, kind, parent, commit).stream();
    if (writtenForm != null) {
      var terms =
          transaction.presentedElements(domain, ElementKind.BUSINESS_TERM, null, commit).stream()
              .map(ElementRow::element)
              .toList();
      var named =
          selectedValues(terms, Vocabulary.WRITTEN_FORM).stream()
              .filter(selected -> selected.value().isTextual())
              .filter(selected -> selected.value().asText().equals(writtenForm))
              .map(Selected::element)
              .distinct()
              .toList();
      var referenced = new HashSet<String>();
      selectedValues(named, Vocabulary.REFERENCED_ELEMENT)
          .forEach(selected -> referenced.add(selected.value().asText()));
      elements = elements.filter(row -> referenced.contains(row.element()));
    }
    return elements
        .map(row -> new Knowledge.Element(row.element(), row.kind(), row.parent()))
        .toList();
  }

  /** The value of the selected Fact at one Fact Address of an element. */
  private record Selected(String element, JsonNode value) {}

  /**
   * The values of the selected Facts at the empty-scope Fact Addresses of a predicate on some
   * elements, for the predicates whose value names a record or a written form. A language-qualified
   * predicate has one such Fact Address per language, so an element can have several values, one
   * per language.
   *
   * @return the values, leaving out elements with no selected Fact for the predicate
   */
  private List<Selected> selectedValues(Collection<String> elements, String predicate) {
    var values = new ArrayList<Selected>();
    var addresses =
        transaction.addressesOf(domain, elements, commit).stream()
            .filter(address -> address.matchable() && address.predicate().equals(predicate))
            .toList();
    if (addresses.isEmpty()) {
      return values;
    }
    var elementOf = new HashMap<String, String>();
    addresses.forEach(address -> elementOf.put(address.factAddress(), address.element()));
    var selected = new LinkedHashMap<String, String>();
    for (var selection : transaction.selectionsAt(domain, elementOf.keySet(), commit)) {
      if (selection.selectedFact() != null) {
        selected.put(selection.selectedFact(), elementOf.get(selection.factAddress()));
      }
    }
    var reader = new Reader();
    reader.keep(transaction.factRecordsOf(domain, selected.keySet()));
    selected.forEach(
        (fact, element) -> {
          var record = reader.records.get(fact);
          if (record != null) {
            record
                .revision()
                .ifPresent(revision -> values.add(new Selected(element, revision.value())));
          }
        });
    return values;
  }

  /**
   * The kind each Dimension among some elements has: the kind its selected dimension-kind Fact
   * states. A Dimension with no selected kind, or a kind not defined, is left out, and its
   * closed-core predicates are those of every Dimension.
   */
  private Map<String, Vocabulary.DimensionKind> dimensionKinds(Collection<ElementRow> elements) {
    var dimensions =
        elements.stream()
            .filter(row -> row.kind() == ElementKind.DIMENSION)
            .map(ElementRow::element)
            .toList();
    var kinds = new HashMap<String, Vocabulary.DimensionKind>();
    if (dimensions.isEmpty()) {
      return kinds;
    }
    for (var selected : selectedValues(dimensions, Vocabulary.DIMENSION_KIND)) {
      if (selected.value().isTextual()) {
        Vocabulary.DimensionKind.of(selected.value().asText())
            .ifPresent(kind -> kinds.put(selected.element(), kind));
      }
    }
    return kinds;
  }

  // Requesting knowledge

  /**
   * Answers a sequence of requests for knowledge at this commit (see {@code
   * docs/semantic-catalog/public-api.md}, "Requesting knowledge"), returning confirmed knowledge
   * only.
   *
   * @see #request(List, Knowledge.View)
   */
  public List<Knowledge.Answer> request(List<KnowledgeRequest> requests) {
    return request(requests, Knowledge.View.CONFIRMED);
  }

  /**
   * Answers a sequence of requests for knowledge at this commit (see {@code
   * docs/semantic-catalog/public-api.md}, "Requesting knowledge"). For every Fact Address of the
   * requested predicate on each target, whatever its applicability scope, it returns the selected
   * Fact or a status with its judgement material, as far as the view includes it. In both views,
   * {@link Knowledge.Answer#missing} lists the requested predicates with missing knowledge.
   *
   * @return one answer per request, in request order
   * @throws SemanticCatalogException when a request names an element that does not exist at this
   *     commit
   */
  public List<Knowledge.Answer> request(List<KnowledgeRequest> requests, Knowledge.View view) {
    var targets = resolveTargets(requests);
    var elements = new LinkedHashMap<String, ElementRow>();
    targets.values().forEach(rows -> rows.forEach(row -> elements.put(row.element(), row)));
    var addresses = new HashMap<String, List<FactAddressRow>>();
    for (var address : transaction.addressesOf(domain, elements.keySet(), commit)) {
      addresses.computeIfAbsent(address.element(), key -> new ArrayList<>()).add(address);
    }
    var dimensionKinds = dimensionKinds(elements.values());
    var requested = new LinkedHashSet<String>();
    var perRequest = new ArrayList<List<FactAddressRow>>();
    var missingPerRequest = new ArrayList<List<Knowledge.Missing>>();
    var reader = new Reader();
    reader.elements.putAll(elements);
    for (var request : requests) {
      var atAddresses = new ArrayList<FactAddressRow>();
      var missing = new ArrayList<Knowledge.Missing>();
      for (var element : targets.getOrDefault(request, List.of())) {
        var ofElement = addresses.getOrDefault(element.element(), List.of());
        var predicate = request.predicate();
        var dimensionKind = dimensionKinds.get(element.element());
        var asked =
            predicate != null
                ? List.of(predicate)
                : dimensionKind != null
                    ? Vocabulary.closedCore(dimensionKind)
                    : Vocabulary.closedCore(element.kind());
        for (var address : ofElement) {
          if (predicate == null
              || (address.predicate().equals(predicate) && address.predicateDefinition() == null)) {
            atAddresses.add(address);
          }
        }
        for (var name : asked) {
          if (Vocabulary.languageQualified(name)) {
            for (var language : languages.declared()) {
              missing.add(new Knowledge.Missing(element.element(), name, language));
            }
          } else {
            missing.add(new Knowledge.Missing(element.element(), name));
          }
        }
      }
      for (var address : atAddresses) {
        reader.addresses.put(address.factAddress(), address);
        requested.add(address.factAddress());
      }
      perRequest.add(atAddresses);
      missingPerRequest.add(missing);
    }
    reader.loadAddresses(requested);
    reader.loadPresentedObservations(requested);
    reader.prepare(reader.records.keySet());

    var answers = new ArrayList<Knowledge.Answer>();
    for (var i = 0; i < requests.size(); i++) {
      var atAddresses = perRequest.get(i).stream().map(reader::atAddress).toList();
      // A requested predicate has missing knowledge when it has no Fact Address of its own, or
      // every one it has offers missing knowledge; a language-qualified one, per declared
      // language.
      var missing =
          missingPerRequest.get(i).stream()
              .filter(
                  asked ->
                      atAddresses.stream()
                          .filter(
                              at ->
                                  at.element().equals(asked.element())
                                      && at.predicate().equals(asked.predicate())
                                      && Objects.equals(at.language(), asked.language())
                                      && at.predicateDefinition() == null)
                          .allMatch(at -> at.knowledge() instanceof Knowledge.MissingKnowledge))
              .toList();
      var shown = new ArrayList<Knowledge.AtAddress>();
      for (var at : atAddresses) {
        at.knowledge()
            .in(view)
            .ifPresent(
                knowledge ->
                    shown.add(
                        new Knowledge.AtAddress(
                            at.factAddress(),
                            at.element(),
                            at.predicate(),
                            at.language(),
                            at.predicateDefinition(),
                            at.scopeDescription(),
                            at.scopeEntities(),
                            knowledge)));
      }
      answers.add(new Knowledge.Answer(requests.get(i), shown, missing));
    }
    return answers;
  }

  // Reading along the hierarchy

  /**
   * Reads along the hierarchy of data sources, namespaces, and tables at this commit (see {@code
   * docs/semantic-catalog/public-api.md}, "Reading along the hierarchy"). A table is read as one
   * unit: its Dataset, every Field, the Measures based on it, and the identifier correspondences it
   * is an end of, each with the result of requesting all its predicates. Every level carries its
   * counts, and a read at the root adds the domain-wide elements.
   *
   * @param position the domain root, a data source, a namespace, or a table
   * @param depth above a table, whether the read goes to the position's direct children or to every
   *     table unit below it; a table is always read as a unit
   * @return the level at the position; a position with no tables has zero counts and no children
   */
  public Hierarchy.Level read(Review.Position position, Hierarchy.Depth depth) {
    return read(position, depth, Knowledge.View.CONFIRMED);
  }

  /**
   * Reads along the hierarchy, as {@link #read(Review.Position, Hierarchy.Depth)} does, with each
   * element's knowledge in a view. The counts are the same in either view.
   */
  public Hierarchy.Level read(
      Review.Position position, Hierarchy.Depth depth, Knowledge.View view) {
    var presented =
        commit == 0
            ? List.<ElementRow>of()
            : transaction.presentedElements(domain, null, null, commit);
    var placement = new Placement(presented.stream().map(ElementRow::element).toList());
    var datasets = new TreeMap<String, ElementRow>();
    var placed = new HashMap<String, List<ElementRow>>();
    var domainWide = new ArrayList<ElementRow>();
    for (var row : presented) {
      var tables = placement.of(row.element());
      if (tables.isEmpty()) {
        domainWide.add(row);
      }
      for (var table : tables) {
        if (position.contains(table)) {
          placed.computeIfAbsent(table, key -> new ArrayList<>()).add(row);
          if (row.kind() == ElementKind.DATASET) {
            datasets.put(table, row);
          }
        }
      }
    }
    var read = new LinkedHashSet<String>();
    datasets
        .keySet()
        .forEach(
            table -> placed.getOrDefault(table, List.of()).forEach(row -> read.add(row.element())));
    if (position.root()) {
      domainWide.forEach(row -> read.add(row.element()));
    }
    var predicates = new HashMap<String, Knowledge.Answer>();
    var requests = read.stream().map(KnowledgeRequest::all).toList();
    for (var answer : request(requests, view)) {
      var target = (KnowledgeRequest.Target.Element) answer.request().target();
      predicates.put(target.element(), answer);
    }
    var reviewItems = new HashMap<String, List<Review.Item>>();
    var domainWideItems = new ArrayList<Review.Item>();
    for (var group : reviewList(new Review.Query(position, null, null, null))) {
      if (group.table() == null) {
        domainWideItems.addAll(group.items());
      } else {
        reviewItems.put(group.table(), group.items());
      }
    }

    var hierarchy = new HierarchyBuilder(datasets, placed, predicates, reviewItems, depth);
    var table = position.table();
    if (table != null) {
      var registration = Objects.requireNonNull(position.registration());
      var namespace = Objects.requireNonNull(position.namespace());
      var canonical =
          Urns.encode(registration) + "." + Urns.encode(namespace) + "." + Urns.encode(table);
      return hierarchy.has(canonical)
          ? hierarchy.table(canonical, true)
          : new Hierarchy.Table(
              registration, namespace, table, canonical, new Tally().counts(), null);
    }
    if (position.namespace() != null) {
      return hierarchy.namespace(
          Objects.requireNonNull(position.registration()), position.namespace(), true);
    }
    if (position.registration() != null) {
      return hierarchy.dataSource(position.registration(), true);
    }
    var tally = new Tally();
    var dataSources = new ArrayList<Hierarchy.DataSource>();
    for (var registration : hierarchy.registrations()) {
      var dataSource = hierarchy.dataSource(registration, depth == Hierarchy.Depth.RECURSIVE);
      dataSources.add(dataSource);
      tally.add(hierarchy.tallyOfRegistration(registration));
    }
    var wide = new ArrayList<Hierarchy.ElementKnowledge>();
    for (var row : domainWide) {
      var knowledge = hierarchy.knowledgeOf(row);
      wide.add(knowledge);
      tally.addElement(knowledge);
    }
    tally.review.addAll(domainWideItems);
    return new Hierarchy.Root(tally.counts(), dataSources, wide);
  }

  /** The sets a level's counts are the sizes of, so an element in two tables counts once above. */
  private static final class Tally {
    final Set<String> confirmed = new HashSet<>();
    final Set<Review.Item> review = new HashSet<>();
    final Set<String> missing = new HashSet<>();

    void add(Tally other) {
      confirmed.addAll(other.confirmed);
      review.addAll(other.review);
      missing.addAll(other.missing);
    }

    /**
     * Adds an element's Fact Addresses with a selected Fact, and its closed-core predicates with
     * missing knowledge. Both are the same in either view, so the counts do not depend on it.
     */
    void addElement(Hierarchy.ElementKnowledge element) {
      var answer = element.predicates();
      for (var address : answer.addresses()) {
        var selected =
            switch (address.knowledge()) {
              case Knowledge.Selected ignored -> true;
              case Knowledge.Conflict conflict -> conflict.selected() != null;
              case Knowledge.AwaitingJudgement ignored -> false;
              case Knowledge.MissingKnowledge ignored -> false;
            };
        if (selected) {
          confirmed.add(address.factAddress());
        }
      }
      for (var missingPredicate : answer.missing()) {
        missing.add(
            missingPredicate.element()
                + " "
                + missingPredicate.predicate()
                + " "
                + missingPredicate.language());
      }
    }

    Hierarchy.Counts counts() {
      return new Hierarchy.Counts(confirmed.size(), review.size(), missing.size());
    }
  }

  /** Builds the levels of one hierarchy read from what it loaded. */
  private static final class HierarchyBuilder {
    private final SortedMap<String, ElementRow> datasets;
    private final Map<String, List<ElementRow>> placed;
    private final Map<String, Knowledge.Answer> predicates;
    private final Map<String, List<Review.Item>> reviewItems;
    private final Hierarchy.Depth depth;

    /**
     * The tables below the position: those of the presented Datasets, and those the review list
     * groups items under, such as a newly observed table whose Dataset's existence awaits consent.
     */
    private final SortedSet<String> tables = new TreeSet<>();

    HierarchyBuilder(
        SortedMap<String, ElementRow> datasets,
        Map<String, List<ElementRow>> placed,
        Map<String, Knowledge.Answer> predicates,
        Map<String, List<Review.Item>> reviewItems,
        Hierarchy.Depth depth) {
      this.datasets = datasets;
      this.placed = placed;
      this.predicates = predicates;
      this.reviewItems = reviewItems;
      this.depth = depth;
      tables.addAll(datasets.keySet());
      tables.addAll(reviewItems.keySet());
    }

    boolean has(String table) {
      return tables.contains(table);
    }

    /** The decoded registration, namespace, and table of a table's canonical form. */
    private static List<String> names(String canonical) {
      return Arrays.stream(canonical.split("\\.")).map(Urns::decode).toList();
    }

    List<String> registrations() {
      return tables.stream().map(table -> names(table).get(0)).distinct().toList();
    }

    private List<String> namespaces(String registration) {
      return tables.stream()
          .map(HierarchyBuilder::names)
          .filter(names -> names.get(0).equals(registration))
          .map(names -> names.get(1))
          .distinct()
          .toList();
    }

    private List<String> tables(String registration, String namespace) {
      return tables.stream()
          .filter(
              table -> {
                var names = names(table);
                return names.get(0).equals(registration) && names.get(1).equals(namespace);
              })
          .toList();
    }

    Hierarchy.ElementKnowledge knowledgeOf(ElementRow row) {
      var answer = predicates.get(row.element());
      if (answer == null) {
        throw new IllegalStateException("Element " + row.element() + " was not requested");
      }
      return new Hierarchy.ElementKnowledge(
          new Knowledge.Element(row.element(), row.kind(), row.parent()),
          row.physicalCatalogReference(),
          answer);
    }

    private Tally tallyOfTable(String table) {
      var tally = new Tally();
      if (datasets.containsKey(table)) {
        for (var row : placed.getOrDefault(table, List.of())) {
          tally.addElement(knowledgeOf(row));
        }
      }
      tally.review.addAll(reviewItems.getOrDefault(table, List.of()));
      return tally;
    }

    private Tally tallyOfNamespace(String registration, String namespace) {
      var tally = new Tally();
      tables(registration, namespace).forEach(table -> tally.add(tallyOfTable(table)));
      return tally;
    }

    Tally tallyOfRegistration(String registration) {
      var tally = new Tally();
      namespaces(registration)
          .forEach(namespace -> tally.add(tallyOfNamespace(registration, namespace)));
      return tally;
    }

    Hierarchy.Table table(String canonical, boolean expand) {
      var names = names(canonical);
      Hierarchy.TableUnit unit = null;
      if (expand && datasets.containsKey(canonical)) {
        var rows = placed.getOrDefault(canonical, List.of());
        var dataset = knowledgeOf(Objects.requireNonNull(datasets.get(canonical)));
        unit =
            new Hierarchy.TableUnit(
                dataset,
                ofKind(rows, ElementKind.FIELD),
                ofKind(rows, ElementKind.MEASURE),
                ofKind(rows, ElementKind.IDENTIFIER_CORRESPONDENCE));
      }
      return new Hierarchy.Table(
          names.get(0),
          names.get(1),
          names.get(2),
          canonical,
          tallyOfTable(canonical).counts(),
          unit);
    }

    private List<Hierarchy.ElementKnowledge> ofKind(List<ElementRow> rows, ElementKind kind) {
      return rows.stream()
          .filter(row -> row.kind() == kind)
          .sorted(Comparator.comparing(ElementRow::element))
          .map(this::knowledgeOf)
          .toList();
    }

    /**
     * A namespace level.
     *
     * @param expand whether its tables are listed: true when the read is at the namespace or goes
     *     below it
     */
    Hierarchy.Namespace namespace(String registration, String namespace, boolean expand) {
      var tables = new ArrayList<Hierarchy.Table>();
      if (expand) {
        for (var table : tables(registration, namespace)) {
          tables.add(table(table, depth == Hierarchy.Depth.RECURSIVE));
        }
      }
      return new Hierarchy.Namespace(
          registration, namespace, tallyOfNamespace(registration, namespace).counts(), tables);
    }

    /**
     * A data source level.
     *
     * @param expand whether its namespaces are listed: true when the read is at the data source or
     *     goes below it
     */
    Hierarchy.DataSource dataSource(String registration, boolean expand) {
      var namespaces = new ArrayList<Hierarchy.Namespace>();
      if (expand) {
        for (var namespace : namespaces(registration)) {
          namespaces.add(namespace(registration, namespace, depth == Hierarchy.Depth.RECURSIVE));
        }
      }
      return new Hierarchy.DataSource(
          registration, tallyOfRegistration(registration).counts(), namespaces);
    }
  }

  private Map<KnowledgeRequest, List<ElementRow>> resolveTargets(List<KnowledgeRequest> requests) {
    var named = new LinkedHashSet<String>();
    for (var request : requests) {
      if (request.target() instanceof KnowledgeRequest.Target.Element element) {
        named.add(element.element());
      }
    }
    var existing = new HashMap<String, ElementRow>();
    if (commit > 0) {
      transaction
          .elementsAt(domain, named, commit)
          .forEach(row -> existing.put(row.element(), row));
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
    var targets = new LinkedHashMap<KnowledgeRequest, List<ElementRow>>();
    var edgeIndex = 0;
    for (var request : requests) {
      if (request.target() instanceof KnowledgeRequest.Target.Edge) {
        targets.put(request, edges.getOrDefault(edgeIndex++, List.of()));
        continue;
      }
      targets.put(
          request,
          switch (request.target()) {
            case KnowledgeRequest.Target.Element element -> {
              var row = existing.get(element.element());
              if (row == null) {
                throw new SemanticCatalogException(
                    "Target not found: element " + element.element() + " at commit " + commit);
              }
              yield List.of(row);
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
    var tables = new Placement(addresses.values().stream().map(FactAddressRow::element).toList());
    var logs = transaction.factLogsOf(domain, waiting, commit);
    var presented = new LinkedHashSet<>(waiting);
    conflicts.forEach(conflict -> presented.addAll(conflict.eligibleFacts()));
    var presentations = transaction.presentationsOf(domain, presented);
    var waitingSet = Set.copyOf(waiting);
    addresses.forEach(reader.addresses::put);
    reader.prepare(reader.records.keySet());

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
    reader.prepare(reader.records.keySet());
    var address =
        transaction.addressesAt(domain, List.of(addressUrn), commit).stream()
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No Fact Address " + addressUrn));
    var target = new Placement(List.of(address.element())).target(address);
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
      List<Review.Item> items, Placement tables, Review.Query query) {
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
   * The tables each element belongs to, for reading along the hierarchy and for grouping the review
   * list: a Dataset's own, a Field's Dataset's, a Measure's base Dataset's, and those at the ends
   * of an identifier correspondence. A Measure's base Dataset is the one its selected base-dataset
   * Fact names, so a candidate never moves it. Other elements, and a Measure without a selected
   * base Dataset, belong to the domain as a whole.
   */
  private final class Placement {
    private final Map<String, ElementRow> elements = new HashMap<>();
    private final Map<String, String> baseDatasets = new HashMap<>();

    Placement(Collection<String> targets) {
      load(targets);
      var more = new LinkedHashSet<String>();
      var measures = new ArrayList<String>();
      for (var row : List.copyOf(elements.values())) {
        if (row.parent() != null) {
          more.add(row.parent());
        }
        if (row.edge() != null && row.kind() == ElementKind.IDENTIFIER_CORRESPONDENCE) {
          more.addAll(LogRecordJson.readStrings(row.edge().endA()));
          more.addAll(LogRecordJson.readStrings(row.edge().endB()));
        }
        if (row.kind() == ElementKind.MEASURE) {
          measures.add(row.element());
        }
      }
      selectedValues(measures, Vocabulary.BASE_DATASET)
          .forEach(
              selected -> {
                if (selected.value().isTextual()) {
                  baseDatasets.put(selected.element(), selected.value().asText());
                  more.add(selected.value().asText());
                }
              });
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
        case MEASURE -> {
          var base = baseDatasets.get(element);
          if (base == null) {
            yield List.of();
          }
          var baseRow = elements.get(base);
          yield baseRow == null || baseRow.kind() != ElementKind.DATASET
              ? List.of()
              : tableOf(base).stream().toList();
        }
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
        return Optional.ofNullable(row.physicalCatalogReference());
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
          row.physicalCatalogReference(),
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
    private final Map<String, @Nullable InputKind> resourceKinds = new HashMap<>();
    private final Map<String, FactAddressRow> addresses = new HashMap<>();
    private final Map<String, ElementRow> elements = new HashMap<>();
    private final Map<String, List<PositionedRecord>> logs = new HashMap<>();
    private final Map<LogPosition, LogRecord> logRecords = new HashMap<>();
    private final Set<String> prepared = new HashSet<>();

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
            candidates.stream().map(fact -> Knowledge.Candidate.of(fact, waiting(fact))).toList());
      }
      var ended = new ArrayList<String>(factsIn(facts, FactState.REJECTED));
      ended.addAll(factsIn(facts, FactState.RETIRED));
      ended.sort(null);
      prepare(ended);
      return new Knowledge.MissingKnowledge(
          ended.stream().map(fact -> new Knowledge.Ended(content(fact), answersOf(fact))).toList());
    }

    /** What a request receives for one loaded Fact Address. */
    Knowledge.AtAddress atAddress(FactAddressRow address) {
      return new Knowledge.AtAddress(
          address.factAddress(),
          address.element(),
          address.predicate(),
          address.language(),
          address.predicateDefinition(),
          address.scopeDescription(),
          address.scopeEntities(),
          knowledge(address.factAddress()));
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
      prepareRevisions(List.of(revision));
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
      return new Knowledge.RevisionContent(
          revision,
          newCandidates,
          newerObservation,
          unexaminedItems(revision),
          referencedBy(revision));
    }

    /**
     * Prepares, a set at a time, what the content of some Facts' latest revisions at this commit
     * refers to: one query per stage for the whole set.
     */
    void prepare(Collection<String> facts) {
      prepareRevisions(
          facts.stream()
              .map(records::get)
              .filter(Objects::nonNull)
              .flatMap(record -> record.revision().stream())
              .toList());
    }

    /**
     * Prepares what the content of some Fact Revisions refers to: their Fact Addresses and
     * elements, the logs of the Facts they refer to and of their own Facts when those ended, and
     * the Input Observations and answers they refer to. Each kind is read in one query for the set.
     */
    void prepareRevisions(Collection<FactRevision> revisions) {
      var fresh = revisions.stream().filter(r -> !prepared.contains(r.urn())).toList();
      if (fresh.isEmpty()) {
        return;
      }
      var missingAddresses = new LinkedHashSet<String>();
      fresh.forEach(revision -> missingAddresses.add(revision.factAddress()));
      missingAddresses.removeAll(addresses.keySet());
      for (var row : transaction.addressesAt(domain, missingAddresses, commit)) {
        addresses.put(row.factAddress(), row);
      }
      var missingElements = new LinkedHashSet<String>();
      for (var revision : fresh) {
        var address = addresses.get(revision.factAddress());
        if (address != null) {
          missingElements.add(address.element());
        }
      }
      missingElements.removeAll(elements.keySet());
      for (var row : transaction.elementsAt(domain, missingElements, commit)) {
        elements.put(row.element(), row);
      }
      var logged = new LinkedHashSet<String>();
      var positions = new LinkedHashSet<LogPosition>();
      for (var revision : fresh) {
        var record = records.get(revision.fact());
        if (record != null && (record.is(FactState.REJECTED) || record.is(FactState.RETIRED))) {
          logged.add(revision.fact());
        }
        for (var urn : referencedUrns(revision)) {
          switch (Urns.kind(urn)) {
            case Urns.FACT_REVISION -> logged.add(Urns.revisionFact(urn));
            case Urns.FACT -> logged.add(urn);
            case Urns.INPUT_OBSERVATION, Urns.LOG_RECORD -> positions.add(Urns.position(urn));
            default -> {}
          }
        }
      }
      logged.removeAll(logs.keySet());
      positions.removeIf(
          position -> position.commit() > commit || logRecords.containsKey(position));
      logs.putAll(transaction.factLogsOf(domain, logged, commit));
      logged.forEach(fact -> logs.putIfAbsent(fact, List.of()));
      for (var positioned : transaction.logRecordsAt(domain, positions)) {
        logRecords.put(positioned.position(), positioned.record());
      }
      fresh.forEach(revision -> prepared.add(revision.urn()));
    }

    /**
     * The URNs of this domain a Fact Revision refers to, in order and once each: its dependencies,
     * its check references, and every URN in its inputs read.
     */
    private List<String> referencedUrns(FactRevision revision) {
      var urns = new LinkedHashSet<String>();
      for (var dependency : revision.dependencies()) {
        urns.add(dependency.version().urnValue());
      }
      for (var check : revision.checks()) {
        if (check.reference() != null) {
          urns.add(check.reference().urnValue());
        }
      }
      collectUrns(revision.inputsRead(), urns);
      return urns.stream().filter(this::expandable).toList();
    }

    /**
     * Whether a URN names a record of this domain whose content can be expanded. Inputs read are
     * free JSON recorded as the Generator gave them, so a string that only looks like a URN is
     * skipped rather than failing the read.
     */
    private boolean expandable(String urn) {
      try {
        if (!Urns.domain(urn).equals(domain)) {
          return false;
        }
        switch (Urns.kind(urn)) {
          case Urns.FACT_REVISION -> Urns.revisionEvent(urn);
          case Urns.INPUT_OBSERVATION, Urns.LOG_RECORD -> Urns.position(urn);
          default -> {}
        }
        return true;
      } catch (IllegalArgumentException e) {
        return false;
      }
    }

    private static void collectUrns(JsonNode node, Set<String> urns) {
      if (node.isTextual()) {
        if (node.asText().startsWith(Urns.PREFIX)) {
          urns.add(node.asText());
        }
        return;
      }
      node.elements().forEachRemaining(child -> collectUrns(child, urns));
    }

    /**
     * The content of an Input Observation as a reader of a Fact that refers to it sees it. A
     * document is shown by its name only: its text can be long, and what a candidate rests on is
     * the quotation its {@code document} check holds, so expanding the text into every read and
     * every policy evaluation subject would pass more of a document than the relevant parts (see
     * {@code docs/semantic-catalog/generators.md}, "Input passed to external models").
     */
    private JsonNode referencedContent(String resource, JsonNode content) {
      if (resourceKind(resource) != InputKind.DOCUMENT) {
        return content;
      }
      return JsonNodeFactory.instance.objectNode().set("name", content.path("name"));
    }

    private @Nullable InputKind resourceKind(String resource) {
      if (!resourceKinds.containsKey(resource)) {
        resourceKinds.put(
            resource,
            transaction
                .projectionSource(domain)
                .inputResource(resource)
                .map(row -> row.kind())
                .orElse(null));
      }
      return resourceKinds.get(resource);
    }

    /** The content of the records a prepared Fact Revision refers to, one level deep. */
    private List<Knowledge.Referenced> referencedBy(FactRevision revision) {
      var referenced = new ArrayList<Knowledge.Referenced>();
      for (var urn : referencedUrns(revision)) {
        switch (Urns.kind(urn)) {
          case Urns.FACT_REVISION ->
              referencedRevision(urn, Urns.revisionFact(urn), Urns.revisionEvent(urn))
                  .ifPresent(referenced::add);
          case Urns.FACT -> referencedRevision(urn, urn, endOfCommit()).ifPresent(referenced::add);
          case Urns.INPUT_OBSERVATION -> {
            if (logRecords.get(Urns.position(urn))
                instanceof LogRecord.InputObservation observation) {
              var resource = observation.inputResource().urnValue();
              referenced.add(
                  new Knowledge.Referenced.Observation(
                      urn, resource, referencedContent(resource, observation.content())));
            }
          }
          case Urns.LOG_RECORD -> {
            if (logRecords.get(Urns.position(urn)) instanceof LogRecord.Answer answer) {
              referenced.add(
                  new Knowledge.Referenced.Answer(urn, givenAnswer(Urns.position(urn), answer)));
            }
          }
          default -> {}
        }
      }
      return referenced;
    }

    /**
     * A Fact's revision at a position, from its log up to this commit, with its state at this
     * commit and, when it has ended by then, the answers given to it.
     */
    private Optional<Knowledge.Referenced> referencedRevision(
        String urn, String fact, LogPosition at) {
      var log = logs.getOrDefault(fact, List.of());
      if (log.isEmpty() || at.compareTo(endOfCommit()) > 0) {
        return Optional.empty();
      }
      var revision = FactRecord.fold(log, at).revision();
      var now = FactRecord.fold(log, endOfCommit()).state();
      if (revision.isEmpty() || now.isEmpty()) {
        return Optional.empty();
      }
      if (Urns.kind(urn).equals(Urns.FACT_REVISION) && !revision.get().urn().equals(urn)) {
        return Optional.empty();
      }
      var ended = now.get() == FactState.REJECTED || now.get() == FactState.RETIRED;
      return Optional.of(
          new Knowledge.Referenced.Revision(
              urn, revision.get(), now.get(), ended ? answersOf(fact) : List.of()));
    }

    /** The answers given to a Fact up to this commit, in log order. */
    List<Knowledge.GivenAnswer> answersOf(String fact) {
      if (!logs.containsKey(fact)) {
        logs.putAll(transaction.factLogsOf(domain, List.of(fact), commit));
      }
      var answers = new ArrayList<Knowledge.GivenAnswer>();
      for (var positioned : logs.getOrDefault(fact, List.of())) {
        if (positioned.record() instanceof LogRecord.Answer answer) {
          answers.add(givenAnswer(positioned.position(), answer));
        }
      }
      return answers;
    }

    private Knowledge.GivenAnswer givenAnswer(LogPosition at, LogRecord.Answer answer) {
      return new Knowledge.GivenAnswer(
          Urns.logRecord(domain, at),
          answer.transition(),
          answer.answerType(),
          answer.decidedBy(),
          answer.wording(),
          answer.reviewPresentation());
    }

    /**
     * The validation items of a prepared Fact Revision's predicate that no supporting check
     * examined.
     */
    private List<String> unexaminedItems(FactRevision revision) {
      var address = addresses.get(revision.factAddress());
      if (address == null) {
        return List.of();
      }
      var element = elements.get(address.element());
      ElementIdentity identity =
          element == null || element.edge() == null
              ? null
              : LogRecordJson.fromStored(element.edge().identifyingPart(), ElementIdentity.class);
      var examined = new HashSet<String>();
      for (var check : revision.checks()) {
        if (check.result() == CheckResult.SUPPORTED) {
          var items = check.items();
          if (items.isTextual()) {
            examined.add(items.asText());
          }
          items.elements().forEachRemaining(item -> examined.add(item.asText()));
        }
      }
      return Vocabulary.validationItems(identity, address.predicate()).stream()
          .filter(item -> !examined.contains(item))
          .toList();
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
