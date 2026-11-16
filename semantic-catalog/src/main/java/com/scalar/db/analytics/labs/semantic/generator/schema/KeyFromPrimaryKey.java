/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator.schema;

import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext.Table;
import com.scalar.db.analytics.labs.semantic.generator.Output;
import com.scalar.db.analytics.labs.semantic.model.PhysicalCatalogReference;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import java.util.HashSet;
import java.util.List;

/**
 * Key from primary key: a Dataset's key is the Field set of its table's primary key, {@code
 * declared-enforced} when the source enforces it and {@code declared-unenforced} otherwise (see
 * {@code docs/semantic-catalog/generators.md}, "Schema interpretation"). Under the default policy a
 * key from an unenforced declaration goes to the policy evaluation model, because the absence of
 * duplicates does not show that a set is a business identifier.
 */
final class KeyFromPrimaryKey extends SchemaGenerator {
  static final String NAME = "key-from-primary-key";

  KeyFromPrimaryKey() {
    super(NAME);
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
    var declarations = declarations(table).flatMap(Declarations::constraints);
    var dataset = context.element(reference);
    if (declarations.isEmpty() || dataset.isEmpty()) {
      return;
    }
    var datasetExistence = confirmedExistence(context, dataset.get());
    if (datasetExistence.isEmpty()) {
      output.missing("the confirmed existence of the Dataset for " + reference.canonical());
      return;
    }
    var kept = new HashSet<String>();
    for (var constraint : declarations.get()) {
      if (!(constraint instanceof Declarations.Constraint.PrimaryKey key)) {
        continue;
      }
      var fields = confirmedFields(context, output, reference, key.columns());
      if (fields.isEmpty()) {
        // Missing input: nothing this Generator produced is judged undeclared on it.
        return;
      }
      var value = fieldSet(fields.get());
      var identity = fieldSetKey(fields.get());
      existing(context, dataset.get(), KEY, identity).ifPresent(kept::add);
      acceptAbout(
          context,
          output,
          dataset.get(),
          KEY,
          value,
          identity,
          onFields(table, datasetExistence.get(), fields.get()),
          List.of(
              declared(
                  key.enforced(), value, table, JSON.objectNode().put("constraint", key.name()))),
          revisions(datasetExistence.get(), fields.get()),
          table);
    }
    retireUndeclared(context, output, table, kept, fact -> fact.predicate().equals(KEY));
  }

  static final String KEY = Vocabulary.KEY;
}
