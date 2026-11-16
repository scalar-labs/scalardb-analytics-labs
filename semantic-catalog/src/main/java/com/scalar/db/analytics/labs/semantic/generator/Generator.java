/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator;

import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import java.util.List;

/**
 * One interpretation or check rule that produces candidates, with validation results, from what it
 * reads (see {@code docs/semantic-catalog/generators.md}, "The Generator contract").
 *
 * <p>A Generator runs inside a build task and never writes: it returns its proposals as the records
 * of one commit, which the Semantic Catalog accepts after matching them against existing records.
 * Producing again from unchanged input therefore records nothing.
 */
public interface Generator {
  /** The Generator's name, recorded in provenance and naming its build tasks. */
  String name();

  /**
   * The Generator's version. Any change that can change its output for the same input makes a new
   * version, so Facts produced by an earlier version can be found from provenance.
   */
  String version();

  /** What the Generator reads, from which its build tasks are created. */
  List<Input> inputs();

  /**
   * Produces candidates and retirement proposals for the targets the run's triggers point to.
   *
   * @param context what the run reads
   * @param output where the run puts what it produces
   */
  void run(GeneratorContext context, Output output);

  /** An input kind a Generator declares it reads. */
  sealed interface Input {
    /** A new physical schema observation creates a build task. */
    record PhysicalSchema() implements Input {}

    /** The confirmation of a Fact of a predicate of an element kind creates a build task. */
    record Confirmed(ElementKind kind, String predicate) implements Input {}
  }
}
