/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.model.Check;
import com.scalar.db.analytics.labs.semantic.model.Document;
import com.scalar.db.analytics.labs.semantic.model.ElementIdentity;
import com.scalar.db.analytics.labs.semantic.model.IdentityKey;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.LogRecordJson;
import com.scalar.db.analytics.labs.semantic.model.MatchingCondition;
import com.scalar.db.analytics.labs.semantic.model.PhysicalCatalogReference;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.UsageFeedback;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.InputKind;
import com.scalar.db.analytics.labs.semantic.projection.IdentityLookup;
import com.scalar.db.analytics.labs.semantic.projection.Projection;
import com.scalar.db.analytics.labs.semantic.storage.StoreTransaction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;

/**
 * What one Generator run reads: its triggers, and the domain's records at the latest commit, read
 * in the transaction that records the run's output.
 */
public final class GeneratorContext {
  private final StoreTransaction transaction;
  private final String domain;
  private final Projection projection;
  private final IdentityLookup lookup;
  private final List<Trigger> triggers;
  private final JsonNode provenance;
  private @Nullable Map<String, OptionalLong> latestVersions;
  private final Map<String, Optional<Table>> tables = new HashMap<>();

  /**
   * @param triggers the URNs of the log records that called for the run
   * @param provenance the provenance every acceptance and retirement proposal of the run records
   */
  public GeneratorContext(
      StoreTransaction transaction,
      String domain,
      Projection projection,
      List<String> triggers,
      JsonNode provenance) {
    this.transaction = transaction;
    this.domain = domain;
    this.projection = projection;
    this.lookup = transaction.identityLookup(domain);
    this.provenance = provenance;
    this.triggers = new ArrayList<>();
    for (var trigger : triggers) {
      resolve(trigger).ifPresent(this.triggers::add);
    }
  }

  /** A log record that called for the run, as the Generator reads it. */
  public sealed interface Trigger {
    /** A new observation of an Input Resource. */
    record Observed(String inputResource, String observation) implements Trigger {}

    /** The confirmation of a Fact of a predicate of an element. */
    record Confirmed(String fact, String element, ElementKind kind, String predicate)
        implements Trigger {}

    /** An acceptance of a Fact of a predicate of an element. */
    record Accepted(String fact, String element, ElementKind kind, String predicate)
        implements Trigger {}
  }

  /** One table's physical schema as observed last. */
  public record Table(
      String inputResource,
      String tableIdentifier,
      Optional<PhysicalCatalogReference.Table> reference,
      String observation,
      Observed observed) {}

  /** What the latest observation of a table's physical schema found. */
  public sealed interface Observed {
    /**
     * The table was read.
     *
     * @param tableMetadata the table metadata the connector derived for the version observed last,
     *     the source-independent form of what the source declares
     * @param complete whether every item of the table could be read
     * @param sourceMetadataSchema the URN of the JSON Schema the connector publishes for the source
     *     metadata of the version observed last
     */
    record Read(JsonNode tableMetadata, boolean complete, String sourceMetadataSchema)
        implements Observed {}

    /** The source reports that the table does not exist. */
    record DoesNotExist() implements Observed {}
  }

  /**
   * A Fact as a Generator reads it.
   *
   * @param revision the URN of the Fact Revision of its latest acceptance, which a dependency on it
   *     names: later evaluations and confirmations do not change what it states
   * @param dependencies the dependency references in effect
   * @param generator the Generator that made its latest acceptance, if one did
   * @param identityKey the canonical identity key of a closed-range Fact
   * @param retirementProposed whether a retirement proposal against it is open
   */
  public record Fact(
      String urn,
      String element,
      String predicate,
      FactState state,
      String revision,
      JsonNode value,
      List<Check> checks,
      List<com.scalar.db.analytics.labs.semantic.model.Dependency> dependencies,
      @Nullable String generator,
      @Nullable String identityKey,
      boolean retirementProposed) {
    /**
     * Whether the Fact can still take a retirement proposal that changes something: a candidate, or
     * a confirmed Fact with no proposal open.
     */
    public boolean retirable() {
      return state == FactState.CANDIDATE || (state == FactState.CONFIRMED && !retirementProposed);
    }
  }

  public String domain() {
    return domain;
  }

  public List<Trigger> triggers() {
    return List.copyOf(triggers);
  }

  public JsonNode provenance() {
    return provenance;
  }

  // Physical schemas

  /** Every physical-schema Input Resource of the domain, oldest first. */
  public List<String> physicalSchemas() {
    return List.copyOf(latestVersions().keySet());
  }

  /** The latest table metadata version received per physical schema, read once per run. */
  private Map<String, OptionalLong> latestVersions() {
    var versions = latestVersions;
    if (versions == null) {
      versions = new LinkedHashMap<>();
      for (var resource : transaction.physicalSchemaResources(domain)) {
        versions.put(resource.inputResource(), resource.latestVersion());
      }
      latestVersions = versions;
    }
    return versions;
  }

  /** A table's physical schema as observed last; empty when it has no observation. */
  public Optional<Table> table(String inputResource) {
    var held = tables.get(inputResource);
    if (held != null) {
      return held;
    }
    var read = readTable(inputResource);
    tables.put(inputResource, read);
    return read;
  }

  private Optional<Table> readTable(String inputResource) {
    var row = projection.inputResource(inputResource);
    if (row.isEmpty() || row.get().kind() != InputKind.PHYSICAL_SCHEMA) {
      return Optional.empty();
    }
    var tableIdentifier = row.get().tableIdentifier();
    var observation = projection.latestObservation(inputResource);
    if (tableIdentifier == null || observation.isEmpty()) {
      return Optional.empty();
    }
    var resource =
        transaction
            .logRecordAt(domain, Urns.position(inputResource))
            .map(positioned -> positioned.record())
            .filter(LogRecord.InputResource.class::isInstance)
            .map(LogRecord.InputResource.class::cast)
            .orElseThrow();
    var content =
        transaction
            .logRecordAt(domain, Urns.position(observation.get()))
            .map(positioned -> positioned.record())
            .filter(LogRecord.InputObservation.class::isInstance)
            .map(record -> ((LogRecord.InputObservation) record).content())
            .orElseThrow();
    Observed observed;
    if ("does-not-exist".equals(content.path("state").asText())) {
      observed = new Observed.DoesNotExist();
    } else {
      var version = latestVersions().getOrDefault(inputResource, OptionalLong.empty());
      var derived =
          version.isPresent()
              ? transaction.derivedRecord(domain, tableIdentifier, version.getAsLong())
              : Optional.<JsonNode>empty();
      if (derived.isEmpty() || !derived.get().path("tableMetadata").isObject()) {
        return Optional.empty();
      }
      observed =
          new Observed.Read(
              derived.get().get("tableMetadata"),
              derived.get().path("complete").asBoolean(),
              Urns.sourceMetadataSchema(
                  domain, derived.get().path("sourceMetadataSchema").asText()));
    }
    return Optional.of(
        new Table(
            inputResource,
            tableIdentifier,
            Optional.ofNullable(resource.table()),
            observation.get(),
            observed));
  }

  /** The physical-schema Input Resource a Fact depends on, if it depends on one. */
  public Optional<String> physicalSchemaOf(String fact) {
    var slice = projection.fact(fact);
    if (slice.isEmpty()) {
      return Optional.empty();
    }
    for (var dependency : slice.get().record().dependencies()) {
      if (dependency
          instanceof com.scalar.db.analytics.labs.semantic.model.Dependency.OnInput input) {
        var resource = input.inputResource().urnValue();
        if (projection
            .inputResource(resource)
            .filter(row -> row.kind() == InputKind.PHYSICAL_SCHEMA)
            .isPresent()) {
          return Optional.of(resource);
        }
      }
    }
    return Optional.empty();
  }

  // Input Resources and data observations

  /** The kind of an Input Resource, if it exists. */
  public Optional<InputKind> inputKind(String inputResource) {
    return projection.inputResource(inputResource).map(row -> row.kind());
  }

  /** The check and target a data-observation Input Resource records, if it is one. */
  public Optional<String> dataCheck(String inputResource) {
    return projection
        .inputResource(inputResource)
        .filter(row -> row.kind() == InputKind.DATA_OBSERVATION)
        .map(row -> row.dataCheck());
  }

  /** The data-observation Input Resource of a check and target, if one exists. */
  public Optional<String> dataObservationResource(String dataCheck) {
    return lookup.inputResourceByDataCheck(dataCheck);
  }

  /** The latest observation of an Input Resource, if it has one. */
  public Optional<String> latestObservation(String inputResource) {
    return projection.latestObservation(inputResource);
  }

  /** The content of an Input Observation. */
  public Optional<JsonNode> observationContent(String observation) {
    if (!Urns.kind(observation).equals(Urns.INPUT_OBSERVATION)) {
      return Optional.empty();
    }
    return transaction
        .logRecordAt(domain, Urns.position(observation))
        .map(positioned -> positioned.record())
        .filter(LogRecord.InputObservation.class::isInstance)
        .map(record -> ((LogRecord.InputObservation) record).content());
  }

  /**
   * The source metadata schema a derived record names, by its URN: the JSON Schema a connector
   * publishes for its source metadata documents.
   */
  public JsonNode sourceMetadataSchema(String urn) {
    return transaction
        .sourceMetadataSchema(domain, Urns.identifier(urn))
        .orElseThrow(() -> new IllegalStateException("No source metadata schema " + urn));
  }

  /** The usage feedback an observed Input Resource holds, if it is usage feedback. */
  public Optional<UsageFeedback> usageFeedback(Trigger.Observed observed) {
    if (inputKind(observed.inputResource()).orElse(null) != InputKind.USAGE_FEEDBACK) {
      return Optional.empty();
    }
    return observationContent(observed.observation())
        .map(content -> LogRecordJson.fromStored(content, UsageFeedback.class));
  }

  // Documents

  /**
   * The latest observation of one registered document.
   *
   * @param inputResource the URN of the document's Input Resource
   * @param observation the URN of its latest Input Observation
   */
  public record DocumentObservation(String inputResource, String observation, Document document) {}

  /** The latest observation of every document registered in the domain, oldest document first. */
  public List<DocumentObservation> documents() {
    var documents = new ArrayList<DocumentObservation>();
    for (var resource : transaction.inputResources(domain, InputKind.DOCUMENT)) {
      var observation = latestObservation(resource);
      if (observation.isEmpty()) {
        continue;
      }
      var content = observationContent(observation.get());
      if (content.isPresent()) {
        documents.add(
            new DocumentObservation(
                resource,
                observation.get(),
                LogRecordJson.fromStored(content.get(), Document.class)));
      }
    }
    return documents;
  }

  // Elements

  /** The kind of an element, if it exists. */
  public Optional<ElementKind> elementKind(String element) {
    return projection.element(element).map(row -> row.kind());
  }

  /** The Dataset a Field belongs to, if it exists. */
  public Optional<String> parent(String element) {
    return projection.element(element).map(row -> row.parent());
  }

  /** The matching condition of an identifier correspondence, if the edge is one. */
  public Optional<MatchingCondition> matchingCondition(String correspondence) {
    return projection
        .element(correspondence)
        .filter(row -> row.kind() == ElementKind.IDENTIFIER_CORRESPONDENCE)
        .flatMap(row -> Optional.ofNullable(row.edge()))
        .map(edge -> LogRecordJson.fromStored(edge.identifyingPart(), ElementIdentity.class))
        .filter(ElementIdentity.IdentifierCorrespondence.class::isInstance)
        .map(identity -> ((ElementIdentity.IdentifierCorrespondence) identity).condition());
  }

  /**
   * A Field's column as data checks read it: the table whose latest physical schema observation its
   * existence rests on, and the column's name in that table.
   *
   * @param existence the Field's existence Fact
   */
  public record Column(
      String field,
      String dataset,
      Table table,
      PhysicalCatalogReference.Table reference,
      String name,
      Fact existence) {}

  /** The column of a Field, when its existence and table are known. */
  public Optional<Column> column(String field) {
    var row = projection.element(field);
    if (row.isEmpty() || row.get().kind() != ElementKind.FIELD || row.get().parent() == null) {
      return Optional.empty();
    }
    var canonical = row.get().physicalCatalogReference();
    var existence = closedRangeFact(field, Vocabulary.EXISTENCE, IdentityKey.choice(EXISTS));
    if (canonical == null || existence.isEmpty()) {
      return Optional.empty();
    }
    var table = physicalSchemaOf(existence.get().urn()).flatMap(this::table);
    if (table.isEmpty() || table.get().reference().isEmpty()) {
      return Optional.empty();
    }
    var reference = table.get().reference().get();
    var prefix = reference.canonical() + ".";
    if (!canonical.startsWith(prefix)) {
      return Optional.empty();
    }
    var name = canonical.substring(prefix.length());
    if (!stillObserved(table.get(), name)) {
      return Optional.empty();
    }
    return Optional.of(
        new Column(
            field,
            requireNonNull(row.get().parent()),
            table.get(),
            reference,
            name,
            existence.get()));
  }

  /**
   * Whether the table's latest observation still has the column: the table was read and lists it,
   * or may hide columns. A column the source no longer has is not read, even while the retirement
   * of its Field waits for consent.
   */
  private static boolean stillObserved(Table table, String column) {
    if (!(table.observed() instanceof Observed.Read read)) {
      return false;
    }
    if (read.tableMetadata().path("columnsMayBeHidden").asBoolean(false)) {
      return true;
    }
    for (var listed : read.tableMetadata().path("columns")) {
      if (column.equals(listed.path("name").asText())) {
        return true;
      }
    }
    return false;
  }

  private static final String EXISTS = "exists";

  /**
   * A Dataset's table as its existence rests on it: the table whose latest physical schema
   * observation the existence Fact depends on.
   */
  public record DatasetTable(
      String dataset, Table table, PhysicalCatalogReference.Table reference, Fact existence) {}

  /** The table of a Dataset, when its existence and table are known and the table was read. */
  public Optional<DatasetTable> datasetTable(String dataset) {
    var row = projection.element(dataset);
    if (row.isEmpty() || row.get().kind() != ElementKind.DATASET) {
      return Optional.empty();
    }
    var existence = closedRangeFact(dataset, Vocabulary.EXISTENCE, IdentityKey.choice(EXISTS));
    if (existence.isEmpty()) {
      return Optional.empty();
    }
    var table = physicalSchemaOf(existence.get().urn()).flatMap(this::table);
    if (table.isEmpty()
        || table.get().reference().isEmpty()
        || !(table.get().observed() instanceof Observed.Read)) {
      return Optional.empty();
    }
    return Optional.of(
        new DatasetTable(dataset, table.get(), table.get().reference().get(), existence.get()));
  }

  /** The Dataset or Field a Physical Catalog reference identifies, if one exists. */
  public Optional<String> element(PhysicalCatalogReference reference) {
    return lookup.physicalCatalogElement(
        reference.elementKind(), IdentityKey.choice(reference.canonical()).canonical());
  }

  /** The Fields of a Dataset, with the canonical form of the column each refers to. */
  public List<Child> fields(String dataset) {
    var fields = new ArrayList<Child>();
    for (var field : transaction.childElements(domain, dataset)) {
      projection
          .element(field)
          .filter(row -> row.physicalCatalogReference() != null)
          .ifPresent(
              row -> fields.add(new Child(field, requireNonNull(row.physicalCatalogReference()))));
    }
    return fields;
  }

  /**
   * An element whose parent is another, with the canonical form of its Physical Catalog reference.
   */
  public record Child(String element, String reference) {}

  /** The identifier correspondence with exactly this identity, if one exists. */
  public Optional<String> correspondence(ElementIdentity.IdentifierCorrespondence identity) {
    return edge(identity);
  }

  /** The edge with exactly this identity, if one exists. Every end must be resolved to a URN. */
  public Optional<String> edge(ElementIdentity.Edge identity) {
    return lookup.edge(identity.kind(), Projection.edgeIdentity(identity));
  }

  /** Every element of a kind, whatever its Facts' states, oldest first. */
  public List<String> elements(ElementKind kind) {
    return transaction.elementsOfKind(domain, kind);
  }

  /** The identity of a concept relationship, if the edge is one. */
  public Optional<ElementIdentity.ConceptRelationship> conceptRelationship(String edge) {
    return projection
        .element(edge)
        .filter(row -> row.kind() == ElementKind.CONCEPT_RELATIONSHIP)
        .flatMap(row -> Optional.ofNullable(row.edge()))
        .map(found -> LogRecordJson.fromStored(found.identifyingPart(), ElementIdentity.class))
        .filter(ElementIdentity.ConceptRelationship.class::isInstance)
        .map(ElementIdentity.ConceptRelationship.class::cast);
  }

  // Facts

  /** A Fact by URN. */
  public Optional<Fact> fact(String urn) {
    var slice = projection.fact(urn);
    if (slice.isEmpty()) {
      return Optional.empty();
    }
    var record = slice.get().record();
    var revision = record.revision();
    var accepted = record.latestAcceptance();
    var state = record.state();
    var address = projection.address(record.factAddress());
    if (revision.isEmpty() || accepted.isEmpty() || state.isEmpty() || address.isEmpty()) {
      return Optional.empty();
    }
    var generator = revision.get().provenance().path("generator");
    return Optional.of(
        new Fact(
            urn,
            address.get().row().element(),
            address.get().row().predicate(),
            state.get(),
            Urns.factRevision(urn, accepted.get()),
            revision.get().value(),
            revision.get().checks(),
            record.dependencies(),
            generator.isTextual() ? generator.asText() : null,
            slice.get().row().identityKey(),
            record.openRetirementProposal().isPresent()));
  }

  /** The closed-range Fact with an identity key at an element's empty-scope Fact Address. */
  public Optional<Fact> closedRangeFact(String element, String predicate, IdentityKey key) {
    return lookup
        .emptyScopeAddress(element, predicate)
        .flatMap(address -> lookup.closedRangeFact(address, key.canonical()))
        .flatMap(this::fact);
  }

  /** The Facts at an element's empty-scope Fact Address of a predicate. */
  public List<Fact> factsAt(String element, String predicate) {
    var address = lookup.emptyScopeAddress(element, predicate).flatMap(projection::address);
    if (address.isEmpty()) {
      return List.of();
    }
    return address.get().facts().stream()
        .sorted()
        .map(this::fact)
        .flatMap(Optional::stream)
        .toList();
  }

  /** The URN of a Fact's Fact Address. */
  public Optional<String> factAddressOf(String fact) {
    return projection.factAddress(fact);
  }

  /**
   * Whether a Fact's Fact Address is found by element and predicate alone, the one {@link #factsAt}
   * reads: its applicability scope is empty and its predicate is not free-description.
   */
  public boolean atMatchableAddress(String fact) {
    return projection
        .factAddress(fact)
        .flatMap(projection::address)
        .map(address -> address.row().matchable())
        .orElse(false);
  }

  /** The predicates of an element's empty-scope Fact Addresses, such as its uniqueness ones. */
  public List<String> predicates(String element) {
    return transaction.addressesOf(domain, List.of(element), Long.MAX_VALUE).stream()
        .filter(row -> row.scopeDescription() == null && row.scopeEntities().isEmpty())
        .map(row -> row.predicate())
        .distinct()
        .toList();
  }

  /** The Facts whose dependencies in effect name a Fact. */
  public List<Fact> dependents(String fact) {
    return projection.dependents(fact).stream()
        .sorted()
        .map(this::fact)
        .flatMap(Optional::stream)
        .toList();
  }

  /** The Facts that depend on an observation of an Input Resource, oldest first. */
  public List<Fact> factsDependingOn(String inputResource) {
    return transaction.factsDependingOn(domain, inputResource).stream()
        .map(this::fact)
        .flatMap(Optional::stream)
        .toList();
  }

  private Optional<Trigger> resolve(String trigger) {
    if (!Urns.kind(trigger).equals(Urns.LOG_RECORD)) {
      return Optional.empty();
    }
    var record = transaction.logRecordAt(domain, Urns.position(trigger));
    if (record.isEmpty()) {
      return Optional.empty();
    }
    return switch (record.get().record()) {
      case LogRecord.InputObservation observation ->
          Optional.of(
              new Trigger.Observed(
                  observation.inputResource().urnValue(),
                  Urns.of(domain, Urns.INPUT_OBSERVATION, record.get().position())));
      case LogRecord.Confirm confirm ->
          factTrigger(confirm.fact().urnValue())
              .map(
                  found ->
                      new Trigger.Confirmed(
                          found.fact(), found.element(), found.kind(), found.predicate()));
      case LogRecord.Accept accept ->
          factTrigger(accept.fact().urnValue())
              .map(
                  found ->
                      new Trigger.Accepted(
                          found.fact(), found.element(), found.kind(), found.predicate()));
      default -> Optional.empty();
    };
  }

  private record FactTrigger(String fact, String element, ElementKind kind, String predicate) {}

  private Optional<FactTrigger> factTrigger(String fact) {
    var address = projection.factAddress(fact).flatMap(projection::address);
    if (address.isEmpty()) {
      return Optional.empty();
    }
    var element = address.get().row().element();
    return projection
        .element(element)
        .map(row -> new FactTrigger(fact, element, row.kind(), address.get().row().predicate()));
  }

  private static String requireNonNull(@Nullable String value) {
    if (value == null) {
      throw new IllegalStateException("A value read as present is absent");
    }
    return value;
  }
}
