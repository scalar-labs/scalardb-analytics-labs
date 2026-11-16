/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator.schema;

import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext.Table;
import com.scalar.db.analytics.labs.semantic.generator.Output;
import com.scalar.db.analytics.labs.semantic.model.Check;
import com.scalar.db.analytics.labs.semantic.model.IdentityKey;
import com.scalar.db.analytics.labs.semantic.model.PhysicalCatalogReference;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Uniqueness declaration ingestion: a Field set does not repeat when the table declares a primary
 * key, a unique constraint, or a unique index on it (see {@code
 * docs/semantic-catalog/generators.md}, "Schema interpretation"). A unique index is always {@code
 * declared-enforced}; the others are so when the source enforces them. A unique constraint or index
 * on nullable columns yields nothing, because rows containing null may repeat, and neither does a
 * partial unique index, which holds only for some rows. Several declarations of one Field set
 * support one candidate.
 */
final class UniquenessDeclarationIngestion extends SchemaGenerator {
  static final String NAME = "uniqueness-declaration-ingestion";
  static final String DOES_NOT_REPEAT = Vocabulary.DOES_NOT_REPEAT;

  UniquenessDeclarationIngestion() {
    super(NAME);
  }

  /**
   * The uniqueness predicate of a Field set, which carries the set: the Fields' URNs, sorted and
   * joined by commas (see {@link Vocabulary#UNIQUENESS_PREFIX}).
   */
  static String predicate(List<ConfirmedField> fields) {
    return Vocabulary.uniqueness(fields.stream().map(ConfirmedField::field).toList());
  }

  @Override
  public List<Input> inputs() {
    return List.of(
        new Input.PhysicalSchema(),
        new Input.Confirmed(ElementKind.DATASET, Vocabulary.EXISTENCE),
        new Input.Confirmed(ElementKind.FIELD, Vocabulary.EXISTENCE));
  }

  @Override
  void interpret(
      GeneratorContext context,
      Output output,
      Table table,
      PhysicalCatalogReference.Table reference) {
    var declared = declarations(table);
    var constraints = declared.flatMap(Declarations::constraints);
    var dataset = context.element(reference);
    if (constraints.isEmpty() || dataset.isEmpty()) {
      return;
    }
    var datasetExistence = confirmedExistence(context, dataset.get());
    if (datasetExistence.isEmpty()) {
      output.missing("the confirmed existence of the Dataset for " + reference.canonical());
      return;
    }
    var value = JSON.textNode(DOES_NOT_REPEAT);
    var checks = new LinkedHashMap<Set<String>, List<Check>>();
    var columnsOf = new LinkedHashMap<Set<String>, List<String>>();
    for (var constraint : constraints.get()) {
      var columns =
          switch (constraint) {
            case Declarations.Constraint.PrimaryKey key -> key.columns();
            case Declarations.Constraint.Unique unique
                when !unique.partial()
                    && unique.columns().stream()
                        .allMatch(
                            column ->
                                declared
                                    .get()
                                    .column(column)
                                    .map(found -> !found.nullable())
                                    .orElse(false)) ->
                unique.columns();
            default -> null;
          };
      if (columns == null || columns.isEmpty()) {
        continue;
      }
      var enforced =
          constraint.enforced()
              || (constraint instanceof Declarations.Constraint.Unique unique && unique.index());
      var set = Set.copyOf(columns);
      columnsOf.putIfAbsent(set, columns);
      checks
          .computeIfAbsent(set, key -> new ArrayList<>())
          .add(
              declared(
                  enforced, value, table, JSON.objectNode().put("constraint", constraint.name())));
    }
    var kept = new HashSet<String>();
    var key = IdentityKey.choice(DOES_NOT_REPEAT);
    for (var entry : checks.entrySet()) {
      var fields =
          confirmedFields(
              context,
              output,
              reference,
              java.util.Objects.requireNonNull(columnsOf.get(entry.getKey())));
      if (fields.isEmpty()) {
        return;
      }
      var predicate = predicate(fields.get());
      existing(context, dataset.get(), predicate, key).ifPresent(kept::add);
      acceptAbout(
          context,
          output,
          dataset.get(),
          predicate,
          value,
          key,
          onFields(table, datasetExistence.get(), fields.get()),
          entry.getValue(),
          revisions(datasetExistence.get(), fields.get()),
          table);
    }
    retireUndeclared(
        context,
        output,
        table,
        kept,
        fact -> fact.predicate().startsWith(Vocabulary.UNIQUENESS_PREFIX));
  }
}
