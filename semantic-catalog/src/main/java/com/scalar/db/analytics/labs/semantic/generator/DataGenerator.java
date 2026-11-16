/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.ConstructionSql;
import java.util.List;
import java.util.Objects;

/**
 * A deterministic Generator that also reads data values through construction-run SQL (see {@code
 * docs/semantic-catalog/generators.md}, "Data observations", and {@code
 * docs/semantic-catalog/storage.md}, "Running build tasks").
 *
 * <p>A run has three steps, so that no transaction is held while a query runs. It plans its queries
 * from the store's latest commit, the queries run outside any transaction, and it records what the
 * reads show inside the transaction that commits its output, reading the state it is recorded on. A
 * collision repeats only the last step, with the same reads.
 */
public interface DataGenerator extends Generator {
  /**
   * The queries the run's triggers call for, one per target.
   *
   * @param context the store at the latest commit; nothing planned here is recorded
   */
  Plan plan(GeneratorContext context);

  /**
   * What a run plans to read.
   *
   * @param queries the queries, one per target
   * @param unread the targets left unread, such as those over a bound on one run, which the run
   *     records as missing input
   */
  record Plan(List<Query> queries, List<String> unread) {
    public Plan {
      queries = List.copyOf(queries);
      unread = List.copyOf(unread);
    }

    public static Plan of(List<Query> queries) {
      return new Plan(queries, List.of());
    }
  }

  /**
   * Produces candidates, additional acceptances, and retirement proposals from the reads, and the
   * data observations that record them.
   *
   * @param context what the run reads, in the recording transaction
   * @param reads the outcome of each planned query, in planned order
   * @param output where the run puts what it produces
   */
  void record(GeneratorContext context, List<Read> reads, Output output);

  /**
   * The statements one check runs on one target, recorded together as one data observation.
   *
   * @param check the kind of check, the Generator's name
   * @param target what the check examines, such as a Field set or a correspondence, as recorded in
   *     the observation
   * @param targetKey the canonical form of the target, which with the check identifies the
   *     observation's Input Resource
   * @param physicalSchemas the latest physical schema observations of the tables the statements
   *     read
   * @param statements the statements, in order
   */
  record Query(
      String check,
      JsonNode target,
      String targetKey,
      List<String> physicalSchemas,
      List<ConstructionSql.Request> statements) {
    public Query {
      Objects.requireNonNull(check, "check");
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(targetKey, "targetKey");
      physicalSchemas = List.copyOf(physicalSchemas);
      statements = List.copyOf(statements);
      if (statements.isEmpty()) {
        throw new IllegalArgumentException("A data check runs at least one statement");
      }
    }

    /** The identity of the data-observation Input Resource: the check and the target. */
    public String dataCheck() {
      return check + " " + targetKey;
    }
  }

  /**
   * What running a query's statements gave.
   *
   * @param outcomes the outcome of each statement, in order
   */
  record Read(Query query, List<ConstructionSql.Outcome> outcomes) {
    public Read {
      Objects.requireNonNull(query, "query");
      outcomes = List.copyOf(outcomes);
      if (outcomes.size() != query.statements().size()) {
        throw new IllegalArgumentException("A read has one outcome per statement");
      }
    }
  }
}
