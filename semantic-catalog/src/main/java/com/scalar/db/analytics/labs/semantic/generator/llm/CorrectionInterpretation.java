/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.generator.CatalogView;
import com.scalar.db.analytics.labs.semantic.generator.CatalogView.FactView;
import com.scalar.db.analytics.labs.semantic.generator.Generator;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorException;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorResult;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorResult.Candidate;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorSupport;
import com.scalar.db.analytics.labs.semantic.model.Check;
import com.scalar.db.analytics.labs.semantic.model.DependencyRef;
import com.scalar.db.analytics.labs.semantic.model.Json;
import com.scalar.db.analytics.labs.semantic.model.Scope;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ObservationKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Correction interpretation (generators.md 3.3): reads the wording of a correction answer or a user
 * correction and proposes the same element and predicate with a revised value or applicability
 * scope.
 */
public final class CorrectionInterpretation implements Generator {
  private final String identity;

  public CorrectionInterpretation(String modelIdentity) {
    this.identity = modelIdentity;
  }

  @Override
  public String name() {
    return "llm.correction-interpretation";
  }

  @Override
  public String version() {
    return "1;model=" + identity + ";prompt=" + LlmSupport.PROMPT_VERSION;
  }

  @Override
  public Set<String> inputs() {
    return Set.of(CORRECTION_ANSWER, USER_CORRECTION);
  }

  @Override
  public GeneratorResult run(GeneratorContext context) throws GeneratorException {
    var catalog = context.catalog();
    var support = new LlmSupport(context);
    var candidates = new ArrayList<Candidate>();
    var missing = new ArrayList<String>();
    for (var trigger : context.triggers()) {
      var answer = catalog.answer(trigger);
      if (answer.isPresent()) {
        var target = catalog.fact(answer.get().fact()).orElseThrow();
        var basis = List.of(trigger);
        interpret(
            support, catalog, target, answer.get().wording(), null, basis, candidates, missing);
        continue;
      }
      var observation = catalog.observation(Urns.observation(catalog.domain(), trigger));
      if (observation.isPresent() && observation.get().kind() == ObservationKind.USER_CORRECTION) {
        var content = observation.get().content();
        var target = catalog.fact(content.path("fact").asText(""));
        if (target.isEmpty()) {
          missing.add("A user correction names no known Fact: " + observation.get().urn());
          continue;
        }
        var reference = GeneratorSupport.onObservation(observation.get(), "correction", content);
        interpret(
            support,
            catalog,
            target.get(),
            content.path("text").asText(""),
            reference,
            List.of(),
            candidates,
            missing);
      }
    }
    return new GeneratorResult(candidates, List.of(), missing);
  }

  private void interpret(
      LlmSupport support,
      CatalogView catalog,
      FactView target,
      String wording,
      DependencyRef.@Nullable OnObservation correction,
      List<String> basis,
      List<Candidate> out,
      List<String> missing)
      throws GeneratorException {
    var element = catalog.element(target.element()).orElseThrow();
    var prompt =
        "You are the correction interpretation Generator of a semantic data catalog.\n"
            + "A deciding party or a user corrected one statement. Read the correction and state the revised statement.\n\n"
            + "Catalog:\n"
            + support.renderCatalog()
            + "\n"
            + "Corrected statement: element "
            + element.descriptor().label()
            + " ("
            + element.kind().wireName()
            + "), predicate "
            + target.predicate()
            + ", value "
            + Json.write(target.value())
            + (target.scope().unrestricted() ? "" : ", scope " + target.scope().description())
            + "\n"
            + "Correction: "
            + wording
            + "\n\n"
            + "Rules:\n"
            + "- value has the same JSON shape as the corrected value. scope is null when the statement holds everywhere, or a description of where it holds.\n"
            + "- When the correction gives neither a value nor a scope you can read, answer {\"value\": null, \"scope\": null}.\n"
            + "- "
            + LlmSupport.LANGUAGE_RULE
            + "\n\n"
            + "Answer with exactly one JSON object: {\"value\": {...}, \"scope\": null, \"reason\": \"...\"}\n";
    var answer = support.ask("correction", prompt);
    var value = answer.path("value");
    var scopeText = LlmSupport.text(answer, "scope");
    if (!value.isObject() && scopeText == null) {
      missing.add("The correction could not be read: " + wording);
      return;
    }
    JsonNode revised = value.isObject() ? value : target.value();
    var scope = scopeText == null ? target.scope() : new Scope(scopeText, List.of());
    // A candidate made from a rejected Fact does not depend on it, or the cascade would reject it;
    // it carries over what the rejected Fact depended on. A correction of a confirmed Fact depends
    // on it, so confirming the new candidate replaces it.
    var dependencies = new ArrayList<DependencyRef>();
    if (target.state() == FactState.REJECTED || target.state() == FactState.CANDIDATE) {
      target.dependsOn().forEach(fact -> dependencies.add(GeneratorSupport.onFact(fact)));
    } else {
      dependencies.add(GeneratorSupport.onFact(target.urn()));
      target.dependsOn().forEach(fact -> dependencies.add(GeneratorSupport.onFact(fact)));
    }
    if (correction != null) {
      dependencies.add(correction);
    }
    out.add(
        new Candidate(
            target.element(),
            null,
            target.predicate(),
            scope,
            revised,
            dependencies,
            List.of(GeneratorSupport.supported(Check.Method.INFERENCE, correction)),
            basis));
  }
}
