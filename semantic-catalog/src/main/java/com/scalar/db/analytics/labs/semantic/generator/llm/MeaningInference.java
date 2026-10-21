/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator.llm;

import static com.scalar.db.analytics.labs.semantic.generator.GeneratorSupport.existence;
import static com.scalar.db.analytics.labs.semantic.generator.GeneratorSupport.hasLiveFact;
import static com.scalar.db.analytics.labs.semantic.generator.GeneratorSupport.onFact;

import com.fasterxml.jackson.databind.JsonNode;
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
import com.scalar.db.analytics.labs.semantic.model.Scope;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Field meaning inference and row meaning inference (generators.md 3.3). Row meaning inference also
 * proposes the concept a row represents, which concept inference owns in the design; the prototype
 * folds it in to keep the number of model calls down.
 */
public final class MeaningInference implements Generator {
  public enum Rule {
    FIELD("llm.field-meaning"),
    ROW("llm.row-meaning");

    private final String generator;

    Rule(String generator) {
      this.generator = generator;
    }
  }

  private static final Set<String> VALUE_KINDS =
      Set.of("identifier", "classification", "amount", "quantity", "time", "string", "boolean");
  private static final Set<String> ROW_KINDS =
      Set.of("current-state", "event", "state-per-time", "pre-aggregated");

  private final Rule rule;
  private final String identity;

  public MeaningInference(Rule rule, String modelIdentity) {
    this.rule = rule;
    this.identity = modelIdentity;
  }

  @Override
  public String name() {
    return rule.generator;
  }

  @Override
  public String version() {
    return "1;model=" + identity + ";prompt=" + LlmSupport.PROMPT_VERSION;
  }

  @Override
  public Set<String> inputs() {
    var kind = rule == Rule.FIELD ? ElementKind.FIELD : ElementKind.DATASET;
    return Set.of(
        Generator.confirmed(kind, Predicates.EXISTS), DOCUMENT, RULING, MISSING_KNOWLEDGE);
  }

  @Override
  public GeneratorResult run(GeneratorContext context) throws GeneratorException {
    return rule == Rule.FIELD ? fields(context) : rows(context);
  }

  private GeneratorResult fields(GeneratorContext context) throws GeneratorException {
    var catalog = context.catalog();
    var domain = catalog.domain();
    var support = new LlmSupport(context);
    var targets = new ArrayList<String>();
    var data = new StringBuilder();
    for (var dataset : catalog.elements(ElementKind.DATASET)) {
      if (dataset.existence() != FactState.CONFIRMED) {
        continue;
      }
      var pending =
          GeneratorSupport.confirmedFields(catalog, dataset.urn()).stream()
              .filter(field -> !hasLiveFact(catalog, field.urn(), Predicates.MEANING))
              .toList();
      if (pending.isEmpty()) {
        continue;
      }
      pending.forEach(field -> targets.add(support.fieldReference(field.urn())));
      data.append(support.renderData(dataset.urn()));
    }
    if (targets.isEmpty()) {
      return GeneratorResult.of(List.of());
    }
    var prompt =
        "You are the Field meaning inference Generator of a semantic data catalog.\n"
            + "Propose, for each target Field, what its values mean in the business.\n\n"
            + "Catalog (confirmed and candidate knowledge):\n"
            + support.renderCatalog()
            + "\n"
            + "Data statistics:\n"
            + data
            + "\n"
            + "Registered documents:\n"
            + support.renderDocuments()
            + "\n"
            + "Missing knowledge and corrections reported at question time:\n"
            + support.renderQuestionTimeInput()
            + "\n"
            + "Target Fields: "
            + String.join(", ", targets)
            + "\n\n"
            + "Rules:\n"
            + "- valueKind is one of identifier, classification, amount, quantity, time, string, boolean.\n"
            + "- For a classification, give every code value that occurs and its meaning in codes; otherwise codes is null.\n"
            + "- unit is the unit or currency of an amount or quantity, or null. nullMeaning says what a NULL means, or null.\n"
            + "- Cite the documents you used by reference (D1, D2, ...) in documents; leave it empty when you inferred from names and data.\n"
            + "- Do not guess what neither the names, the data, nor the documents support; omit such a Field.\n"
            + "- "
            + LlmSupport.LANGUAGE_RULE
            + "\n\n"
            + "Answer with exactly one JSON object:\n"
            + "{\"fields\": [{\"field\": \"source.namespace.table.column\", \"meaning\": \"...\", \"valueKind\": \"...\","
            + " \"unit\": null, \"nullMeaning\": null, \"codes\": null, \"documents\": [], \"reason\": \"...\"}]}\n";
    var answer = support.ask("field-meaning", prompt);
    var candidates = new ArrayList<Candidate>();
    for (var item : answer.path("fields")) {
      var field = support.field(LlmSupport.text(item, "field"));
      if (field.isEmpty() || !targets.contains(support.fieldReference(field.get()))) {
        continue;
      }
      var urn = field.get();
      var dataset = catalog.element(urn).map(view -> view.descriptor().parent()).orElse(null);
      var rationale = support.rationale(item.path("documents"), dataset);
      var dependencies = new ArrayList<DependencyRef>(rationale.dependencies());
      dependencies.addFirst(onFact(existence(domain, urn)));
      var meaning = LlmSupport.text(item, "meaning");
      if (meaning != null) {
        var value = Json.object().put("text", meaning);
        if (item.path("codes").isObject() && !item.path("codes").isEmpty()) {
          value.set("codes", item.path("codes"));
        }
        candidates.add(candidate(urn, Predicates.MEANING, value, dependencies, rationale));
      }
      var kind = LlmSupport.text(item, "valueKind");
      if (kind != null
          && VALUE_KINDS.contains(kind)
          && !hasLiveFact(catalog, urn, Predicates.VALUE_KIND)) {
        candidates.add(
            candidate(
                urn,
                Predicates.VALUE_KIND,
                Json.object().put("kind", kind),
                dependencies,
                rationale));
      }
      var unit = LlmSupport.text(item, "unit");
      if (unit != null && !hasLiveFact(catalog, urn, Predicates.UNIT)) {
        candidates.add(
            candidate(
                urn, Predicates.UNIT, Json.object().put("unit", unit), dependencies, rationale));
      }
      var nullMeaning = LlmSupport.text(item, "nullMeaning");
      if (nullMeaning != null && !hasLiveFact(catalog, urn, Predicates.NULL_MEANING)) {
        candidates.add(
            candidate(
                urn,
                Predicates.NULL_MEANING,
                Json.object().put("text", nullMeaning),
                dependencies,
                rationale));
      }
    }
    return GeneratorResult.of(candidates);
  }

  private GeneratorResult rows(GeneratorContext context) throws GeneratorException {
    var catalog = context.catalog();
    var domain = catalog.domain();
    var support = new LlmSupport(context);
    var targets = new ArrayList<String>();
    var data = new StringBuilder();
    for (var dataset : catalog.elements(ElementKind.DATASET)) {
      if (dataset.existence() == FactState.CONFIRMED
          && !hasLiveFact(catalog, dataset.urn(), Predicates.ROW_KIND)) {
        targets.add(support.datasetReference(dataset.urn()));
        data.append(support.renderData(dataset.urn()));
      }
    }
    if (targets.isEmpty()) {
      return GeneratorResult.of(List.of());
    }
    var prompt =
        "You are the row meaning inference Generator of a semantic data catalog.\n"
            + "Propose, for each target Dataset, what one row represents.\n\n"
            + "Catalog (confirmed and candidate knowledge):\n"
            + support.renderCatalog()
            + "\n"
            + "Data statistics:\n"
            + data
            + "\n"
            + "Registered documents:\n"
            + support.renderDocuments()
            + "\n"
            + "Target Datasets: "
            + String.join(", ", targets)
            + "\n\n"
            + "Rules:\n"
            + "- concept is the business concept one row is a single object or event of, with a short English identifier name and a meaning.\n"
            + "- rowKind is one of current-state, event, state-per-time, pre-aggregated.\n"
            + "- rowGrain is the combination of concepts and time granularity one row corresponds to, for state-per-time and pre-aggregated rows; otherwise null.\n"
            + "- rowInclusion says which objects or events are included and which are not (for example cancelled orders or test data), or null when nothing indicates it.\n"
            + "- Cite the documents you used by reference (D1, D2, ...) in documents.\n"
            + "- "
            + LlmSupport.LANGUAGE_RULE
            + "\n\n"
            + "Answer with exactly one JSON object:\n"
            + "{\"datasets\": [{\"dataset\": \"source.namespace.table\", \"concept\": {\"name\": \"order\", \"label\": \"...\", \"meaning\": \"...\"},"
            + " \"rowKind\": \"event\", \"rowGrain\": null, \"rowInclusion\": null, \"documents\": [], \"reason\": \"...\"}]}\n";
    var answer = support.ask("row-meaning", prompt);
    var candidates = new ArrayList<Candidate>();
    for (var item : answer.path("datasets")) {
      var dataset = support.dataset(LlmSupport.text(item, "dataset"));
      if (dataset.isEmpty() || !targets.contains(support.datasetReference(dataset.get()))) {
        continue;
      }
      var urn = dataset.get();
      var rationale = support.rationale(item.path("documents"), urn);
      var dependencies = new ArrayList<DependencyRef>(rationale.dependencies());
      dependencies.addFirst(onFact(existence(domain, urn)));
      var concept = item.path("concept");
      var conceptName = LlmSupport.text(concept, "name");
      if (conceptName != null && !hasLiveFact(catalog, urn, Predicates.ROW_CONCEPT)) {
        var conceptUrn = Urns.node(domain, ElementKind.CONCEPT, conceptName);
        var label =
            java.util.Objects.requireNonNullElse(LlmSupport.text(concept, "label"), conceptName);
        candidates.add(
            Candidate.existence(
                ElementDescriptor.node(conceptUrn, ElementKind.CONCEPT, null, label),
                rationale.dependencies(),
                rationale.checks()));
        var conceptMeaning = LlmSupport.text(concept, "meaning");
        if (conceptMeaning != null) {
          var conceptDependencies = new ArrayList<DependencyRef>(rationale.dependencies());
          conceptDependencies.addFirst(onFact(existence(domain, conceptUrn)));
          candidates.add(
              candidate(
                  conceptUrn,
                  Predicates.MEANING,
                  Json.object().put("text", conceptMeaning),
                  conceptDependencies,
                  rationale));
        }
        var rowConcept = new ArrayList<DependencyRef>(dependencies);
        rowConcept.add(onFact(existence(domain, conceptUrn)));
        candidates.add(
            candidate(
                urn,
                Predicates.ROW_CONCEPT,
                Json.object().put("concept", conceptUrn),
                rowConcept,
                rationale));
      }
      var rowKind = LlmSupport.text(item, "rowKind");
      if (rowKind != null && ROW_KINDS.contains(rowKind)) {
        candidates.add(
            candidate(
                urn,
                Predicates.ROW_KIND,
                Json.object().put("kind", rowKind),
                dependencies,
                rationale));
      }
      var grain = LlmSupport.text(item, "rowGrain");
      if (grain != null && !hasLiveFact(catalog, urn, Predicates.ROW_GRAIN)) {
        candidates.add(
            candidate(
                urn,
                Predicates.ROW_GRAIN,
                Json.object().put("text", grain),
                dependencies,
                rationale));
      }
      var inclusion = LlmSupport.text(item, "rowInclusion");
      if (inclusion != null && !hasLiveFact(catalog, urn, Predicates.ROW_INCLUSION)) {
        candidates.add(
            candidate(
                urn,
                Predicates.ROW_INCLUSION,
                Json.object().put("text", inclusion),
                dependencies,
                rationale));
      }
    }
    return GeneratorResult.of(candidates);
  }

  static Candidate candidate(
      String element,
      String predicate,
      JsonNode value,
      List<DependencyRef> dependencies,
      LlmSupport.Rationale rationale) {
    return new Candidate(
        element,
        null,
        predicate,
        Scope.UNRESTRICTED,
        value,
        dependencies,
        rationale.checks(),
        List.of());
  }
}
