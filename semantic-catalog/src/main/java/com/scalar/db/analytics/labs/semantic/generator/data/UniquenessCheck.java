/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator.data;

import com.scalar.db.analytics.labs.semantic.ConstructionSql;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext.Fact;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext.Trigger;
import com.scalar.db.analytics.labs.semantic.generator.Output;
import com.scalar.db.analytics.labs.semantic.generator.schema.Declarations;
import com.scalar.db.analytics.labs.semantic.model.IdentityKey;
import com.scalar.db.analytics.labs.semantic.model.PhysicalCatalogReference;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.CheckResult;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.DataFinding;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The uniqueness check: whether a Field set repeats among the rows read (see {@code
 * docs/semantic-catalog/generators.md}, "Data checks"). It examines the Field sets that a table
 * declares unique once their Fields exist, the Field set of each confirmed key, and the Field set
 * at each end of each confirmed identifier correspondence, whose repetition gives how many rows
 * match across it. A new physical schema observation of a table examines again the sets it examined
 * there.
 *
 * <p>One grouping query on a sample of the table counts the rows, the rows whose set contains null,
 * and the values that repeat. A repeating value is a counterexample, as sure in a sample as in the
 * whole table, so {@code repeats} is adopted inline and refutes {@code does-not-repeat}. Finding no
 * repetition supports {@code does-not-repeat} only as an absence: it is adopted inline when a
 * declaration also supports it and is otherwise left to the policy evaluation model. More than one
 * row with null leaves the set undecidable, because those rows cannot be compared, and so does
 * reading no rows.
 */
final class UniquenessCheck extends DataCheck {
  static final String NAME = "uniqueness-check";
  static final String DOES_NOT_REPEAT = Vocabulary.DOES_NOT_REPEAT;
  static final String REPEATS = Vocabulary.REPEATS;

  UniquenessCheck() {
    super(NAME);
  }

  @Override
  public List<Input> inputs() {
    return List.of(
        new Input.PhysicalSchema(),
        new Input.Confirmed(ElementKind.FIELD, Vocabulary.EXISTENCE),
        new Input.Confirmed(ElementKind.DATASET, Vocabulary.KEY),
        new Input.Confirmed(ElementKind.IDENTIFIER_CORRESPONDENCE, Vocabulary.EXISTENCE));
  }

  @Override
  public Plan plan(GeneratorContext context) {
    var sets = new LinkedHashSet<List<String>>();
    for (var trigger : context.triggers()) {
      switch (trigger) {
        case Trigger.Observed observed ->
            sets.addAll(examinedOn(context, observed.inputResource()));
        case Trigger.Confirmed confirmed -> sets.addAll(confirmedSets(context, confirmed));
        case Trigger.Accepted ignored -> {}
      }
    }
    var distinct = new LinkedHashSet<List<String>>();
    sets.forEach(fields -> distinct.add(List.copyOf(new TreeSet<>(fields))));
    var queries = new ArrayList<Query>();
    for (var fields : distinct) {
      FieldSet.of(context, fields).ifPresent(set -> queries.add(query(set)));
    }
    return Plan.of(queries);
  }

  private List<List<String>> confirmedSets(GeneratorContext context, Trigger.Confirmed confirmed) {
    return switch (confirmed.kind()) {
      case FIELD ->
          context.column(confirmed.element()).stream()
              .flatMap(column -> declaredSets(context, column.table()).stream())
              .filter(set -> set.contains(confirmed.element()))
              .toList();
      case DATASET ->
          context.fact(confirmed.fact()).map(UniquenessCheck::keyFields).stream().toList();
      case IDENTIFIER_CORRESPONDENCE ->
          context
              .matchingCondition(confirmed.element())
              .map(condition -> List.of(urns(condition.fromFields()), urns(condition.toFields())))
              .orElse(List.of());
      default -> List.of();
    };
  }

  private static List<String> urns(List<Ref> refs) {
    return refs.stream().map(Ref::urnValue).toList();
  }

  private static List<String> keyFields(Fact key) {
    var fields = new ArrayList<String>();
    key.value().path("fields").forEach(field -> fields.add(field.asText()));
    return fields;
  }

  /**
   * The sets examined again on a table's new physical schema observation: those it declares unique
   * and those this check examined before.
   */
  private List<List<String>> examinedOn(GeneratorContext context, String inputResource) {
    var sets = new ArrayList<List<String>>();
    var table = context.table(inputResource);
    if (table.isEmpty() || table.get().reference().isEmpty()) {
      return sets;
    }
    sets.addAll(declaredSets(context, table.get()));
    var dataset = context.element(table.get().reference().get());
    if (dataset.isEmpty()) {
      return sets;
    }
    for (var predicate : context.predicates(dataset.get())) {
      if (!predicate.startsWith(Vocabulary.UNIQUENESS_PREFIX)) {
        continue;
      }
      var examined =
          context.factsAt(dataset.get(), predicate).stream()
              .anyMatch(
                  fact ->
                      fact.checks().stream()
                          .anyMatch(
                              check -> NAME.equals(check.details().path("check").asText(null))));
      if (examined) {
        // A uniqueness predicate names its Field set.
        sets.add(Vocabulary.uniquenessFields(predicate));
      }
    }
    return sets;
  }

  /** The Field sets a table declares unique, by primary key or a unique constraint or index. */
  private static List<List<String>> declaredSets(
      GeneratorContext context, GeneratorContext.Table table) {
    if (!(table.observed() instanceof GeneratorContext.Observed.Read read)
        || table.reference().isEmpty()) {
      return List.of();
    }
    var reference = table.reference().get();
    var sets = new ArrayList<List<String>>();
    for (var constraint : Declarations.of(read.tableMetadata()).constraints().orElse(List.of())) {
      var columns =
          switch (constraint) {
            case Declarations.Constraint.PrimaryKey key -> key.columns();
            case Declarations.Constraint.Unique unique when !unique.partial() -> unique.columns();
            default -> List.<String>of();
          };
      if (columns.isEmpty()) {
        continue;
      }
      var fields = new ArrayList<String>();
      for (var column : columns) {
        var field = context.element(new PhysicalCatalogReference.Column(reference, column));
        if (field.isEmpty()) {
          break;
        }
        fields.add(field.get());
      }
      if (fields.size() == columns.size()) {
        sets.add(fields);
      }
    }
    return sets;
  }

  private Query query(FieldSet set) {
    var columns = set.columnNames().stream().map(column -> column("t", column)).toList();
    var grouped =
        "SELECT count(*) AS n, max(CASE WHEN "
            + anyNull(columns)
            + " THEN 1 ELSE 0 END) AS has_null FROM "
            + table(set.table())
            + " AS t GROUP BY "
            + String.join(", ", columns);
    var sql =
        "SELECT "
            + count("sum(g.n)")
            + " AS row_count, "
            + count("sum(CASE WHEN g.has_null = 1 THEN g.n ELSE 0 END)")
            + " AS rows_with_null, "
            + count("sum(CASE WHEN g.has_null = 0 AND g.n > 1 THEN 1 ELSE 0 END)")
            + " AS repeating_values FROM ("
            + grouped
            + ") AS g";
    return new Query(
        NAME,
        set.target(),
        set.uniqueness(),
        List.of(set.physicalSchema()),
        List.of(
            request(
                sql,
                List.of(new ConstructionSql.Input(set.table(), ConstructionSql.Reading.SAMPLED)))));
  }

  @Override
  public void record(GeneratorContext context, List<Read> reads, Output output) {
    for (var read : reads) {
      var fields = new TreeSet<String>();
      read.query().target().path("fields").forEach(field -> fields.add(field.asText()));
      var set = FieldSet.of(context, fields);
      if (set.isEmpty()) {
        output.missing("the confirmed existence of the Fields " + fields);
        continue;
      }
      var observed = observe(context, output, read);
      conclude(context, output, observed, conclusion(set.get(), read, observed));
    }
  }

  private Conclusion conclusion(FieldSet set, Read read, Observed observed) {
    var completed = completed(read);
    Optional<Proposal> proposal = Optional.empty();
    Set<String> refuted = Set.of();
    String undecidable = null;
    if (completed.isEmpty()) {
      undecidable = "The query exceeded a limit of the application";
    } else {
      var result = completed.get().getFirst();
      var rows = number(result, "row_count");
      var withNull = number(result, "rows_with_null");
      var repeating = number(result, "repeating_values");
      var details =
          JSON.objectNode()
              .put("rowsRead", rows)
              .put("rowsWithNull", withNull)
              .put("repeatingValues", repeating);
      if (repeating > 0) {
        proposal =
            Optional.of(
                new Proposal(
                    JSON.textNode(REPEATS),
                    IdentityKey.choice(REPEATS),
                    List.of(
                        check(
                            JSON.textNode(REPEATS),
                            CheckResult.SUPPORTED,
                            DataFinding.COUNTEREXAMPLE_FOUND,
                            observed,
                            details))));
        refuted = Set.of(DOES_NOT_REPEAT);
      } else if (rows == 0) {
        undecidable = "No rows were read";
      } else if (withNull > 1) {
        undecidable = "Rows whose Field set contains null cannot be compared";
      } else {
        proposal =
            Optional.of(
                new Proposal(
                    JSON.textNode(DOES_NOT_REPEAT),
                    IdentityKey.choice(DOES_NOT_REPEAT),
                    List.of(
                        check(
                            JSON.textNode(DOES_NOT_REPEAT),
                            CheckResult.SUPPORTED,
                            DataFinding.NO_COUNTEREXAMPLE_FOUND,
                            observed,
                            details))));
      }
    }
    var refutes = refuted;
    var reason = undecidable;
    return new Conclusion(
        set.dataset(),
        set.uniqueness(),
        proposal,
        fact -> refutes.contains(fact.value().asText()) ? List.of(fact.value()) : List.of(),
        reason == null
            ? Optional.empty()
            : Optional.of(
                fact ->
                    check(
                        fact.value(),
                        CheckResult.UNDECIDABLE,
                        null,
                        observed,
                        JSON.objectNode().put("reason", reason))),
        set.premises(),
        set.revisions());
  }
}
