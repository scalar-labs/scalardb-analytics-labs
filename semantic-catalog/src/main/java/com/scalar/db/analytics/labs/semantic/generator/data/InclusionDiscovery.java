/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator.data;

import com.scalar.db.analytics.labs.semantic.ConstructionSql;
import com.scalar.db.analytics.labs.semantic.generator.Acceptances;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext.Fact;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext.Trigger;
import com.scalar.db.analytics.labs.semantic.generator.Output;
import com.scalar.db.analytics.labs.semantic.generator.Proposals;
import com.scalar.db.analytics.labs.semantic.generator.schema.Declarations;
import com.scalar.db.analytics.labs.semantic.model.Check;
import com.scalar.db.analytics.labs.semantic.model.Dependency;
import com.scalar.db.analytics.labs.semantic.model.ElementIdentity;
import com.scalar.db.analytics.labs.semantic.model.IdentityKey;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.MatchingCondition;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Scope;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.CheckResult;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ValidationMethod;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Inclusion discovery: whether the values of a Field are contained in a Field confirmed not to
 * repeat, which suggests an identifier correspondence no declaration states (see {@code
 * docs/semantic-catalog/generators.md}, "Data checks"). It explores single Fields against
 * single-Field unique sets, the initial column-set size, pairing a newly confirmed Field with every
 * such set and a newly confirmed uniqueness with every Field, when both columns have the same
 * mapped type and it is not a boolean, date, time, or interval type, and when the correspondence
 * does not exist yet.
 *
 * <p>The Fields of one table paired with one unique set are checked together: one statement samples
 * the table and counts, for each Field, the values not found in the whole of the unique set. A run
 * plans at most {@link #QUERIES_PER_RUN} such statements and records the rest as missing input.
 * When every value read of a Field is found, it proposes the correspondence's existence with {@code
 * inference}, never {@code data-check}: columns whose values happen to overlap need not denote the
 * same objects. The observation is an input read and a dependency, not a check reference.
 */
final class InclusionDiscovery extends DataCheck {
  static final String NAME = "inclusion-discovery";
  private static final String DOES_NOT_REPEAT = Vocabulary.DOES_NOT_REPEAT;
  private static final IdentityKey EXISTS = IdentityKey.choice("exists");

  InclusionDiscovery() {
    super(NAME);
  }

  @Override
  public List<Input> inputs() {
    return List.of(
        new Input.Confirmed(ElementKind.FIELD, Vocabulary.EXISTENCE),
        new Input.Confirmed(ElementKind.DATASET, Vocabulary.UNIQUENESS_PREFIX));
  }

  /**
   * The most queries one run plans, an initial bound. Each query reads one table against one unique
   * set, so a run never holds the build queue for the square of the catalog's size; the targets
   * past it are recorded as missing input.
   */
  static final int QUERIES_PER_RUN = 100;

  /** A Field, a single-Field set confirmed not to repeat, and that uniqueness Fact. */
  private record Pair(FieldSet from, FieldSet to, Fact uniqueness) {
    String key() {
      return from.fields().getFirst() + " " + to.fields().getFirst();
    }

    /** The Fields of one table checked against one unique set together. */
    String batch() {
      return to.fields().getFirst() + " " + from.dataset();
    }
  }

  @Override
  public Plan plan(GeneratorContext context) {
    var pairs = new LinkedHashMap<String, Pair>();
    var unique = uniqueSets(context);
    for (var trigger : context.triggers()) {
      if (!(trigger instanceof Trigger.Confirmed confirmed)) {
        continue;
      }
      if (confirmed.kind() == ElementKind.FIELD) {
        var from = FieldSet.of(context, List.of(confirmed.element()));
        if (from.isPresent()) {
          for (var to : unique) {
            pair(context, from.get(), to).ifPresent(found -> pairs.putIfAbsent(found.key(), found));
          }
        }
      } else if (confirmed.kind() == ElementKind.DATASET) {
        var to =
            unique.stream()
                .filter(found -> found.uniqueness().urn().equals(confirmed.fact()))
                .findFirst();
        if (to.isPresent()) {
          for (var from : allFields(context)) {
            pair(context, from, to.get()).ifPresent(found -> pairs.putIfAbsent(found.key(), found));
          }
        }
      }
    }
    var batches = new LinkedHashMap<String, List<Pair>>();
    for (var pair : pairs.values()) {
      batches.computeIfAbsent(pair.batch(), ignored -> new ArrayList<>()).add(pair);
    }
    var queries = new ArrayList<Query>();
    var unread = new ArrayList<String>();
    for (var batch : batches.values()) {
      if (queries.size() < QUERIES_PER_RUN) {
        queries.add(query(batch));
      } else {
        unread.add(
            "the inclusion of the Fields of "
                + batch.getFirst().from().dataset()
                + " in "
                + batch.getFirst().to().fields().getFirst()
                + ", past the bound of "
                + QUERIES_PER_RUN
                + " queries per run");
      }
    }
    return new Plan(queries, unread);
  }

  /** A single-Field set confirmed not to repeat. */
  private record Unique(FieldSet set, Fact uniqueness) {}

  private static List<Unique> uniqueSets(GeneratorContext context) {
    var sets = new ArrayList<Unique>();
    for (var resource : context.physicalSchemas()) {
      var dataset =
          context
              .table(resource)
              .flatMap(GeneratorContext.Table::reference)
              .flatMap(context::element);
      if (dataset.isEmpty()) {
        continue;
      }
      for (var predicate : context.predicates(dataset.get())) {
        var fields = Vocabulary.uniquenessFields(predicate);
        if (fields.size() != 1) {
          continue;
        }
        var field = fields.getFirst();
        var fact =
            context
                .closedRangeFact(dataset.get(), predicate, IdentityKey.choice(DOES_NOT_REPEAT))
                .filter(found -> found.state() == FactState.CONFIRMED);
        var set = FieldSet.of(context, List.of(field));
        if (fact.isPresent() && set.isPresent()) {
          sets.add(new Unique(set.get(), fact.get()));
        }
      }
    }
    return sets;
  }

  private static List<FieldSet> allFields(GeneratorContext context) {
    var fields = new ArrayList<FieldSet>();
    for (var resource : context.physicalSchemas()) {
      var dataset =
          context
              .table(resource)
              .flatMap(GeneratorContext.Table::reference)
              .flatMap(context::element);
      if (dataset.isEmpty()) {
        continue;
      }
      for (var field : context.fields(dataset.get())) {
        FieldSet.of(context, List.of(field.element())).ifPresent(fields::add);
      }
    }
    return fields;
  }

  private static Optional<Pair> pair(GeneratorContext context, FieldSet from, Unique to) {
    if (from.fields().equals(to.set().fields())) {
      return Optional.empty();
    }
    var fromKind = analyticsKind(from);
    var toKind = analyticsKind(to.set());
    if (fromKind == null || !fromKind.equals(toKind) || !comparable(fromKind)) {
      return Optional.empty();
    }
    if (context.correspondence(identity(from, to.set())).isPresent()) {
      return Optional.empty();
    }
    return Optional.of(new Pair(from, to.set(), to.uniqueness()));
  }

  /** Whether values of a type can denote objects: not a boolean or a time. */
  private static boolean comparable(String analyticsKind) {
    return !analyticsKind.equals("boolean")
        && !analyticsKind.contains("date")
        && !analyticsKind.contains("time")
        && !analyticsKind.contains("interval");
  }

  private static @Nullable String analyticsKind(FieldSet set) {
    var column = set.columns().getFirst();
    if (!(column.table().observed() instanceof GeneratorContext.Observed.Read read)) {
      return null;
    }
    return Declarations.of(read.tableMetadata())
        .column(column.name())
        .map(Declarations.Column::analyticsKind)
        .orElse(null);
  }

  private static ElementIdentity.IdentifierCorrespondence identity(FieldSet from, FieldSet to) {
    return new ElementIdentity.IdentifierCorrespondence(
        MatchingCondition.direct(
            MatchingCondition.Pair.of(
                Ref.urn(from.fields().getFirst()), Ref.urn(to.fields().getFirst()))));
  }

  /**
   * One anti-join count per Field of one table against one unique set, in one statement that reads
   * the table once.
   */
  private static Query query(List<Pair> batch) {
    var first = batch.getFirst();
    var to = column("o", first.to().columns().getFirst().name());
    var select = new ArrayList<String>();
    select.add(count("count(*)") + " AS row_count");
    for (var index = 0; index < batch.size(); index++) {
      var from = column("c", batch.get(index).from().columns().getFirst().name());
      select.add(count("count(" + from + ")") + " AS non_null_rows_" + index);
      select.add(
          count(
                  "sum(CASE WHEN "
                      + from
                      + " IS NOT NULL AND NOT EXISTS (SELECT 1 FROM "
                      + table(first.to().table())
                      + " AS o WHERE "
                      + to
                      + " = "
                      + from
                      + ") THEN 1 ELSE 0 END)")
              + " AS unmatched_rows_"
              + index);
    }
    var sql =
        "SELECT " + String.join(", ", select) + " FROM " + table(first.from().table()) + " AS c";
    var inputs =
        first.from().table().equals(first.to().table())
            ? List.of(
                new ConstructionSql.Input(first.from().table(), ConstructionSql.Reading.WHOLE))
            : List.of(
                new ConstructionSql.Input(first.from().table(), ConstructionSql.Reading.SAMPLED),
                new ConstructionSql.Input(first.to().table(), ConstructionSql.Reading.WHOLE));
    var target = JSON.objectNode().put("to", first.to().fields().getFirst());
    var fields = target.putArray("from");
    batch.forEach(pair -> fields.add(pair.from().fields().getFirst()));
    var schemas = new ArrayList<String>();
    schemas.add(first.from().physicalSchema());
    if (!schemas.contains(first.to().physicalSchema())) {
      schemas.add(first.to().physicalSchema());
    }
    return new Query(NAME, target, first.batch(), schemas, List.of(request(sql, inputs)));
  }

  @Override
  public void record(GeneratorContext context, List<Read> reads, Output output) {
    for (var read : reads) {
      var toField = read.query().target().path("to").asText();
      var to = FieldSet.of(context, List.of(toField));
      var uniqueness =
          to.flatMap(
              set ->
                  context
                      .closedRangeFact(
                          set.dataset(), set.uniqueness(), IdentityKey.choice(DOES_NOT_REPEAT))
                      .filter(fact -> fact.state() == FactState.CONFIRMED));
      if (to.isEmpty() || uniqueness.isEmpty()) {
        output.missing("the confirmed existence and uniqueness of " + toField);
        continue;
      }
      var observed = observe(context, output, read);
      var completed = completed(read);
      if (completed.isEmpty()) {
        continue;
      }
      var result = completed.get().getFirst();
      var fromFields = read.query().target().path("from");
      for (var index = 0; index < fromFields.size(); index++) {
        var fromField = fromFields.get(index).asText();
        var from = FieldSet.of(context, List.of(fromField));
        var nonNull = number(result, "non_null_rows_" + index);
        if (from.isEmpty() || nonNull == 0 || number(result, "unmatched_rows_" + index) > 0) {
          continue;
        }
        propose(context, output, observed, from.get(), to.get(), uniqueness.get(), nonNull);
      }
    }
  }

  private void propose(
      GeneratorContext context,
      Output output,
      Observed observed,
      FieldSet from,
      FieldSet to,
      Fact uniqueness,
      long nonNull) {
    var identity = identity(from, to);
    var item = JSON.objectNode();
    item.putArray("pair").add(from.fields().getFirst()).add(to.fields().getFirst());
    var inference =
        new Check(
            ValidationMethod.INFERENCE,
            item,
            CheckResult.SUPPORTED,
            null,
            null,
            stored(
                JSON.objectNode()
                    .put("check", name())
                    .put("nonNullRowsRead", nonNull)
                    .put(
                        "basis",
                        "Every value of the Field read is a value of a Field that does not repeat;"
                            + " values that overlap need not denote the same objects")));
    var dependencies = new ArrayList<Dependency>();
    dependencies.add(observed.dependency());
    dependencies.add(Proposals.on(from.columns().getFirst().existence()));
    dependencies.add(Proposals.on(to.columns().getFirst().existence()));
    dependencies.add(Proposals.on(uniqueness));
    var existing =
        context
            .correspondence(identity)
            .flatMap(edge -> context.closedRangeFact(edge, Vocabulary.EXISTENCE, EXISTS));
    var restated =
        Acceptances.restate(existing, ownership(context), dependencies, List.of(inference));
    var edge = output.add(new LogRecord.Element(identity));
    var address =
        output.add(new LogRecord.FactAddress(edge, Vocabulary.EXISTENCE, Scope.EMPTY, null));
    var fact = output.add(new LogRecord.Fact(address, JSON.textNode("exists"), EXISTS));
    output.add(
        new LogRecord.Accept(
            fact,
            restated.dependencies(),
            restated.checks(),
            inputsRead(
                observed,
                List.of(),
                List.of(
                    from.columns().getFirst().existence().revision(),
                    to.columns().getFirst().existence().revision(),
                    uniqueness.revision())),
            context.provenance()));
  }
}
