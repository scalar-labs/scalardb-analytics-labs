/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.model.Check;
import com.scalar.db.analytics.labs.semantic.model.ElementIdentity;
import com.scalar.db.analytics.labs.semantic.model.IdentityKey;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.PhysicalCatalogReference;
import com.scalar.db.analytics.labs.semantic.model.Urns;
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
     */
    record Read(JsonNode tableMetadata, boolean complete) implements Observed {}

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
              derived.get().get("tableMetadata"), derived.get().path("complete").asBoolean());
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

  // Elements

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
    return lookup.edge(identity.kind(), Projection.edgeIdentity(identity));
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
      case LogRecord.Confirm confirm -> {
        var fact = confirm.fact().urnValue();
        var address = projection.factAddress(fact).flatMap(projection::address);
        if (address.isEmpty()) {
          yield Optional.empty();
        }
        var element = address.get().row().element();
        yield projection
            .element(element)
            .map(
                row ->
                    new Trigger.Confirmed(
                        fact, element, row.kind(), address.get().row().predicate()));
      }
      default -> Optional.empty();
    };
  }

  private static String requireNonNull(@Nullable String value) {
    if (value == null) {
      throw new IllegalStateException("A value read as present is absent");
    }
    return value;
  }
}
