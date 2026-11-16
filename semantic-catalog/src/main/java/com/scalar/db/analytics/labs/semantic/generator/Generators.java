/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator;

import com.scalar.db.analytics.labs.semantic.generator.data.DataChecks;
import com.scalar.db.analytics.labs.semantic.generator.llm.MeaningInferences;
import com.scalar.db.analytics.labs.semantic.generator.schema.SchemaInterpretation;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Generators the Semantic Catalog runs, in the order their build tasks are created, so that one
 * change runs the Generators that produce a premise before those that read its confirmation.
 */
public final class Generators {
  private static final List<Generator> ALL = all(SchemaInterpretation.generators());

  private Generators() {}

  private static List<Generator> all(List<Generator> schemaInterpretation) {
    var all = new ArrayList<>(schemaInterpretation);
    all.addAll(DataChecks.generators());
    all.addAll(MeaningInferences.generators());
    return List.copyOf(all);
  }

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

  /** The Generators that read new document observations. */
  public static List<Generator> readingDocuments() {
    return ALL.stream()
        .filter(generator -> generator.inputs().contains(new Generator.Input.Document()))
        .toList();
  }

  /** The Generators that read usage feedback with related Facts. */
  public static List<Generator> readingFeedbackWithRelatedFacts() {
    return ALL.stream()
        .filter(
            generator ->
                generator.inputs().contains(new Generator.Input.FeedbackWithRelatedFacts()))
        .toList();
  }

  /** The Generators that read confirmed Facts of a predicate of an element kind. */
  public static List<Generator> readingConfirmed(ElementKind kind, String predicate) {
    return ALL.stream()
        .filter(
            generator ->
                generator.inputs().stream()
                    .anyMatch(
                        input ->
                            input instanceof Generator.Input.Confirmed confirmed
                                && confirmed.kind() == kind
                                && Generator.Input.covers(confirmed.predicate(), predicate)))
        .toList();
  }

  /** The Generators that read acceptances of Facts of a predicate of an element kind. */
  public static List<Generator> readingAccepted(ElementKind kind, String predicate) {
    return ALL.stream()
        .filter(
            generator ->
                generator.inputs().stream()
                    .anyMatch(
                        input ->
                            input instanceof Generator.Input.Accepted accepted
                                && accepted.kind() == kind
                                && Generator.Input.covers(accepted.predicate(), predicate)))
        .toList();
  }
}
