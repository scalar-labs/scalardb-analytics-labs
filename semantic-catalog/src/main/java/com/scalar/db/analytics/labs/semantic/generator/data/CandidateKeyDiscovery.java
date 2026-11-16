/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator.data;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scalar.db.analytics.labs.semantic.ConstructionSql;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext.Trigger;
import com.scalar.db.analytics.labs.semantic.generator.Output;
import com.scalar.db.analytics.labs.semantic.generator.schema.Declarations;
import com.scalar.db.analytics.labs.semantic.model.Check;
import com.scalar.db.analytics.labs.semantic.model.IdentityKey;
import com.scalar.db.analytics.labs.semantic.model.PhysicalCatalogReference;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.CheckResult;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.DataFinding;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ValidationMethod;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Candidate key discovery: the minimal Field sets that do not repeat among the rows read of a
 * Dataset whose table declares no primary key (see {@code docs/semantic-catalog/generators.md},
 * "Data checks"). It explores single columns and pairs of columns, the initial column-set sizes,
 * once every column of the table has a Field whose existence is confirmed.
 *
 * <p>Two statements on a sample of the table count, for each single column and for each pair, the
 * rows without null and their distinct values; a pair statement over a limit leaves the single
 * columns decided. A set is unique when no row has null in it and every value is distinct; a pair
 * counts only when neither of its columns is unique alone. Each minimal set gets a uniqueness
 * candidate with a {@code data-check} that found no counterexample, left to the policy evaluation
 * model, and the smallest ones each a key candidate with {@code inference}, because the absence of
 * duplicates does not show that a set is a business identifier. A set whose value repeats refutes
 * the uniqueness Facts already proposed for it. A table whose metadata does not report its
 * constraints is not explored, because nothing is concluded from their absence.
 */
final class CandidateKeyDiscovery extends DataCheck {
  static final String NAME = "candidate-key-discovery";
  static final String DOES_NOT_REPEAT = Vocabulary.DOES_NOT_REPEAT;

  CandidateKeyDiscovery() {
    super(NAME);
  }

  @Override
  public List<Input> inputs() {
    return List.of(new Input.Confirmed(ElementKind.FIELD, Vocabulary.EXISTENCE));
  }

  /** A Dataset without a primary key and its Fields and their columns, in column order. */
  private record Target(
      String dataset,
      GeneratorContext.Table table,
      PhysicalCatalogReference.Table reference,
      List<String> fields,
      List<String> columns) {}

  @Override
  public Plan plan(GeneratorContext context) {
    var tables = new LinkedHashSet<String>();
    for (var trigger : context.triggers()) {
      if (trigger instanceof Trigger.Confirmed confirmed) {
        context
            .column(confirmed.element())
            .ifPresent(column -> tables.add(column.table().inputResource()));
      }
    }
    var queries = new ArrayList<Query>();
    for (var resource : tables) {
      target(context, resource).map(CandidateKeyDiscovery::query).ifPresent(queries::add);
    }
    return Plan.of(queries);
  }

  private static Optional<Target> target(GeneratorContext context, String resource) {
    var table = context.table(resource);
    if (table.isEmpty()
        || table.get().reference().isEmpty()
        || !(table.get().observed() instanceof GeneratorContext.Observed.Read read)) {
      return Optional.empty();
    }
    // Nothing is concluded from constraints the table metadata does not report, so discovery runs
    // only when they are reported and include no primary key.
    var declarations = Declarations.of(read.tableMetadata());
    var primaryKey =
        declarations
            .constraints()
            .map(
                constraints ->
                    constraints.stream()
                        .anyMatch(Declarations.Constraint.PrimaryKey.class::isInstance))
            .orElse(true);
    if (primaryKey || declarations.columns().isEmpty()) {
      return Optional.empty();
    }
    var reference = table.get().reference().get();
    var dataset = context.element(reference);
    if (dataset.isEmpty()) {
      return Optional.empty();
    }
    var fields = new ArrayList<String>();
    var names = new ArrayList<String>();
    for (var column : declarations.columns()) {
      var field = context.element(new PhysicalCatalogReference.Column(reference, column.name()));
      if (field.isEmpty()) {
        return Optional.empty();
      }
      fields.add(field.get());
      names.add(column.name());
    }
    if (FieldSet.of(context, fields).isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new Target(dataset.get(), table.get(), reference, fields, names));
  }

  /**
   * The statements: the single columns, and the pairs when there are any, separately, so a pair
   * statement over a limit leaves the single columns decided.
   */
  private static Query query(Target target) {
    var columns = target.columns().stream().map(name -> column("t", name)).toList();
    var input =
        List.of(new ConstructionSql.Input(target.reference(), ConstructionSql.Reading.SAMPLED));
    var singles = new ArrayList<String>();
    singles.add(count("count(*)") + " AS row_count");
    for (var i = 0; i < columns.size(); i++) {
      singles.add(count("count(" + columns.get(i) + ")") + " AS n" + i);
      singles.add(count("count(DISTINCT " + columns.get(i) + ")") + " AS d" + i);
    }
    var statements = new ArrayList<ConstructionSql.Request>();
    statements.add(request(select(singles, target), input));
    var select = new ArrayList<String>();
    select.add(count("count(*)") + " AS row_count");
    for (var i = 0; i < columns.size(); i++) {
      for (var j = i + 1; j < columns.size(); j++) {
        var both = columns.get(i) + " IS NOT NULL AND " + columns.get(j) + " IS NOT NULL";
        select.add(count("sum(CASE WHEN " + both + " THEN 1 ELSE 0 END)") + " AS n" + i + "_" + j);
        select.add(
            count(
                    "count(DISTINCT CASE WHEN "
                        + both
                        + " THEN row("
                        + columns.get(i)
                        + ", "
                        + columns.get(j)
                        + ") END)")
                + " AS d"
                + i
                + "_"
                + j);
      }
    }
    if (columns.size() > 1) {
      statements.add(request(select(select, target), input));
    }
    var node = JSON.objectNode().put("dataset", target.dataset());
    var fields = node.putArray("fields");
    target.fields().forEach(fields::add);
    return new Query(
        NAME, node, target.dataset(), List.of(target.table().observation()), statements);
  }

  private static String select(List<String> aggregates, Target target) {
    return "SELECT "
        + String.join(", ", aggregates)
        + " FROM "
        + table(target.reference())
        + " AS t";
  }

  @Override
  public void record(GeneratorContext context, List<Read> reads, Output output) {
    for (var read : reads) {
      var fields = new ArrayList<String>();
      read.query().target().path("fields").forEach(field -> fields.add(field.asText()));
      if (FieldSet.of(context, fields).isEmpty()) {
        output.missing("the confirmed existence of the Fields " + fields);
        continue;
      }
      var observed = observe(context, output, read);
      if (!(read.outcomes().getFirst() instanceof ConstructionSql.Outcome.Read result)) {
        continue;
      }
      var rows = number(result, "row_count");
      if (rows == 0) {
        continue;
      }
      var details = JSON.objectNode().put("rowsRead", rows);
      var singles = new ArrayList<List<String>>();
      for (var i = 0; i < fields.size(); i++) {
        var set = List.of(fields.get(i));
        switch (finding(result, rows, String.valueOf(i))) {
          case UNIQUE -> singles.add(set);
          case REPEATS -> refute(context, output, observed, set, details);
          case UNDECIDED -> {}
        }
      }
      var pairs = new ArrayList<List<String>>();
      if (read.outcomes().size() > 1
          && read.outcomes().get(1) instanceof ConstructionSql.Outcome.Read pairResult) {
        for (var i = 0; i < fields.size(); i++) {
          for (var j = i + 1; j < fields.size(); j++) {
            var set = List.of(fields.get(i), fields.get(j));
            switch (finding(pairResult, rows, i + "_" + j)) {
              case UNIQUE -> {
                var neitherAlone =
                    !singles.contains(List.of(fields.get(i)))
                        && !singles.contains(List.of(fields.get(j)));
                if (neitherAlone) {
                  pairs.add(set);
                }
              }
              case REPEATS -> refute(context, output, observed, set, details);
              case UNDECIDED -> {}
            }
          }
        }
      }
      var minimal = new ArrayList<>(singles);
      minimal.addAll(pairs);
      for (var set : minimal) {
        FieldSet.of(context, set)
            .ifPresent(found -> concludeUniqueness(context, output, observed, found, details));
      }
      for (var set : singles.isEmpty() ? pairs : singles) {
        FieldSet.of(context, set)
            .ifPresent(found -> concludeKey(context, output, observed, found, details));
      }
    }
  }

  /** What the counts of one set show. */
  private enum Finding {
    /** Every row read has the set without null and no value repeats. */
    UNIQUE,
    /** A value of the set without null repeats: a counterexample to its uniqueness. */
    REPEATS,
    /** Some row has null in the set, which cannot be compared, and no value repeats. */
    UNDECIDED
  }

  private static Finding finding(ConstructionSql.Outcome.Read result, long rows, String suffix) {
    var nonNull = number(result, "n" + suffix);
    var distinct = number(result, "d" + suffix);
    if (distinct < nonNull) {
      return Finding.REPEATS;
    }
    return nonNull == rows ? Finding.UNIQUE : Finding.UNDECIDED;
  }

  /**
   * Refutes the uniqueness Facts of a set whose value repeats: a candidate gets a refuting check,
   * and a confirmed one a retirement proposal because its confirmation was wrong. A set without
   * such Facts gets nothing, because this Generator proposes only what may be a key.
   */
  private void refute(
      GeneratorContext context,
      Output output,
      Observed observed,
      List<String> fields,
      ObjectNode details) {
    var set = FieldSet.of(context, fields);
    if (set.isEmpty() || context.factsAt(set.get().dataset(), set.get().uniqueness()).isEmpty()) {
      return;
    }
    var refuted = JSON.textNode(DOES_NOT_REPEAT);
    conclude(
        context,
        output,
        observed,
        new Conclusion(
            set.get().dataset(),
            set.get().uniqueness(),
            Optional.empty(),
            fact -> fact.value().equals(refuted) ? List.of(fact.value()) : List.of(),
            Optional.empty(),
            set.get().premises(),
            set.get().revisions()));
  }

  private void concludeUniqueness(
      GeneratorContext context,
      Output output,
      Observed observed,
      FieldSet set,
      ObjectNode details) {
    var value = JSON.textNode(DOES_NOT_REPEAT);
    conclude(
        context,
        output,
        observed,
        new Conclusion(
            set.dataset(),
            set.uniqueness(),
            Optional.of(
                new Proposal(
                    value,
                    IdentityKey.choice(DOES_NOT_REPEAT),
                    List.of(
                        check(
                            value,
                            CheckResult.SUPPORTED,
                            DataFinding.NO_COUNTEREXAMPLE_FOUND,
                            observed,
                            details.deepCopy())))),
            fact -> List.of(),
            Optional.empty(),
            set.premises(),
            set.revisions()));
  }

  private void concludeKey(
      GeneratorContext context,
      Output output,
      Observed observed,
      FieldSet set,
      ObjectNode details) {
    var value = JSON.objectNode();
    var fields = value.putArray("fields");
    set.fields().forEach(fields::add);
    var key =
        IdentityKey.of(
            new IdentityKey.Part.References(set.fields().stream().map(Ref::urn).toList()));
    var inference =
        new Check(
            ValidationMethod.INFERENCE,
            value,
            CheckResult.SUPPORTED,
            null,
            null,
            stored(
                details
                    .deepCopy()
                    .put("check", name())
                    .put(
                        "basis",
                        "No value of the set repeats and none is null among the rows read; whether it"
                            + " identifies the Dataset's objects is not shown by data")));
    conclude(
        context,
        output,
        observed,
        new Conclusion(
            set.dataset(),
            Vocabulary.KEY,
            Optional.of(new Proposal(value, key, List.of(inference))),
            fact -> List.of(),
            Optional.empty(),
            set.premises(),
            set.revisions()));
  }
}
