/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator.schema;

import com.scalar.db.analytics.labs.semantic.generator.Generator;
import java.util.List;

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
}
