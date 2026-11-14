/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import java.util.List;

/**
 * The Facts one commit moved by policy evaluation and cascades, beyond what its request wrote, and
 * the candidates it left for the policy evaluation model. Callers create build tasks from them.
 *
 * @param confirmed the Facts confirmed
 * @param rejected the Facts rejected
 * @param retired the Facts retired
 * @param awaitingModel the candidates and retirement proposals that inline evaluation left
 *     undecided under {@code auto}, which wait for the policy evaluation model
 */
public record Transitions(
    List<String> confirmed,
    List<String> rejected,
    List<String> retired,
    List<String> awaitingModel) {
  public static final Transitions NONE =
      new Transitions(List.of(), List.of(), List.of(), List.of());

  public Transitions {
    confirmed = List.copyOf(confirmed);
    rejected = List.copyOf(rejected);
    retired = List.copyOf(retired);
    awaitingModel = List.copyOf(awaitingModel);
  }
}
