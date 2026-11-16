/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator.schema;

import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext.Table;
import com.scalar.db.analytics.labs.semantic.generator.Output;
import com.scalar.db.analytics.labs.semantic.model.IdentityKey;
import com.scalar.db.analytics.labs.semantic.model.PhysicalCatalogReference;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Value kind from source type: a Field whose column has a date, time, or timestamp type is a time
 * or date, and one with a boolean type is a boolean, {@code declared-enforced} because the source
 * enforces column types (see {@code docs/semantic-catalog/generators.md}, "Schema interpretation").
 * It reads the column's Analytics type, the connector's source-independent form of the source type.
 * Numeric and string types cannot tell identifiers from amounts, so their value kinds come from
 * Field meaning inference.
 */
final class ValueKindFromSourceType extends SchemaGenerator {
  static final String NAME = "value-kind-from-source-type";
  static final String VALUE_KIND = Vocabulary.VALUE_KIND;
  static final String TIME_OR_DATE = "time-or-date";
  static final String BOOLEAN = "boolean";

  private static final Set<String> TIME_OR_DATE_TYPES =
      Set.of("date", "time", "timestamp", "timestamp-tz", "time-with-time-zone", "year");

  ValueKindFromSourceType() {
    super(NAME);
  }

  /** The value kind an Analytics type fixes, if it fixes one. */
  static Optional<String> valueKind(@Nullable String analyticsKind) {
    if (analyticsKind == null) {
      return Optional.empty();
    }
    if (TIME_OR_DATE_TYPES.contains(analyticsKind)) {
      return Optional.of(TIME_OR_DATE);
    }
    return "boolean".equals(analyticsKind) ? Optional.of(BOOLEAN) : Optional.empty();
  }

  @Override
  public List<Input> inputs() {
    return List.of(
        new Input.PhysicalSchema(), new Input.Confirmed(ElementKind.FIELD, Vocabulary.EXISTENCE));
  }

  @Override
  void interpret(
      GeneratorContext context,
      Output output,
      Table table,
      PhysicalCatalogReference.Table reference) {
    var declared = declarations(table);
    if (declared.isEmpty()) {
      return;
    }
    var kept = new HashSet<String>();
    var complete = true;
    for (var column : declared.get().columns()) {
      if (column.type() instanceof Declarations.Type.Unread) {
        // A type that could not be read is missing, not changed: nothing is retired on it.
        output.missing(
            "the type of column "
                + new PhysicalCatalogReference.Column(reference, column.name()).canonical());
        complete = false;
        continue;
      }
      var kind = valueKind(column.analyticsKind());
      if (kind.isEmpty()) {
        continue;
      }
      var field = confirmedField(context, reference, column.name());
      if (field.isEmpty()) {
        output.missing(
            "the confirmed existence of the Field for "
                + new PhysicalCatalogReference.Column(reference, column.name()).canonical());
        complete = false;
        continue;
      }
      var value = JSON.textNode(kind.get());
      var key = IdentityKey.choice(kind.get());
      existing(context, field.get().field(), VALUE_KIND, key).ifPresent(kept::add);
      acceptAbout(
          context,
          output,
          field.get().field(),
          VALUE_KIND,
          value,
          key,
          List.of(onObservation(table), on(field.get().existence())),
          List.of(
              declared(
                  true,
                  value,
                  table,
                  JSON.objectNode().put("analyticsType", column.analyticsKind()))),
          List.of(field.get().existence().revision()),
          table);
    }
    if (complete) {
      retireUndeclared(context, output, table, kept, fact -> fact.predicate().equals(VALUE_KIND));
    }
  }
}
