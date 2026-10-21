/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.generator.CatalogView.ElementView;
import com.scalar.db.analytics.labs.semantic.generator.CatalogView.FactView;
import com.scalar.db.analytics.labs.semantic.generator.CatalogView.ObservationView;
import com.scalar.db.analytics.labs.semantic.model.Check;
import com.scalar.db.analytics.labs.semantic.model.DependencyRef;
import com.scalar.db.analytics.labs.semantic.model.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Facts;
import com.scalar.db.analytics.labs.semantic.model.Json;
import com.scalar.db.analytics.labs.semantic.model.Predicates;
import com.scalar.db.analytics.labs.semantic.model.Scope;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ObservationKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Helpers shared by the first Generators. */
public final class GeneratorSupport {
  private GeneratorSupport() {}

  public static final JsonNode EXISTS = Json.object().put("exists", true);

  /** The existence Fact URN of an element. */
  public static String existence(String domain, String element) {
    return Facts.fact(domain, element, Predicates.EXISTS, Scope.UNRESTRICTED, EXISTS);
  }

  public static DependencyRef.OnFact onFact(String fact) {
    return new DependencyRef.OnFact(fact);
  }

  /** A dependency on the part of an observation a selector points to. */
  public static DependencyRef.OnObservation onObservation(
      ObservationView observation, String selector, Object selected) {
    return new DependencyRef.OnObservation(
        observation.inputResource(), observation.urn(), selector, Json.digest(selected));
  }

  public static Check check(
      Check.Method method,
      List<String> items,
      Check.Result result,
      @Nullable DependencyRef rationale) {
    return new Check(method, items, result, rationale);
  }

  public static Check supported(Check.Method method, @Nullable DependencyRef rationale) {
    return check(method, List.of(Predicates.VALUE_ITEM), Check.Result.SUPPORTED, rationale);
  }

  /** The physical schema observations that triggered a task. */
  public static List<ObservationView> triggeringSchemas(GeneratorContext context) {
    var result = new ArrayList<ObservationView>();
    var domain = context.catalog().domain();
    for (var trigger : context.triggers()) {
      context
          .catalog()
          .observation(Urns.observation(domain, trigger))
          .filter(observation -> observation.kind() == ObservationKind.PHYSICAL_SCHEMA)
          .ifPresent(result::add);
    }
    return result;
  }

  /** The latest physical schema observation of every table. */
  public static List<ObservationView> schemas(CatalogView catalog) {
    return catalog.latestObservations(ObservationKind.PHYSICAL_SCHEMA);
  }

  public static String resourceIdentifier(ObservationView schema) {
    return schema.content().path("resource").asText();
  }

  public static String datasetUrn(String domain, String resourceIdentifier) {
    return Urns.dataset(domain, resourceIdentifier);
  }

  /** Whether an element's existence Fact is confirmed. */
  public static boolean confirmed(CatalogView catalog, String element) {
    return catalog
        .element(element)
        .map(view -> view.existence() == FactState.CONFIRMED)
        .orElse(false);
  }

  /** The Fields of a Dataset whose existence is confirmed. */
  public static List<ElementView> confirmedFields(CatalogView catalog, String dataset) {
    return catalog.children(dataset).stream()
        .filter(
            field -> field.kind() == ElementKind.FIELD && field.existence() == FactState.CONFIRMED)
        .toList();
  }

  /** Whether an element already has a candidate or confirmed Fact for a predicate, in any scope. */
  public static boolean hasLiveFact(CatalogView catalog, String element, String predicate) {
    return catalog.facts(element).stream()
        .anyMatch(
            fact ->
                fact.predicate().equals(predicate)
                    && (fact.state() == FactState.CANDIDATE
                        || fact.state() == FactState.CONFIRMED));
  }

  /** The selected Fact's value for an element's unrestricted-scope predicate. */
  public static Optional<JsonNode> selectedValue(
      CatalogView catalog, String element, String predicate) {
    return catalog.selected(element, predicate).map(FactView::value);
  }

  /** Quotes a column name for DuckDB SQL. */
  public static String quote(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  /** The SQL name of a table from its resource identifier, {@code source.namespace.table}. */
  public static String table(String resourceIdentifier) {
    var parts = resourceIdentifier.split("\\.", -1);
    var quoted = new ArrayList<String>();
    for (var part : parts) {
      quoted.add(quote(part));
    }
    return String.join(".", quoted);
  }

  /** The column name of a Field, which is its label. */
  public static String column(CatalogView catalog, String field) {
    return catalog.element(field).map(view -> view.descriptor().label()).orElseThrow();
  }

  /** The resource identifier of a Dataset, which is its label. */
  public static String datasetResource(CatalogView catalog, String dataset) {
    return catalog.element(dataset).map(view -> view.descriptor().label()).orElseThrow();
  }
}
