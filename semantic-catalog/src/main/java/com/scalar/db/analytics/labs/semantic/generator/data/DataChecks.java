/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator.data;

import com.scalar.db.analytics.labs.semantic.generator.Generator;
import java.util.List;

/** The data-check Generators, in the order their build tasks are created. */
public final class DataChecks {
  private DataChecks() {}

  public static List<Generator> generators() {
    return List.of(
        new UniquenessCheck(),
        new CandidateKeyDiscovery(),
        new InclusionDiscovery(),
        new SetRelationshipCheck());
  }
}
