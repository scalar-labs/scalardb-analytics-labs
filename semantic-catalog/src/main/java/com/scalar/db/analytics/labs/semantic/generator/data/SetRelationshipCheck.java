/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.ConstructionSql;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext.Fact;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext.Trigger;
import com.scalar.db.analytics.labs.semantic.generator.Output;
import com.scalar.db.analytics.labs.semantic.generator.Proposals;
import com.scalar.db.analytics.labs.semantic.generator.SetRelationships;
import com.scalar.db.analytics.labs.semantic.generator.SetRelationships.End;
import com.scalar.db.analytics.labs.semantic.model.Check;
import com.scalar.db.analytics.labs.semantic.model.Dependency;
import com.scalar.db.analytics.labs.semantic.model.IdentityKey;
import com.scalar.db.analytics.labs.semantic.model.MatchingCondition;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.CheckResult;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.DataFinding;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Normalization;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * The set relationship check: which values of each end of a confirmed identifier correspondence are
 * matched on the other end (see {@code docs/semantic-catalog/generators.md}, "Data checks"). It
 * examines a direct correspondence, whose matching condition pairs the Fields of its two ends, and
 * examines it again on a new physical schema observation of either end's table.
 *
 * <p>Two anti-join counts, one per direction, apply the matching condition after each side's
 * normalization. Each samples only the end whose values it checks and looks every sampled value up
 * in the whole of the other end, because a value missing from a sample of the other end would look
 * like a counterexample that is not one. An end read from the same table as the other is read whole
 * on both sides for the same reason. Rows whose end contains null take no part.
 *
 * <p>The value follows from whether every value of each end is matched and whether any is. An
 * unmatched value or a match found is a counterexample, adopted inline; that every value is matched
 * or that none is rests on an absence, adopted inline only when a declaration supports the same
 * item, as a foreign key on non-null columns does for the referencing end.
 */
final class SetRelationshipCheck extends DataCheck {
  static final String NAME = "set-relationship-check";

  SetRelationshipCheck() {
    super(NAME);
  }

  @Override
  public List<Input> inputs() {
    return List.of(
        new Input.PhysicalSchema(),
        new Input.Confirmed(ElementKind.IDENTIFIER_CORRESPONDENCE, Vocabulary.EXISTENCE));
  }

  /** A direct correspondence whose existence is confirmed, with the Field sets of its ends. */
  private record Target(
      String edge, Fact existence, MatchingCondition.Hop hop, FieldSet from, FieldSet to) {}

  @Override
  public Plan plan(GeneratorContext context) {
    var edges = new LinkedHashSet<String>();
    for (var trigger : context.triggers()) {
      switch (trigger) {
        case Trigger.Confirmed confirmed -> edges.add(confirmed.element());
        case Trigger.Observed observed -> edges.addAll(examinedOn(context, observed));
        case Trigger.Accepted ignored -> {}
      }
    }
    var queries = new ArrayList<Query>();
    for (var edge : edges) {
      target(context, edge).flatMap(SetRelationshipCheck::query).ifPresent(queries::add);
    }
    return Plan.of(queries);
  }

  /**
   * The correspondences this check examined that have an end in a newly observed table: those whose
   * existence rests on the existence of one of the table's Fields, whichever Generator proposed
   * them.
   */
  private static List<String> examinedOn(GeneratorContext context, Trigger.Observed observed) {
    var edges = new LinkedHashSet<String>();
    var dataset =
        context
            .table(observed.inputResource())
            .flatMap(GeneratorContext.Table::reference)
            .flatMap(context::element);
    if (dataset.isEmpty()) {
      return List.of();
    }
    for (var field : context.fields(dataset.get())) {
      var existence =
          context.closedRangeFact(
              field.element(), Vocabulary.EXISTENCE, IdentityKey.choice("exists"));
      if (existence.isEmpty()) {
        continue;
      }
      for (var dependent : context.dependents(existence.get().urn())) {
        var correspondence =
            dependent.predicate().equals(Vocabulary.EXISTENCE)
                && context
                    .elementKind(dependent.element())
                    .filter(kind -> kind == ElementKind.IDENTIFIER_CORRESPONDENCE)
                    .isPresent();
        if (correspondence && examined(context, dependent.element())) {
          edges.add(dependent.element());
        }
      }
    }
    return List.copyOf(edges);
  }

  private static boolean examined(GeneratorContext context, String edge) {
    return context.factsAt(edge, Vocabulary.SET_RELATIONSHIP).stream()
        .anyMatch(
            found ->
                found.checks().stream()
                    .anyMatch(check -> NAME.equals(check.details().path("check").asText(null))));
  }

  private static Optional<Target> target(GeneratorContext context, String edge) {
    var condition = context.matchingCondition(edge);
    var existence =
        context
            .closedRangeFact(edge, Vocabulary.EXISTENCE, IdentityKey.choice("exists"))
            .filter(fact -> fact.state() == FactState.CONFIRMED);
    if (condition.isEmpty() || existence.isEmpty() || condition.get().hops().size() != 1) {
      return Optional.empty();
    }
    var hop = condition.get().hops().getFirst();
    var from =
        FieldSet.of(
            context, hop.pairs().stream().map(pair -> pair.from().field().urnValue()).toList());
    var to =
        FieldSet.of(
            context, hop.pairs().stream().map(pair -> pair.to().field().urnValue()).toList());
    if (from.isEmpty() || to.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new Target(edge, existence.get(), hop, from.get(), to.get()));
  }

  private static Optional<Query> query(Target target) {
    var fromSql = direction(target, End.FROM);
    var toSql = direction(target, End.TO);
    if (fromSql.isEmpty() || toSql.isEmpty()) {
      return Optional.empty();
    }
    var sameTable = target.from().table().equals(target.to().table());
    var schemas = new LinkedHashSet<String>();
    schemas.add(target.from().physicalSchema());
    schemas.add(target.to().physicalSchema());
    return Optional.of(
        new Query(
            NAME,
            JSON.objectNode().put("correspondence", target.edge()),
            target.edge(),
            List.copyOf(schemas),
            List.of(
                request(fromSql.get(), inputs(target, End.FROM, sameTable)),
                request(toSql.get(), inputs(target, End.TO, sameTable)))));
  }

  private static List<ConstructionSql.Input> inputs(Target target, End checked, boolean sameTable) {
    var checkedSet = checked == End.FROM ? target.from() : target.to();
    var otherSet = checked == End.FROM ? target.to() : target.from();
    if (sameTable) {
      return List.of(new ConstructionSql.Input(checkedSet.table(), ConstructionSql.Reading.WHOLE));
    }
    return List.of(
        new ConstructionSql.Input(checkedSet.table(), ConstructionSql.Reading.SAMPLED),
        new ConstructionSql.Input(otherSet.table(), ConstructionSql.Reading.WHOLE));
  }

  /**
   * The anti-join count of one direction: the rows of the checked end read, those whose end
   * contains null, and those whose end has no match on the other end.
   */
  private static Optional<String> direction(Target target, End checked) {
    var checkedSet = checked == End.FROM ? target.from() : target.to();
    var otherSet = checked == End.FROM ? target.to() : target.from();
    var checkedColumns = new ArrayList<String>();
    var matches = new ArrayList<String>();
    for (var pair : target.hop().pairs()) {
      var checkedSide = checked == End.FROM ? pair.from() : pair.to();
      var otherSide = checked == End.FROM ? pair.to() : pair.from();
      var checkedColumn = column("c", nameOf(checkedSet, checkedSide.field().urnValue()));
      var otherColumn = column("o", nameOf(otherSet, otherSide.field().urnValue()));
      var checkedValue = normalized(checkedColumn, checkedSide.normalization());
      var otherValue = normalized(otherColumn, otherSide.normalization());
      if (checkedValue.isEmpty() || otherValue.isEmpty()) {
        return Optional.empty();
      }
      checkedColumns.add(checkedColumn);
      matches.add(otherValue.get() + " = " + checkedValue.get());
    }
    var unmatched =
        "NOT "
            + anyNull(checkedColumns)
            + " AND NOT EXISTS (SELECT 1 FROM "
            + table(otherSet.table())
            + " AS o WHERE "
            + String.join(" AND ", matches)
            + ")";
    return Optional.of(
        "SELECT "
            + count("count(*)")
            + " AS row_count, "
            + count("sum(CASE WHEN " + anyNull(checkedColumns) + " THEN 1 ELSE 0 END)")
            + " AS rows_with_null, "
            + count("sum(CASE WHEN " + unmatched + " THEN 1 ELSE 0 END)")
            + " AS unmatched_rows FROM "
            + table(checkedSet.table())
            + " AS c");
  }

  private static String nameOf(FieldSet set, String field) {
    return set.columns().get(set.fields().indexOf(field)).name();
  }

  /**
   * A value after its normalization steps, in the Query Engine's dialect; empty when a step has no
   * SQL form there, which leaves the correspondence unexamined.
   */
  private static Optional<String> normalized(String value, List<Normalization> steps) {
    var sql = value;
    for (var step : steps) {
      sql =
          switch (step) {
            case LOWER_CASE -> "lower(" + sql + ")";
            case TRIM -> "trim(" + sql + ")";
            case TO_TEXT -> "CAST(" + sql + " AS VARCHAR)";
            case DIGITS_ONLY -> "regexp_replace(" + sql + ", '[^0-9]', '', 'g')";
            case UNICODE_NFKC -> null;
          };
      if (sql == null) {
        return Optional.empty();
      }
    }
    return Optional.of(sql);
  }

  /** What one direction read. */
  private record Direction(long rows, long withNull, long unmatched) {
    long nonNull() {
      return rows - withNull;
    }

    long matched() {
      return nonNull() - unmatched;
    }
  }

  @Override
  public void record(GeneratorContext context, List<Read> reads, Output output) {
    for (var read : reads) {
      var edge = read.query().target().path("correspondence").asText();
      var target = target(context, edge);
      if (target.isEmpty()) {
        output.missing("the confirmed existence of the correspondence " + edge + " and its Fields");
        continue;
      }
      var observed = observe(context, output, read);
      conclude(context, output, observed, conclusion(target.get(), read, observed));
    }
  }

  private Conclusion conclusion(Target target, Read read, Observed observed) {
    var premises = new ArrayList<Dependency>();
    premises.add(Proposals.on(target.existence()));
    var revisions = new ArrayList<String>();
    revisions.add(target.existence().revision());
    var completed = completed(read);
    String undecidable = null;
    Optional<Proposal> proposal = Optional.empty();
    List<JsonNode> found = List.of();
    if (completed.isEmpty()) {
      undecidable = "A query exceeded a limit of the application";
    } else {
      var from = direction(completed.get().get(0));
      var to = direction(completed.get().get(1));
      if (from.nonNull() == 0 || to.nonNull() == 0) {
        undecidable = "An end has no value among the rows read";
      } else {
        var details =
            JSON.objectNode()
                .put("fromRowsRead", from.rows())
                .put("fromUnmatchedRows", from.unmatched())
                .put("toRowsRead", to.rows())
                .put("toUnmatchedRows", to.unmatched());
        var anyMatched = from.matched() > 0 || to.matched() > 0;
        var value = SetRelationships.of(from.unmatched() == 0, to.unmatched() == 0, anyMatched);
        var counterexamples = new ArrayList<JsonNode>();
        var absences = new ArrayList<JsonNode>();
        for (var item : SetRelationships.claims(value)) {
          var absence =
              item.equals(SetRelationships.everyValueMatched(End.FROM))
                  || item.equals(SetRelationships.everyValueMatched(End.TO))
                  || item.equals(SetRelationships.noValueMatched());
          (absence ? absences : counterexamples).add(item);
        }
        var checks = new ArrayList<Check>();
        if (!counterexamples.isEmpty()) {
          checks.add(
              check(
                  items(counterexamples),
                  CheckResult.SUPPORTED,
                  DataFinding.COUNTEREXAMPLE_FOUND,
                  observed,
                  details));
        }
        if (!absences.isEmpty()) {
          checks.add(
              check(
                  items(absences),
                  CheckResult.SUPPORTED,
                  DataFinding.NO_COUNTEREXAMPLE_FOUND,
                  observed,
                  details));
        }
        proposal =
            Optional.of(new Proposal(JSON.textNode(value), IdentityKey.choice(value), checks));
        var refuting = new ArrayList<JsonNode>();
        if (from.unmatched() > 0) {
          refuting.add(SetRelationships.everyValueMatched(End.FROM));
        }
        if (to.unmatched() > 0) {
          refuting.add(SetRelationships.everyValueMatched(End.TO));
        }
        if (anyMatched) {
          refuting.add(SetRelationships.noValueMatched());
        }
        found = refuting;
      }
    }
    var refuted = found;
    var reason = undecidable;
    return new Conclusion(
        target.edge(),
        Vocabulary.SET_RELATIONSHIP,
        proposal,
        fact ->
            SetRelationships.claims(fact.value().asText()).stream()
                .filter(refuted::contains)
                .toList(),
        reason == null
            ? Optional.empty()
            : Optional.of(
                fact ->
                    check(
                        items(SetRelationships.claims(fact.value().asText())),
                        CheckResult.UNDECIDABLE,
                        null,
                        observed,
                        JSON.objectNode().put("reason", reason))),
        premises,
        revisions);
  }

  private static Direction direction(ConstructionSql.Outcome.Read read) {
    return new Direction(
        number(read, "row_count"), number(read, "rows_with_null"), number(read, "unmatched_rows"));
  }
}
