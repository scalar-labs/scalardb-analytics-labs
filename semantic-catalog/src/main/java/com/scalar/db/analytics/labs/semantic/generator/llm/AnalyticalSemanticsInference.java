/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator.llm;

import static com.scalar.db.analytics.labs.semantic.generator.GeneratorSupport.existence;
import static com.scalar.db.analytics.labs.semantic.generator.GeneratorSupport.onFact;
import static com.scalar.db.analytics.labs.semantic.generator.GeneratorSupport.table;

import com.scalar.db.analytics.labs.semantic.generator.DataAccess.DataAccessException;
import com.scalar.db.analytics.labs.semantic.generator.Generator;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorException;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorResult;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorResult.Candidate;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorSupport;
import com.scalar.db.analytics.labs.semantic.model.DependencyRef;
import com.scalar.db.analytics.labs.semantic.model.ElementDescriptor;
import com.scalar.db.analytics.labs.semantic.model.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Json;
import com.scalar.db.analytics.labs.semantic.model.Predicates;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Analytical semantics inference (generators.md 3.3): Measures and business terms. A proposed
 * aggregation is run once through the application's SQL execution, and a candidate whose SQL does
 * not run is not proposed.
 */
public final class AnalyticalSemanticsInference implements Generator {
  private static final Set<String> FUNCTIONS =
      Set.of("sum", "count", "avg", "min", "max", "count_distinct");

  private final String identity;

  public AnalyticalSemanticsInference(String modelIdentity) {
    this.identity = modelIdentity;
  }

  @Override
  public String name() {
    return "llm.analytical-semantics";
  }

  @Override
  public String version() {
    return "1;model=" + identity + ";prompt=" + LlmSupport.PROMPT_VERSION;
  }

  @Override
  public Set<String> inputs() {
    return Set.of(
        DOCUMENT,
        RULING,
        MISSING_KNOWLEDGE,
        Generator.confirmed(ElementKind.FIELD, Predicates.MEANING));
  }

  @Override
  public GeneratorResult run(GeneratorContext context) throws GeneratorException {
    var catalog = context.catalog();
    var domain = catalog.domain();
    var support = new LlmSupport(context);
    if (catalog.elements(ElementKind.DATASET).stream()
        .noneMatch(dataset -> dataset.existence() == FactState.CONFIRMED)) {
      return GeneratorResult.of(List.of());
    }
    var prompt =
        "You are the analytical semantics inference Generator of a semantic data catalog.\n"
            + "Propose Measures (business metrics) and business terms that a Query Agent needs to answer business questions correctly.\n\n"
            + "Catalog (confirmed and candidate knowledge):\n"
            + support.renderCatalog()
            + "\n"
            + "Registered documents:\n"
            + support.renderDocuments()
            + "\n"
            + "Missing knowledge and corrections reported at question time:\n"
            + support.renderQuestionTimeInput()
            + "\n"
            + "Rules:\n"
            + "- A Measure aggregates one Dataset: function is one of sum, count, avg, min, max, count_distinct; expression is a DuckDB SQL expression over that Dataset's columns;"
            + " filter is a SQL condition on the same Dataset that keeps only the rows that count, or null; timeField is the column that dates a row for period questions, or null.\n"
            + "- Give each Measure a stable English identifier name and a label.\n"
            + "- A business term is a word users say; refersTo names what it points to: \"measure:<name>\", a Dataset \"source.namespace.table\", or a Field \"source.namespace.table.column\".\n"
            + "- Base definitions on the documents when they define them, and cite them (D1, D2, ...). Do not invent exclusions or definitions no evidence supports.\n"
            + "- Do not repeat Measures or business terms already in the catalog unless the evidence says the existing one is wrong.\n"
            + "- "
            + LlmSupport.LANGUAGE_RULE
            + "\n\n"
            + "Answer with exactly one JSON object:\n"
            + "{\"measures\": [{\"name\": \"ec_sales\", \"label\": \"...\", \"meaning\": \"...\", \"dataset\": \"source.namespace.table\", \"function\": \"sum\","
            + " \"expression\": \"amount\", \"filter\": null, \"timeField\": null, \"documents\": [], \"reason\": \"...\"}],"
            + " \"terms\": [{\"text\": \"...\", \"refersTo\": \"measure:ec_sales\", \"documents\": [], \"reason\": \"...\"}]}\n";
    var answer = support.ask("analytical-semantics", prompt);
    var candidates = new ArrayList<Candidate>();
    var missing = new ArrayList<String>();
    var measures = new HashMap<String, String>();
    for (var item : answer.path("measures")) {
      var name = LlmSupport.text(item, "name");
      var dataset = support.dataset(LlmSupport.text(item, "dataset"));
      var function =
          java.util.Objects.requireNonNullElse(LlmSupport.text(item, "function"), "")
              .toLowerCase(Locale.ROOT);
      var expression = LlmSupport.text(item, "expression");
      if (name == null
          || dataset.isEmpty()
          || !FUNCTIONS.contains(function)
          || expression == null) {
        continue;
      }
      var measure = Urns.node(domain, ElementKind.MEASURE, name);
      if (catalog.element(measure).isPresent()
          && GeneratorSupport.hasLiveFact(catalog, measure, Predicates.EXISTS)) {
        measures.put(name, measure);
        continue;
      }
      var filter = LlmSupport.text(item, "filter");
      var resource = support.datasetReference(dataset.get());
      var aggregate =
          function.equals("count_distinct")
              ? "count(DISTINCT " + expression + ")"
              : function + "(" + expression + ")";
      var sql =
          "SELECT "
              + aggregate
              + " AS value FROM "
              + table(resource)
              + (filter == null ? "" : " WHERE " + filter);
      try {
        context
            .data()
            .query(
                "measure-validation",
                Json.object().put("measure", measure).put("sql", sql),
                sql,
                List.of(resource));
      } catch (DataAccessException e) {
        missing.add(
            "Measure "
                + name
                + " was not proposed because its SQL does not run: "
                + e.getMessage());
        continue;
      }
      var rationale = support.rationale(item.path("documents"), null);
      var label = java.util.Objects.requireNonNullElse(LlmSupport.text(item, "label"), name);
      var existsDependencies = new ArrayList<DependencyRef>(rationale.dependencies());
      existsDependencies.addFirst(onFact(existence(domain, dataset.get())));
      candidates.add(
          Candidate.existence(
              ElementDescriptor.node(measure, ElementKind.MEASURE, null, label),
              existsDependencies,
              rationale.checks()));
      var dependencies = new ArrayList<DependencyRef>(rationale.dependencies());
      dependencies.addFirst(onFact(existence(domain, measure)));
      var meaning = LlmSupport.text(item, "meaning");
      if (meaning != null) {
        candidates.add(
            MeaningInference.candidate(
                measure,
                Predicates.MEANING,
                Json.object().put("text", meaning),
                dependencies,
                rationale));
      }
      candidates.add(
          MeaningInference.candidate(
              measure,
              Predicates.BASE_DATASET,
              Json.object().put("dataset", dataset.get()),
              dependencies,
              rationale));
      candidates.add(
          new Candidate(
              measure,
              null,
              Predicates.AGGREGATION_RULE,
              com.scalar.db.analytics.labs.semantic.model.Scope.UNRESTRICTED,
              Json.object().put("function", function).put("expression", expression),
              dependencies,
              rationale.covering(List.of("function", "expression")),
              List.of()));
      if (filter != null) {
        candidates.add(
            MeaningInference.candidate(
                measure,
                Predicates.FILTER,
                Json.object().put("condition", filter),
                dependencies,
                rationale));
      }
      var timeField = support.column(dataset.get(), LlmSupport.text(item, "timeField"));
      if (timeField.isPresent()) {
        var timeDependencies = new ArrayList<DependencyRef>(dependencies);
        timeDependencies.add(onFact(existence(domain, timeField.get())));
        candidates.add(
            MeaningInference.candidate(
                measure,
                Predicates.REFERENCE_TIME_FIELD,
                Json.object().put("field", timeField.get()),
                timeDependencies,
                rationale));
      }
      measures.put(name, measure);
    }
    for (var item : answer.path("terms")) {
      var text = LlmSupport.text(item, "text");
      var refersTo = LlmSupport.text(item, "refersTo");
      if (text == null || refersTo == null) {
        continue;
      }
      String target = null;
      if (refersTo.startsWith("measure:")) {
        target = measures.get(refersTo.substring("measure:".length()));
      } else {
        target = support.dataset(refersTo).or(() -> support.field(refersTo)).orElse(null);
      }
      if (target == null) {
        continue;
      }
      var term = Urns.node(domain, ElementKind.BUSINESS_TERM, text);
      var rationale = support.rationale(item.path("documents"), null);
      if (!GeneratorSupport.hasLiveFact(catalog, term, Predicates.EXISTS)) {
        candidates.add(
            Candidate.existence(
                ElementDescriptor.node(term, ElementKind.BUSINESS_TERM, null, text),
                rationale.dependencies(),
                rationale.checks()));
      }
      var dependencies = new ArrayList<DependencyRef>(rationale.dependencies());
      dependencies.addFirst(onFact(existence(domain, term)));
      if (!GeneratorSupport.hasLiveFact(catalog, term, Predicates.WRITTEN_FORM)) {
        candidates.add(
            MeaningInference.candidate(
                term,
                Predicates.WRITTEN_FORM,
                Json.object().put("text", text),
                dependencies,
                rationale));
      }
      if (!GeneratorSupport.hasLiveFact(catalog, term, Predicates.REFERENCED_ELEMENT)) {
        var referenceDependencies = new ArrayList<DependencyRef>(dependencies);
        referenceDependencies.add(onFact(existence(domain, target)));
        candidates.add(
            MeaningInference.candidate(
                term,
                Predicates.REFERENCED_ELEMENT,
                Json.object().put("element", target),
                referenceDependencies,
                rationale));
      }
    }
    return new GeneratorResult(candidates, List.of(), missing);
  }
}
