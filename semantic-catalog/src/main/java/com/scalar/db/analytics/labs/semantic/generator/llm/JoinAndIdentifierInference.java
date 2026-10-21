/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator.llm;

import static com.scalar.db.analytics.labs.semantic.generator.GeneratorSupport.existence;
import static com.scalar.db.analytics.labs.semantic.generator.GeneratorSupport.onFact;

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
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Join and identifier correspondence inference (generators.md 3.3): joins no foreign key declares,
 * and identifier correspondences across data sources with their business definition.
 */
public final class JoinAndIdentifierInference implements Generator {
  private static final Set<String> NORMALIZATIONS = Set.of("none", "lower-trim", "text");

  private final String identity;

  public JoinAndIdentifierInference(String modelIdentity) {
    this.identity = modelIdentity;
  }

  @Override
  public String name() {
    return "llm.join-and-identifier";
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
        Generator.confirmed(ElementKind.FIELD, Predicates.MEANING),
        Generator.confirmed(ElementKind.FIELD, Predicates.REPRESENTED_CONCEPT));
  }

  @Override
  public GeneratorResult run(GeneratorContext context) throws GeneratorException {
    var catalog = context.catalog();
    var domain = catalog.domain();
    var support = new LlmSupport(context);
    var datasets =
        catalog.elements(ElementKind.DATASET).stream()
            .filter(dataset -> dataset.existence() == FactState.CONFIRMED)
            .count();
    if (datasets < 2) {
      return GeneratorResult.of(List.of());
    }
    var prompt =
        "You are the join and identifier correspondence inference Generator of a semantic data catalog.\n"
            + "Propose joins between Datasets that no foreign key declares, and identifier correspondences between Fields of different data sources that identify the same business object.\n\n"
            + "Catalog (confirmed and candidate knowledge, with declared foreign keys):\n"
            + support.renderCatalog()
            + "\n"
            + "Registered documents:\n"
            + support.renderDocuments()
            + "\n"
            + "Missing knowledge and corrections reported at question time:\n"
            + support.renderQuestionTimeInput()
            + "\n"
            + "Rules:\n"
            + "- A join goes from the referencing Dataset to the referenced one; on lists [fromColumn, toColumn] pairs.\n"
            + "- An identifier correspondence pairs two Fields whose values identify the same object. normalization is how values compare: none, lower-trim (case-insensitive after trimming), or text.\n"
            + "- definition states, in business terms, why the two identify the same object; cite documents (D1, D2, ...).\n"
            + "- Do not propose joins that a foreign key already declares, or anything already in the catalog.\n"
            + "- "
            + LlmSupport.LANGUAGE_RULE
            + "\n\n"
            + "Answer with exactly one JSON object:\n"
            + "{\"joins\": [{\"from\": \"source.namespace.table\", \"to\": \"source.namespace.table\", \"on\": [[\"from_column\", \"to_column\"]], \"documents\": [], \"reason\": \"...\"}],"
            + " \"identifiers\": [{\"from\": \"source.namespace.table.column\", \"to\": \"source.namespace.table.column\", \"normalization\": \"none\", \"definition\": \"...\", \"documents\": [], \"reason\": \"...\"}]}\n";
    var answer = support.ask("join-and-identifier", prompt);
    var candidates = new ArrayList<Candidate>();
    for (var item : answer.path("joins")) {
      var from = support.dataset(LlmSupport.text(item, "from"));
      var to = support.dataset(LlmSupport.text(item, "to"));
      if (from.isEmpty()
          || to.isEmpty()
          || !item.path("on").isArray()
          || item.path("on").isEmpty()) {
        continue;
      }
      var pairs = Json.array();
      var dependencies = new ArrayList<DependencyRef>();
      var valid = true;
      for (var pair : item.path("on")) {
        var fromField = support.column(from.get(), pair.path(0).asText(null));
        var toField = support.column(to.get(), pair.path(1).asText(null));
        if (fromField.isEmpty() || toField.isEmpty()) {
          valid = false;
          break;
        }
        pairs.add(Json.array().add(fromField.get()).add(toField.get()));
        dependencies.add(onFact(existence(domain, fromField.get())));
        dependencies.add(onFact(existence(domain, toField.get())));
      }
      if (!valid) {
        continue;
      }
      var identifying = Json.object().set("on", pairs);
      var descriptor =
          ElementDescriptor.edge(
              domain,
              ElementKind.JOIN,
              from.get(),
              to.get(),
              true,
              identifying,
              support.datasetReference(from.get()) + " -> " + support.datasetReference(to.get()));
      if (catalog.element(descriptor.urn()).isPresent()) {
        continue;
      }
      var rationale = support.rationale(item.path("documents"), null);
      dependencies.addAll(rationale.dependencies());
      var items = new ArrayList<String>();
      pairs.forEach(pair -> items.add(Predicates.joinPairItem(pair)));
      candidates.add(Candidate.existence(descriptor, dependencies, rationale.covering(items)));
    }
    for (var item : answer.path("identifiers")) {
      var from = support.field(LlmSupport.text(item, "from"));
      var to = support.field(LlmSupport.text(item, "to"));
      if (from.isEmpty() || to.isEmpty() || from.get().equals(to.get())) {
        continue;
      }
      var normalization =
          Objects.requireNonNullElse(LlmSupport.text(item, "normalization"), "none");
      if (!NORMALIZATIONS.contains(normalization)) {
        normalization = "none";
      }
      var identifying = Json.object().put("match", "direct").put("normalization", normalization);
      var descriptor =
          ElementDescriptor.edge(
              domain,
              ElementKind.IDENTIFIER_CORRESPONDENCE,
              from.get(),
              to.get(),
              false,
              identifying,
              support.fieldReference(from.get()) + " = " + support.fieldReference(to.get()));
      if (GeneratorSupport.hasLiveFact(catalog, descriptor.urn(), Predicates.EXISTS)) {
        continue;
      }
      var rationale = support.rationale(item.path("documents"), null);
      var dependencies = new ArrayList<DependencyRef>();
      dependencies.add(onFact(existence(domain, from.get())));
      dependencies.add(onFact(existence(domain, to.get())));
      dependencies.addAll(rationale.dependencies());
      candidates.add(Candidate.existence(descriptor, dependencies, rationale.checks()));
      var definition = LlmSupport.text(item, "definition");
      if (definition != null) {
        var definitionDependencies = new ArrayList<DependencyRef>(rationale.dependencies());
        definitionDependencies.addFirst(onFact(existence(domain, descriptor.urn())));
        candidates.add(
            MeaningInference.candidate(
                descriptor.urn(),
                Predicates.BUSINESS_DEFINITION,
                Json.object().put("text", definition),
                definitionDependencies,
                rationale));
      }
    }
    return GeneratorResult.of(candidates);
  }
}
