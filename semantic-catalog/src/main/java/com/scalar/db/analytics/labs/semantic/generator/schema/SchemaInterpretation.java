/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator.schema;

import com.scalar.db.analytics.labs.semantic.generator.Generator;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** The schema-interpretation Generators, in the order their build tasks are created. */
public final class SchemaInterpretation {
  private SchemaInterpretation() {}

  public static List<Generator> generators() {
    return List.of(
        new TableColumnIngestion(),
        new KeyFromPrimaryKey(),
        new UniquenessDeclarationIngestion(),
        new CorrespondenceFromForeignKey(),
        new ValueKindFromSourceType());
  }

  /**
   * The value kind a column's Analytics type fixes, if it fixes one: the Fact Address that value
   * kind from source type fills from the declaration, which meaning inference leaves to it.
   */
  public static Optional<String> declaredValueKind(@Nullable String analyticsKind) {
    return ValueKindFromSourceType.valueKind(analyticsKind);
  }
}
