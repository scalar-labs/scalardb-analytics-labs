/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.scalar.db.analytics.labs.semantic.generator.Generator;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext.Fact;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext.Table;
import com.scalar.db.analytics.labs.semantic.generator.Output;
import com.scalar.db.analytics.labs.semantic.model.Check;
import com.scalar.db.analytics.labs.semantic.model.Dependency;
import com.scalar.db.analytics.labs.semantic.model.IdentityKey;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.PhysicalCatalogReference;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Scope;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.CheckResult;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.RetirementReason;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ValidationMethod;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * A schema-interpretation Generator: it maps what one table's metadata declares to candidates (see
 * {@code docs/semantic-catalog/generators.md}, "Schema interpretation"). Its targets are the tables
 * its triggers point to; each is interpreted from the table metadata of its latest observation.
 *
 * <p>Every run proposes all of what a table declares, so a run on unchanged input records nothing.
 * A Fact this Generator produced from a table that the table no longer declares gets a retirement
 * proposal, unless a data check also supports it, since what the data confirmed does not change
 * when a declaration goes.
 */
abstract class SchemaGenerator implements Generator {
  static final JsonNodeFactory JSON = JsonNodeFactory.instance;
  static final String EXISTS = "exists";
  static final IdentityKey EXISTS_KEY = IdentityKey.choice(EXISTS);

  private final String name;

  SchemaGenerator(String name) {
    this.name = name;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String version() {
    return "1";
  }

  /** The physical-schema Input Resources the run's triggers point to, in trigger order. */
  static Set<String> triggeredTables(GeneratorContext context) {
    var tables = new LinkedHashSet<String>();
    for (var trigger : context.triggers()) {
      switch (trigger) {
        case GeneratorContext.Trigger.Observed observed -> tables.add(observed.inputResource());
        case GeneratorContext.Trigger.Confirmed confirmed ->
            context.physicalSchemaOf(confirmed.fact()).ifPresent(tables::add);
      }
    }
    return tables;
  }

  @Override
  public void run(GeneratorContext context, Output output) {
    for (var resource : targets(context)) {
      var table = context.table(resource);
      if (table.isEmpty()) {
        continue;
      }
      var reference = table.get().reference();
      if (reference.isEmpty()) {
        output.missing("the Physical Catalog reference of table " + table.get().tableIdentifier());
        continue;
      }
      interpret(context, output, table.get(), reference.get());
    }
  }

  /** The tables the run interprets. */
  Collection<String> targets(GeneratorContext context) {
    return triggeredTables(context);
  }

  /** Interprets one table that has a Physical Catalog reference. */
  abstract void interpret(
      GeneratorContext context,
      Output output,
      Table table,
      PhysicalCatalogReference.Table reference);

  /**
   * The table's declarations, or empty when its latest observation found that it does not exist,
   * which table and column ingestion turns into a retirement.
   */
  static Optional<Declarations> declarations(Table table) {
    return switch (table.observed()) {
      case GeneratorContext.Observed.Read read ->
          Optional.of(Declarations.of(read.tableMetadata()));
      case GeneratorContext.Observed.DoesNotExist ignored -> Optional.empty();
    };
  }

  // Reading

  /** The confirmed existence Fact of an element. */
  static Optional<Fact> confirmedExistence(GeneratorContext context, String element) {
    return context
        .closedRangeFact(element, Vocabulary.EXISTENCE, EXISTS_KEY)
        .filter(fact -> fact.state() == FactState.CONFIRMED);
  }

  /** The Field of a column whose existence is confirmed. */
  static Optional<ConfirmedField> confirmedField(
      GeneratorContext context, PhysicalCatalogReference.Table table, String column) {
    return context
        .element(new PhysicalCatalogReference.Column(table, column))
        .flatMap(
            field ->
                confirmedExistence(context, field)
                    .map(existence -> new ConfirmedField(field, column, existence)));
  }

  /** A Field and its confirmed existence Fact. */
  record ConfirmedField(String field, String column, Fact existence) {}

  /**
   * The Fields of columns, each with its confirmed existence, or empty when any column has no Field
   * whose existence is confirmed, which the output records as missing input.
   */
  static Optional<List<ConfirmedField>> confirmedFields(
      GeneratorContext context,
      Output output,
      PhysicalCatalogReference.Table table,
      List<String> columns) {
    var fields = new ArrayList<ConfirmedField>();
    for (var column : columns) {
      var field = confirmedField(context, table, column);
      if (field.isEmpty()) {
        output.missing(
            "the confirmed existence of the Field for "
                + new PhysicalCatalogReference.Column(table, column).canonical());
        return Optional.empty();
      }
      fields.add(field.get());
    }
    return Optional.of(fields);
  }

  /** A set of Fields as a value: their URNs, sorted. */
  static JsonNode fieldSet(List<ConfirmedField> fields) {
    var value = JSON.objectNode();
    var urns = value.putArray("fields");
    fields.stream().map(ConfirmedField::field).sorted().distinct().forEach(urns::add);
    return value;
  }

  /** The identity key of a set of Fields, compared regardless of order. */
  static IdentityKey fieldSetKey(List<ConfirmedField> fields) {
    return IdentityKey.of(
        new IdentityKey.Part.References(
            fields.stream().map(field -> Ref.urn(field.field())).toList()));
  }

  /** The dependencies of a Fact about a Dataset's Fields: the observation and each existence. */
  static List<Dependency> onFields(Table table, Fact dataset, List<ConfirmedField> fields) {
    var dependencies = new ArrayList<Dependency>();
    dependencies.add(onObservation(table));
    dependencies.add(on(dataset));
    fields.stream().map(field -> on(field.existence())).distinct().forEach(dependencies::add);
    return dependencies;
  }

  static List<String> revisions(Fact dataset, List<ConfirmedField> fields) {
    var revisions = new ArrayList<String>();
    revisions.add(dataset.revision());
    fields.forEach(field -> revisions.add(field.existence().revision()));
    return revisions;
  }

  // Writing

  /** A dependency on the latest observation of a table's physical schema. */
  static Dependency onObservation(Table table) {
    return new Dependency.OnInput(Ref.urn(table.inputResource()), Ref.urn(table.observation()));
  }

  /** A dependency on the Fact Revision of a Fact's latest acceptance. */
  static Dependency on(Fact fact) {
    return new Dependency.OnFact(Ref.urn(fact.urn()), Ref.urn(fact.revision()));
  }

  /** A check that the table metadata declares a value, enforced by the source or not. */
  static Check declared(boolean enforced, JsonNode items, Table table, JsonNode details) {
    return new Check(
        enforced ? ValidationMethod.DECLARED_ENFORCED : ValidationMethod.DECLARED_UNENFORCED,
        items,
        CheckResult.SUPPORTED,
        null,
        Ref.urn(table.observation()),
        details);
  }

  /** The inputs read: the URNs of the observation and Fact Revisions the run read. */
  static JsonNode inputsRead(Table table, Collection<String> revisions) {
    ArrayNode read = JSON.arrayNode();
    read.add(table.observation());
    revisions.forEach(read::add);
    return read;
  }

  /** Proposes a Fact Address of an element with the empty scope. */
  static Ref address(Output output, Ref element, String predicate) {
    return output.add(new LogRecord.FactAddress(element, predicate, Scope.EMPTY, null));
  }

  /** Proposes a closed-range Fact and accepts it; returns the acceptance. */
  Accepted acceptClosedRange(
      GeneratorContext context,
      Output output,
      Ref address,
      JsonNode value,
      IdentityKey key,
      List<Dependency> dependencies,
      List<Check> checks,
      JsonNode inputsRead) {
    var fact = output.add(new LogRecord.Fact(address, value, key));
    var accept =
        output.add(
            new LogRecord.Accept(fact, dependencies, checks, inputsRead, context.provenance()));
    return new Accepted(fact, accept);
  }

  /**
   * Proposes a closed-range Fact about an existing element and accepts it. When another Fact is
   * confirmed at the Fact Address, the candidate reads it and depends on it, so the candidate
   * replaces it once confirmed (see {@code docs/semantic-catalog/generators.md}, "The Generator
   * contract").
   */
  Accepted acceptAbout(
      GeneratorContext context,
      Output output,
      String element,
      String predicate,
      JsonNode value,
      IdentityKey key,
      List<Dependency> dependencies,
      List<Check> checks,
      List<String> revisions,
      Table table) {
    var withReplaced = new ArrayList<>(dependencies);
    var read = new ArrayList<>(revisions);
    var others = context.factsAt(element, predicate);
    var same =
        others.stream().filter(other -> key.canonical().equals(other.identityKey())).findFirst();
    for (var other : others) {
      if (key.canonical().equals(other.identityKey())) {
        continue;
      }
      // A replacement read earlier stays, so a run after the replaced Fact is retired records
      // nothing new.
      var replacedEarlier =
          same.map(
                  found ->
                      found.dependencies().stream()
                          .anyMatch(
                              dependency ->
                                  dependency instanceof Dependency.OnFact onFact
                                      && onFact.fact().urnValue().equals(other.urn())))
              .orElse(false);
      if (other.state() == FactState.CONFIRMED || replacedEarlier) {
        var dependency =
            same.flatMap(
                    found ->
                        found.dependencies().stream()
                            .filter(
                                earlier ->
                                    earlier instanceof Dependency.OnFact onFact
                                        && onFact.fact().urnValue().equals(other.urn()))
                            .findFirst())
                .orElse(on(other));
        withReplaced.add(dependency);
        read.add(dependency.version().urnValue());
      }
    }
    return acceptClosedRange(
        context,
        output,
        address(output, Ref.urn(element), predicate),
        value,
        key,
        withReplaced,
        checks,
        inputsRead(table, read));
  }

  /** A proposed Fact and its acceptance, by temporary numbers. */
  record Accepted(Ref fact, Ref acceptance) {
    /** A dependency on the Fact Revision the acceptance produces. */
    Dependency dependency() {
      return new Dependency.OnFact(fact, acceptance);
    }
  }

  /**
   * Proposes retiring the Facts this Generator produced from a table that a run no longer yields.
   *
   * @param kept the URNs of the existing Facts the run proposed again
   * @param scope which of this Generator's Facts the run covered
   */
  void retireUndeclared(
      GeneratorContext context,
      Output output,
      Table table,
      Set<String> kept,
      Predicate<Fact> scope) {
    for (var fact : context.factsDependingOn(table.inputResource())) {
      if (name.equals(fact.generator())
          && scope.test(fact)
          && fact.retirable()
          && !kept.contains(fact.urn())
          && !supportedByData(fact)) {
        proposeRetire(
            context,
            output,
            table,
            fact,
            RetirementReason.SOURCE_DECLARATION_NO_LONGER_EXISTS,
            JSON.objectNode().put("reason", "The table metadata no longer declares it"));
      }
    }
  }

  /** Proposes retiring a Fact because of what the table's latest observation states. */
  static void proposeRetire(
      GeneratorContext context,
      Output output,
      Table table,
      Fact fact,
      RetirementReason reason,
      JsonNode details) {
    output.add(
        new LogRecord.ProposeRetire(
            Ref.urn(fact.urn()),
            reason,
            List.of(
                new Check(
                    ValidationMethod.DECLARED_ENFORCED,
                    fact.value(),
                    CheckResult.SUPPORTED,
                    null,
                    Ref.urn(table.observation()),
                    details)),
            inputsRead(table, List.of(fact.revision())),
            context.provenance()));
  }

  private static boolean supportedByData(Fact fact) {
    return fact.checks().stream()
        .anyMatch(
            check ->
                check.method() == ValidationMethod.DATA_CHECK
                    && check.result() == CheckResult.SUPPORTED);
  }

  /** The existing Fact a closed-range proposal is the same as, if any. */
  static Optional<String> existing(
      GeneratorContext context, @Nullable String element, String predicate, IdentityKey key) {
    if (element == null) {
      return Optional.empty();
    }
    return context.closedRangeFact(element, predicate, key).map(Fact::urn);
  }
}
