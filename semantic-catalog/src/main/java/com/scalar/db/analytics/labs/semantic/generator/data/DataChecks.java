/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator.data;

import static com.scalar.db.analytics.labs.semantic.generator.GeneratorSupport.column;
import static com.scalar.db.analytics.labs.semantic.generator.GeneratorSupport.datasetResource;
import static com.scalar.db.analytics.labs.semantic.generator.GeneratorSupport.existence;
import static com.scalar.db.analytics.labs.semantic.generator.GeneratorSupport.onFact;
import static com.scalar.db.analytics.labs.semantic.generator.GeneratorSupport.quote;
import static com.scalar.db.analytics.labs.semantic.generator.GeneratorSupport.table;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.generator.CatalogView;
import com.scalar.db.analytics.labs.semantic.generator.CatalogView.ElementView;
import com.scalar.db.analytics.labs.semantic.generator.DataAccess.DataAccessException;
import com.scalar.db.analytics.labs.semantic.generator.DataAccess.DataObservation;
import com.scalar.db.analytics.labs.semantic.generator.Generator;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorResult;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorResult.Candidate;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorSupport;
import com.scalar.db.analytics.labs.semantic.model.Check;
import com.scalar.db.analytics.labs.semantic.model.DependencyRef;
import com.scalar.db.analytics.labs.semantic.model.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Json;
import com.scalar.db.analytics.labs.semantic.model.Predicates;
import com.scalar.db.analytics.labs.semantic.model.Scope;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The data check Generators (generators.md 3.2). Their queries are counts and set comparisons
 * through the application's SQL execution; they never read data rows themselves. A query that hits
 * a limit makes the check undecidable rather than supporting the whole table from part of it.
 */
public final class DataChecks implements Generator {
  public enum Rule {
    UNIQUENESS("data.uniqueness-check"),
    JOIN("data.join-multiplicity-and-participation"),
    IDENTIFIER_SET("data.identifier-set-relationship");

    private final String generator;

    Rule(String generator) {
      this.generator = generator;
    }
  }

  private final Rule rule;

  public DataChecks(Rule rule) {
    this.rule = rule;
  }

  public static List<Generator> all() {
    var generators = new ArrayList<Generator>();
    for (var rule : Rule.values()) {
      generators.add(new DataChecks(rule));
    }
    return generators;
  }

  @Override
  public String name() {
    return rule.generator;
  }

  @Override
  public String version() {
    return "1";
  }

  @Override
  public Set<String> inputs() {
    return switch (rule) {
      case UNIQUENESS ->
          Set.of(
              Generator.confirmed(ElementKind.DATASET, Predicates.KEY),
              Generator.confirmed(ElementKind.DATASET, "uniqueness"),
              PHYSICAL_SCHEMA);
      case JOIN -> Set.of(Generator.confirmed(ElementKind.JOIN, Predicates.EXISTS));
      case IDENTIFIER_SET ->
          Set.of(Generator.confirmed(ElementKind.IDENTIFIER_CORRESPONDENCE, Predicates.EXISTS));
    };
  }

  @Override
  public GeneratorResult run(GeneratorContext context) {
    var candidates = new ArrayList<Candidate>();
    var missing = new ArrayList<String>();
    switch (rule) {
      case UNIQUENESS -> uniqueness(context, candidates, missing);
      case JOIN -> joins(context, candidates, missing);
      case IDENTIFIER_SET -> identifierSets(context, candidates, missing);
    }
    return new GeneratorResult(candidates, List.of(), missing);
  }

  // Uniqueness check

  private static void uniqueness(
      GeneratorContext context, List<Candidate> out, List<String> missing) {
    var catalog = context.catalog();
    var domain = catalog.domain();
    for (var dataset : catalog.elements(ElementKind.DATASET)) {
      if (dataset.existence() != FactState.CONFIRMED) {
        continue;
      }
      var sets = new LinkedHashSet<List<String>>();
      for (var fact : catalog.facts(dataset.urn())) {
        if (fact.state() == FactState.REJECTED || fact.state() == FactState.RETIRED) {
          continue;
        }
        if (fact.predicate().equals(Predicates.KEY) && fact.value().has("fields")) {
          var fields = new ArrayList<String>();
          fact.value().path("fields").forEach(field -> fields.add(field.asText()));
          sets.add(fields.stream().map(urn -> column(catalog, urn)).sorted().toList());
        }
        if (Predicates.isUniqueness(fact.predicate())) {
          sets.add(
              List.of(
                  fact.predicate().substring(Predicates.UNIQUENESS_PREFIX.length()).split(",")));
        }
      }
      for (var columns : sets) {
        if (!columns.stream()
            .allMatch(
                name -> GeneratorSupport.confirmed(catalog, Urns.field(dataset.urn(), name)))) {
          continue;
        }
        var target = Json.object().put("dataset", dataset.urn());
        target.set("columns", Json.tree(columns));
        var tableName = table(datasetResource(catalog, dataset.urn()));
        var list = columns.stream().map(GeneratorSupport::quote).collect(Collectors.joining(", "));
        var notNull =
            columns.stream()
                .map(name -> quote(name) + " IS NOT NULL")
                .collect(Collectors.joining(" AND "));
        var anyNull =
            columns.stream()
                .map(name -> quote(name) + " IS NULL")
                .collect(Collectors.joining(" OR "));
        var sql =
            "SELECT (SELECT count(*) FROM "
                + tableName
                + ") AS row_count,"
                + " (SELECT count(*) FROM (SELECT DISTINCT "
                + list
                + " FROM "
                + tableName
                + " WHERE "
                + notNull
                + ")) AS distinct_count,"
                + " (SELECT count(*) FROM "
                + tableName
                + " WHERE "
                + anyNull
                + ") AS null_count";
        var dependencies = new ArrayList<DependencyRef>();
        columns.forEach(
            name -> dependencies.add(onFact(existence(domain, Urns.field(dataset.urn(), name)))));
        var predicate = Predicates.uniqueness(columns);
        try {
          var observation =
              context
                  .data()
                  .query(
                      "uniqueness", target, sql, List.of(datasetResource(catalog, dataset.urn())));
          var row = observation.rows().getFirst();
          var unique = row.get(0).asLong() == row.get(1).asLong() && row.get(2).asLong() == 0;
          var rationale = rationale(observation);
          dependencies.add(rationale);
          out.add(
              new Candidate(
                  dataset.urn(),
                  null,
                  predicate,
                  Scope.UNRESTRICTED,
                  Json.object().put("unique", unique),
                  dependencies,
                  List.of(GeneratorSupport.supported(Check.Method.DATA_CHECK, rationale)),
                  List.of()));
          var declared =
              com.scalar.db.analytics.labs.semantic.model.Facts.fact(
                  domain,
                  dataset.urn(),
                  predicate,
                  Scope.UNRESTRICTED,
                  Json.object().put("unique", true));
          if (!unique && catalog.fact(declared).isPresent()) {
            // The check refutes a declared uniqueness of the same set.
            out.add(
                new Candidate(
                    dataset.urn(),
                    null,
                    predicate,
                    Scope.UNRESTRICTED,
                    Json.object().put("unique", true),
                    dependencies,
                    List.of(
                        GeneratorSupport.check(
                            Check.Method.DATA_CHECK,
                            List.of(Predicates.VALUE_ITEM),
                            Check.Result.REFUTED,
                            rationale)),
                    List.of()));
          }
        } catch (DataAccessException e) {
          missing.add(
              "uniqueness of "
                  + dataset.descriptor().label()
                  + "("
                  + String.join(",", columns)
                  + "): "
                  + e.getMessage());
        }
      }
    }
  }

  private static DependencyRef.OnObservation rationale(DataObservation observation) {
    return new DependencyRef.OnObservation(
        observation.inputResource(),
        observation.inputObservation(),
        "result",
        observation.digest());
  }

  // Join multiplicity and participation

  private static void joins(GeneratorContext context, List<Candidate> out, List<String> missing) {
    var catalog = context.catalog();
    var domain = catalog.domain();
    for (var join : catalog.elements(ElementKind.JOIN)) {
      if (join.existence() != FactState.CONFIRMED) {
        continue;
      }
      var descriptor = join.descriptor();
      var from = datasetResource(catalog, java.util.Objects.requireNonNull(descriptor.from()));
      var to = datasetResource(catalog, java.util.Objects.requireNonNull(descriptor.to()));
      var fromColumns = new ArrayList<String>();
      var toColumns = new ArrayList<String>();
      for (var pair : descriptor.identifyingOrEmpty().path("on")) {
        fromColumns.add(column(catalog, pair.path(0).asText()));
        toColumns.add(column(catalog, pair.path(1).asText()));
      }
      var condition = new ArrayList<String>();
      for (var i = 0; i < fromColumns.size(); i++) {
        condition.add("f." + quote(fromColumns.get(i)) + " = t." + quote(toColumns.get(i)));
      }
      var on = String.join(" AND ", condition);
      var fromList =
          fromColumns.stream().map(GeneratorSupport::quote).collect(Collectors.joining(", "));
      var toList =
          toColumns.stream().map(GeneratorSupport::quote).collect(Collectors.joining(", "));
      var sql =
          "SELECT (SELECT count(*) FROM "
              + table(from)
              + ") AS from_rows,"
              + " (SELECT count(*) FROM (SELECT DISTINCT "
              + fromList
              + " FROM "
              + table(from)
              + ")) AS from_distinct,"
              + " (SELECT count(*) FROM "
              + table(to)
              + ") AS to_rows,"
              + " (SELECT count(*) FROM (SELECT DISTINCT "
              + toList
              + " FROM "
              + table(to)
              + ")) AS to_distinct,"
              + " (SELECT count(*) FROM "
              + table(from)
              + " f WHERE NOT EXISTS (SELECT 1 FROM "
              + table(to)
              + " t WHERE "
              + on
              + ")) AS from_unmatched,"
              + " (SELECT count(*) FROM "
              + table(to)
              + " t WHERE NOT EXISTS (SELECT 1 FROM "
              + table(from)
              + " f WHERE "
              + on
              + ")) AS to_unmatched";
      var target = Json.object().put("join", join.urn());
      try {
        var observation = context.data().query("join", target, sql, List.of(from, to));
        var row = observation.rows().getFirst();
        var fromUnique = row.get(0).asLong() == row.get(1).asLong();
        var toUnique = row.get(2).asLong() == row.get(3).asLong();
        var multiplicity =
            fromUnique && toUnique
                ? "one-to-one"
                : toUnique ? "many-to-one" : fromUnique ? "one-to-many" : "many-to-many";
        var rationale = rationale(observation);
        var dependencies = List.<DependencyRef>of(onFact(existence(domain, join.urn())), rationale);
        var check = List.of(GeneratorSupport.supported(Check.Method.DATA_CHECK, rationale));
        out.add(
            new Candidate(
                join.urn(),
                null,
                Predicates.MULTIPLICITY,
                Scope.UNRESTRICTED,
                Json.object().put("multiplicity", multiplicity),
                dependencies,
                check,
                List.of()));
        out.add(
            new Candidate(
                join.urn(),
                null,
                Predicates.PARTICIPATION_FROM,
                Scope.UNRESTRICTED,
                participation(row.get(4)),
                dependencies,
                check,
                List.of()));
        out.add(
            new Candidate(
                join.urn(),
                null,
                Predicates.PARTICIPATION_TO,
                Scope.UNRESTRICTED,
                participation(row.get(5)),
                dependencies,
                check,
                List.of()));
      } catch (DataAccessException e) {
        missing.add("join checks of " + descriptor.label() + ": " + e.getMessage());
      }
    }
  }

  private static JsonNode participation(JsonNode unmatched) {
    return Json.object().put("participation", unmatched.asLong() == 0 ? "all" : "some");
  }

  // Set relationship of an identifier correspondence

  private static void identifierSets(
      GeneratorContext context, List<Candidate> out, List<String> missing) {
    var catalog = context.catalog();
    var domain = catalog.domain();
    for (var correspondence : catalog.elements(ElementKind.IDENTIFIER_CORRESPONDENCE)) {
      if (correspondence.existence() != FactState.CONFIRMED) {
        continue;
      }
      var descriptor = correspondence.descriptor();
      var identifying = descriptor.identifyingOrEmpty();
      var fromField = java.util.Objects.requireNonNull(descriptor.from());
      var toField = java.util.Objects.requireNonNull(descriptor.to());
      if (identifying.path("steps").size() > 1) {
        missing.add(
            "set relationship of " + descriptor.label() + ": only direct matching is checked");
        continue;
      }
      var from = fieldReference(catalog, fromField);
      var to = fieldReference(catalog, toField);
      var fromValues =
          "SELECT DISTINCT "
              + transform(identifying, "fromExpression", from[1])
              + " AS v FROM "
              + table(from[0])
              + " WHERE "
              + quote(from[1])
              + " IS NOT NULL";
      var toValues =
          "SELECT DISTINCT "
              + transform(identifying, "toExpression", to[1])
              + " AS v FROM "
              + table(to[0])
              + " WHERE "
              + quote(to[1])
              + " IS NOT NULL";
      var sql =
          "SELECT (SELECT count(*) FROM ("
              + fromValues
              + ") a WHERE v NOT IN ("
              + toValues
              + ")) AS only_from,"
              + " (SELECT count(*) FROM ("
              + toValues
              + ") b WHERE v NOT IN ("
              + fromValues
              + ")) AS only_to,"
              + " (SELECT count(*) FROM ("
              + fromValues
              + ") c WHERE v IN ("
              + toValues
              + ")) AS shared";
      var target = Json.object().put("correspondence", correspondence.urn());
      try {
        var observation =
            context.data().query("identifier-set", target, sql, List.of(from[0], to[0]));
        var row = observation.rows().getFirst();
        var onlyFrom = row.get(0).asLong();
        var onlyTo = row.get(1).asLong();
        var shared = row.get(2).asLong();
        var relationship =
            shared == 0
                ? "disjoint"
                : onlyFrom == 0 && onlyTo == 0
                    ? "equal"
                    : onlyFrom == 0 ? "subset" : onlyTo == 0 ? "superset" : "overlap";
        var rationale = rationale(observation);
        out.add(
            new Candidate(
                correspondence.urn(),
                null,
                Predicates.SET_RELATIONSHIP,
                Scope.UNRESTRICTED,
                Json.object().put("relationship", relationship),
                List.of(onFact(existence(domain, correspondence.urn())), rationale),
                List.of(GeneratorSupport.supported(Check.Method.DATA_CHECK, rationale)),
                List.of()));
      } catch (DataAccessException e) {
        missing.add("set relationship of " + descriptor.label() + ": " + e.getMessage());
      }
    }
  }

  /** The table resource identifier and column name of a Field. */
  private static String[] fieldReference(CatalogView catalog, String field) {
    ElementView view = catalog.element(field).orElseThrow();
    var dataset = java.util.Objects.requireNonNull(view.descriptor().parent());
    return new String[] {datasetResource(catalog, dataset), view.descriptor().label()};
  }

  /**
   * The value compared for one side: the column, or a normalization the matching condition gives.
   */
  private static String transform(JsonNode identifying, String key, String column) {
    var normalization = identifying.path("normalization").asText("");
    var expression = quote(column);
    return switch (normalization) {
      case "lower-trim" -> "lower(trim(CAST(" + expression + " AS VARCHAR)))";
      case "text" -> "CAST(" + expression + " AS VARCHAR)";
      default -> "CAST(" + expression + " AS VARCHAR)";
    };
  }
}
