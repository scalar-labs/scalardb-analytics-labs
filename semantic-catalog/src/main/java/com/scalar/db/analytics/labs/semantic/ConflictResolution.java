/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A deciding party's resolution of a conflict, the input of {@linkplain
 * SemanticCatalogStore#resolveConflict resolving a conflict} (see {@code
 * docs/semantic-catalog/public-api.md}, "Resolving a conflict").
 *
 * @param factAddress the conflicting Fact Address
 * @param chosen the Fact presented from now on
 * @param passedOver the other eligible Facts
 * @param decidedBy the deciding party
 * @param authorityCheck the reference to the check of the deciding party's authority; required
 * @param wording the deciding party's own wording
 * @param reviewPresentation the review presentation the resolution was made in, if any
 */
public record ConflictResolution(
    String factAddress,
    String chosen,
    List<String> passedOver,
    String decidedBy,
    String authorityCheck,
    String wording,
    @Nullable Long reviewPresentation) {
  public ConflictResolution {
    Objects.requireNonNull(factAddress, "factAddress");
    Objects.requireNonNull(chosen, "chosen");
    Objects.requireNonNull(authorityCheck, "authorityCheck");
    passedOver = List.copyOf(passedOver);
  }
}
