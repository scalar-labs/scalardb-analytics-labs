/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator;

import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import java.util.List;

/**
 * One interpretation or check rule that produces candidates, with validation results, from what it
 * reads (see {@code docs/semantic-catalog/generators.md}, "The Generator contract").
 *
 * <p>A Generator runs inside a build task and never writes: it returns its proposals as the records
 * of one commit, which the Semantic Catalog accepts after matching them against existing records.
 * Producing again from unchanged input therefore records nothing.
 *
 * <p>How a run reads decides how it is run: a {@link StoreGenerator} reads only the store, and a
 * {@link DataGenerator} also reads data values through construction-run SQL.
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

  /** An input kind a Generator declares it reads. */
  sealed interface Input {
    /** A new physical schema observation creates a build task. */
    record PhysicalSchema() implements Input {}

    /** A new document observation creates a build task. */
    record Document() implements Input {}

    /**
     * The confirmation of a Fact of a predicate of an element kind creates a build task. The
     * predicate {@link Vocabulary#UNIQUENESS_PREFIX} stands for the uniqueness of every Field set.
     */
    record Confirmed(ElementKind kind, String predicate) implements Input {}

    /**
     * An acceptance of a Fact of a predicate of an element kind that changes it creates a build
     * task, so a Generator can add its support to a candidate another Generator proposed.
     */
    record Accepted(ElementKind kind, String predicate) implements Input {}

    /**
     * An observation of usage feedback with related Facts, such as the feedback a correction answer
     * registers, creates a build task.
     */
    record FeedbackWithRelatedFacts() implements Input {}

    /** Whether an input declared for a predicate covers a Fact's predicate. */
    static boolean covers(String declared, String predicate) {
      return declared.equals(predicate)
          || (declared.equals(Vocabulary.UNIQUENESS_PREFIX)
              && predicate.startsWith(Vocabulary.UNIQUENESS_PREFIX));
    }
  }
}
