/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator;

import com.scalar.db.analytics.labs.semantic.generator.schema.SchemaInterpretation;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import java.util.List;
import java.util.Optional;

/**
 * The Generators the Semantic Catalog runs, in the order their build tasks are created, so that one
 * change runs the Generators that produce a premise before those that read its confirmation.
 */
public final class Generators {
  private static final List<Generator> ALL = SchemaInterpretation.generators();

  private Generators() {}

  public static List<Generator> all() {
    return ALL;
  }

  public static Optional<Generator> named(String name) {
    return ALL.stream().filter(generator -> generator.name().equals(name)).findFirst();
  }

  /** The Generators that read new physical schema observations. */
  public static List<Generator> readingPhysicalSchema() {
    return ALL.stream()
        .filter(generator -> generator.inputs().contains(new Generator.Input.PhysicalSchema()))
        .toList();
  }

  /** The Generators that read confirmed Facts of a predicate of an element kind. */
  public static List<Generator> readingConfirmed(ElementKind kind, String predicate) {
    var input = new Generator.Input.Confirmed(kind, predicate);
    return ALL.stream().filter(generator -> generator.inputs().contains(input)).toList();
  }
}
