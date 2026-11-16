/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext.Table;
import com.scalar.db.analytics.labs.semantic.generator.Output;
import com.scalar.db.analytics.labs.semantic.model.Check;
import com.scalar.db.analytics.labs.semantic.model.Dependency;
import com.scalar.db.analytics.labs.semantic.model.ElementIdentity;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.MatchingCondition;
import com.scalar.db.analytics.labs.semantic.model.PhysicalCatalogReference;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.CheckResult;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ValidationMethod;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Correspondence from foreign key: a foreign key states that the referencing values denote the
 * objects of the referenced rows, so it supports an identifier correspondence from the referencing
 * Fields to the referenced ones, and that correspondence's business definition (see {@code
 * docs/semantic-catalog/generators.md}, "Schema interpretation"). Both are {@code
 * declared-enforced} when the source enforces the key and {@code declared-unenforced} otherwise.
 * Several keys stating the same correspondence support one candidate.
 *
 * <p>A pair leaves its validation item undecidable unless both columns have a mapped type other
 * than text, because text compares under a collation the table metadata does not state, an unmapped
 * or unread type could be text, and a key can reference a row an equality does not match; such a
 * correspondence is never adopted inline. A key whose referenced table is registered later is
 * interpreted once that table's Fields are confirmed, because a run triggered by a table also
 * interprets the tables whose keys reference it.
 */
final class CorrespondenceFromForeignKey extends SchemaGenerator {
  static final String NAME = "correspondence-from-foreign-key";
  static final String BUSINESS_DEFINITION = Vocabulary.BUSINESS_DEFINITION;

  CorrespondenceFromForeignKey() {
    super(NAME);
  }

  @Override
  public List<Input> inputs() {
    return List.of(
        new Input.PhysicalSchema(), new Input.Confirmed(ElementKind.FIELD, Vocabulary.EXISTENCE));
  }

  /** The triggered tables and every table with a foreign key referencing one of them. */
  @Override
  Collection<String> targets(GeneratorContext context) {
    var triggered = triggeredTables(context);
    var referenced = new HashSet<String>();
    for (var resource : triggered) {
      context
          .table(resource)
          .flatMap(Table::reference)
          .ifPresent(reference -> referenced.add(reference.canonical()));
    }
    var targets = new LinkedHashSet<>(triggered);
    for (var resource : context.physicalSchemas()) {
      var table = context.table(resource);
      if (table.isEmpty() || table.get().reference().isEmpty()) {
        continue;
      }
      var reference = table.get().reference().get();
      var references =
          foreignKeys(table.get()).stream()
              .anyMatch(
                  key ->
                      referencedTable(reference, key)
                          .map(target -> referenced.contains(target.canonical()))
                          .orElse(false));
      if (references) {
        targets.add(resource);
      }
    }
    return targets;
  }

  private static List<Declarations.Constraint.ForeignKey> foreignKeys(Table table) {
    return declarations(table).flatMap(Declarations::constraints).orElse(List.of()).stream()
        .filter(Declarations.Constraint.ForeignKey.class::isInstance)
        .map(Declarations.Constraint.ForeignKey.class::cast)
        .toList();
  }

  /** The table a foreign key references, in the referencing table's data source. */
  private static Optional<PhysicalCatalogReference.Table> referencedTable(
      PhysicalCatalogReference.Table table, Declarations.Constraint.ForeignKey key) {
    return PhysicalCatalogReference.Table.of(table.registration(), key.referencedTable());
  }

  /** The keys stating one correspondence, with its Fields. */
  private record Statement(
      PhysicalCatalogReference.Table target,
      List<ConfirmedField> from,
      List<ConfirmedField> to,
      List<Declarations.Constraint.ForeignKey> keys) {}

  @Override
  void interpret(
      GeneratorContext context,
      Output output,
      Table table,
      PhysicalCatalogReference.Table reference) {
    var declared = declarations(table);
    if (declared.flatMap(Declarations::constraints).isEmpty()) {
      return;
    }
    var statements = new LinkedHashMap<List<String>, Statement>();
    var complete = true;
    for (var key : foreignKeys(table)) {
      var target = referencedTable(reference, key);
      if (target.isEmpty()) {
        output.missing(
            "the Physical Catalog reference of the table foreign key "
                + key.name()
                + " of "
                + reference.canonical()
                + " references");
        complete = false;
        continue;
      }
      var from = confirmedFields(context, output, reference, key.columns());
      var to = confirmedFields(context, output, target.get(), key.referencedColumns());
      if (from.isEmpty() || to.isEmpty() || from.get().size() != to.get().size()) {
        complete = false;
        continue;
      }
      var identity = new ArrayList<String>();
      from.get().forEach(field -> identity.add(field.field()));
      to.get().forEach(field -> identity.add(field.field()));
      var found = target.get();
      var fromFields = from.get();
      var toFields = to.get();
      statements
          .computeIfAbsent(
              identity, ignored -> new Statement(found, fromFields, toFields, new ArrayList<>()))
          .keys()
          .add(key);
    }
    var kept = new HashSet<String>();
    for (var statement : statements.values()) {
      interpretStatement(context, output, table, declared.get(), statement, kept);
    }
    if (complete) {
      retireUndeclared(
          context, output, table, kept, fact -> fact.predicate().equals(Vocabulary.EXISTENCE));
    }
  }

  private void interpretStatement(
      GeneratorContext context,
      Output output,
      Table table,
      Declarations declared,
      Statement statement,
      Set<String> kept) {
    var referencedDeclarations =
        statement.to().stream()
            .findFirst()
            .flatMap(field -> context.physicalSchemaOf(field.existence().urn()))
            .flatMap(context::table)
            .flatMap(SchemaGenerator::declarations);
    var pairs = new ArrayList<MatchingCondition.Pair>();
    var decidable = JSON.arrayNode();
    var undecidable = JSON.arrayNode();
    for (var index = 0; index < statement.from().size(); index++) {
      var fromField = statement.from().get(index);
      var toField = statement.to().get(index);
      pairs.add(MatchingCondition.Pair.of(Ref.urn(fromField.field()), Ref.urn(toField.field())));
      var item = JSON.objectNode();
      item.putArray("pair").add(fromField.field()).add(toField.field());
      var exact =
          declared
                  .column(fromField.column())
                  .map(Declarations.Column::comparesExactly)
                  .orElse(false)
              && referencedDeclarations
                  .flatMap(found -> found.column(toField.column()))
                  .map(Declarations.Column::comparesExactly)
                  .orElse(false);
      (exact ? decidable : undecidable).add(item);
    }
    var identity =
        new ElementIdentity.IdentifierCorrespondence(
            MatchingCondition.direct(pairs.toArray(MatchingCondition.Pair[]::new)));
    var existing = context.correspondence(identity);
    existing
        .flatMap(edge -> context.closedRangeFact(edge, Vocabulary.EXISTENCE, EXISTS_KEY))
        .ifPresent(fact -> kept.add(fact.urn()));
    var definition = definition(table, statement);
    var checks = new ArrayList<Check>();
    var definitionChecks = new ArrayList<Check>();
    for (var key : statement.keys()) {
      var details = JSON.objectNode().put("constraint", key.name());
      if (!decidable.isEmpty()) {
        checks.add(declared(key.enforced(), decidable, table, details));
      }
      if (!undecidable.isEmpty()) {
        checks.add(
            new Check(
                key.enforced()
                    ? ValidationMethod.DECLARED_ENFORCED
                    : ValidationMethod.DECLARED_UNENFORCED,
                undecidable,
                CheckResult.UNDECIDABLE,
                null,
                Ref.urn(table.observation()),
                details
                    .deepCopy()
                    .put(
                        "reason",
                        "The pair is not known to compare as the key compares it: text compares"
                            + " under a collation the table metadata does not state, and an"
                            + " unmapped or unread type could be text")));
      }
      definitionChecks.add(declared(key.enforced(), definition, table, details));
    }
    var dependencies = new ArrayList<Dependency>();
    dependencies.add(onObservation(table));
    var revisions = new ArrayList<String>();
    for (var field : statement.from()) {
      dependencies.add(on(field.existence()));
      revisions.add(field.existence().revision());
    }
    for (var field : statement.to()) {
      dependencies.add(on(field.existence()));
      revisions.add(field.existence().revision());
    }
    var read = inputsRead(table, revisions);
    var edge = output.add(new LogRecord.Element(identity));
    var existence =
        acceptClosedRange(
            context,
            output,
            address(output, edge, Vocabulary.EXISTENCE),
            JSON.textNode(EXISTS),
            EXISTS_KEY,
            dependencies,
            checks,
            read);
    // The definition is fixed by the correspondence, so this Generator's earlier Fact with the
    // same text is the same claim and is accepted again rather than proposed anew.
    var own =
        existing.stream()
            .flatMap(found -> context.factsAt(found, BUSINESS_DEFINITION).stream())
            .filter(fact -> NAME.equals(fact.generator()) && fact.value().equals(definition))
            .findFirst();
    var address = address(output, edge, BUSINESS_DEFINITION);
    var fact =
        own.isPresent()
            ? Ref.urn(own.get().urn())
            : output.add(new LogRecord.Fact(address, definition, null));
    output.add(
        new LogRecord.Accept(
            fact,
            List.of(onObservation(table), existence.dependency()),
            definitionChecks,
            read,
            context.provenance()));
  }

  /**
   * The business definition a foreign key states, in words. It names only the tables and columns,
   * which the correspondence fixes, so renaming the key does not change it.
   */
  private static JsonNode definition(Table table, Statement statement) {
    var referencing =
        statement.from().stream().map(ConfirmedField::column).collect(Collectors.joining(", "));
    var referenced =
        statement.to().stream().map(ConfirmedField::column).collect(Collectors.joining(", "));
    return JSON.textNode(
        "Each value of ("
            + referencing
            + ") in "
            + table.reference().map(PhysicalCatalogReference.Table::canonical).orElse("")
            + " denotes the object of the "
            + statement.target().canonical()
            + " row whose ("
            + referenced
            + ") equals it.");
  }
}
