/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator.llm;

import static com.scalar.db.analytics.labs.semantic.generator.GeneratorSupport.quote;
import static com.scalar.db.analytics.labs.semantic.generator.GeneratorSupport.table;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.generator.CatalogView;
import com.scalar.db.analytics.labs.semantic.generator.CatalogView.ObservationView;
import com.scalar.db.analytics.labs.semantic.generator.DataAccess.DataAccessException;
import com.scalar.db.analytics.labs.semantic.generator.DataAccess.DataObservation;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorException;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorSupport;
import com.scalar.db.analytics.labs.semantic.generator.LanguageModel.LanguageModelException;
import com.scalar.db.analytics.labs.semantic.model.Check;
import com.scalar.db.analytics.labs.semantic.model.DependencyRef;
import com.scalar.db.analytics.labs.semantic.model.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Json;
import com.scalar.db.analytics.labs.semantic.model.Predicates;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ObservationKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What the LLM Generators share: rendering the catalog, documents, data statistics, and question-
 * time observations into a prompt, mapping the model's references back to URNs, and reading its
 * JSON answer.
 *
 * <p>References in prompts are readable: a Dataset is its resource identifier {@code
 * source.namespace.table}, a Field is {@code source.namespace.table.column}, and a document is
 * {@code D1}, {@code D2}, and so on.
 */
final class LlmSupport {
  static final String PROMPT_VERSION = "1";
  private static final int SAMPLE_ROWS = 5;
  private static final int PROFILED_VALUES = 8;
  private static final Pattern FENCE = Pattern.compile("```(?:json)?\\s*(.*?)```", Pattern.DOTALL);

  static final String LANGUAGE_RULE =
      "Write human-readable text (meanings, definitions, descriptions, reasons) in the language of"
          + " the registered documents, or in English when there are none. Keep identifiers, SQL,"
          + " and code values exactly as they are.";

  private final GeneratorContext context;
  private final CatalogView catalog;
  final Map<String, ObservationView> documents = new LinkedHashMap<>();
  final Map<String, DataObservation> samples = new LinkedHashMap<>();

  LlmSupport(GeneratorContext context) {
    this.context = context;
    this.catalog = context.catalog();
    var index = 1;
    for (var kind : List.of(ObservationKind.DOCUMENT, ObservationKind.RULING)) {
      for (var observation : catalog.latestObservations(kind)) {
        documents.put("D" + index++, observation);
      }
    }
  }

  static String version(GeneratorContext context, String base) {
    return base + ";model=" + context.model().identity() + ";prompt=" + PROMPT_VERSION;
  }

  // References

  String datasetReference(String dataset) {
    return GeneratorSupport.datasetResource(catalog, dataset);
  }

  String fieldReference(String field) {
    var view = catalog.element(field).orElseThrow();
    return datasetReference(java.util.Objects.requireNonNull(view.descriptor().parent()))
        + "."
        + view.descriptor().label();
  }

  /** The Dataset URN a reference names, if the Dataset is confirmed. */
  Optional<String> dataset(@Nullable String reference) {
    if (reference == null) {
      return Optional.empty();
    }
    var urn = Urns.dataset(catalog.domain(), reference);
    return GeneratorSupport.confirmed(catalog, urn) ? Optional.of(urn) : Optional.empty();
  }

  /** The Field URN a reference names, if the Field is confirmed. */
  Optional<String> field(@Nullable String reference) {
    if (reference == null) {
      return Optional.empty();
    }
    var dot = reference.lastIndexOf('.');
    if (dot < 0) {
      return Optional.empty();
    }
    var urn =
        Urns.field(
            Urns.dataset(catalog.domain(), reference.substring(0, dot)),
            reference.substring(dot + 1));
    return GeneratorSupport.confirmed(catalog, urn) ? Optional.of(urn) : Optional.empty();
  }

  /** The Field URN for a column of a Dataset, if the Field is confirmed. */
  Optional<String> column(String dataset, @Nullable String column) {
    if (column == null || column.isBlank()) {
      return Optional.empty();
    }
    var urn = Urns.field(dataset, column);
    return GeneratorSupport.confirmed(catalog, urn) ? Optional.of(urn) : Optional.empty();
  }

  // Rendering

  /** Every confirmed Dataset with its confirmed Fields and their selected knowledge. */
  String renderCatalog() {
    var text = new StringBuilder();
    for (var dataset : catalog.elements(ElementKind.DATASET)) {
      if (dataset.existence() != FactState.CONFIRMED) {
        continue;
      }
      text.append("Dataset ").append(datasetReference(dataset.urn())).append('\n');
      appendKnowledge(text, dataset.urn(), "  ");
      var schema =
          catalog.latestObservation(
              Urns.tableResource(catalog.domain(), datasetReference(dataset.urn())));
      var types = new LinkedHashMap<String, JsonNode>();
      schema.ifPresent(
          observation ->
              observation
                  .content()
                  .path("columns")
                  .forEach(column -> types.put(column.path("name").asText(), column)));
      for (var field : GeneratorSupport.confirmedFields(catalog, dataset.urn())) {
        var column = types.get(field.descriptor().label());
        text.append("  Field ").append(fieldReference(field.urn()));
        if (column != null) {
          text.append(" (")
              .append(column.path("sourceType").asText())
              .append(column.path("nullable").asBoolean(true) ? ", nullable" : ", not null")
              .append(')');
        }
        text.append('\n');
        appendKnowledge(text, field.urn(), "    ");
      }
      schema.ifPresent(
          observation -> {
            var key = observation.content().path("primaryKey");
            if (key.isObject()) {
              text.append("  Primary key: ").append(key.path("columns")).append('\n');
            }
            for (var foreignKey : observation.content().path("foreignKeys")) {
              text.append("  Foreign key: ")
                  .append(foreignKey.path("columns"))
                  .append(" -> ")
                  .append(foreignKey.path("references").path("resource").asText())
                  .append(foreignKey.path("references").path("columns"))
                  .append('\n');
            }
          });
    }
    for (var kind :
        List.of(
            ElementKind.CONCEPT,
            ElementKind.MEASURE,
            ElementKind.BUSINESS_TERM,
            ElementKind.JOIN,
            ElementKind.IDENTIFIER_CORRESPONDENCE)) {
      for (var element : catalog.elements(kind)) {
        if (element.existence() == FactState.REJECTED || element.existence() == FactState.RETIRED) {
          continue;
        }
        text.append(kind.wireName())
            .append(' ')
            .append(element.descriptor().label())
            .append(element.existence() == FactState.CONFIRMED ? "" : " (candidate)")
            .append('\n');
        appendKnowledge(text, element.urn(), "  ");
      }
    }
    return text.toString();
  }

  private void appendKnowledge(StringBuilder text, String element, String indent) {
    for (var fact : catalog.facts(element)) {
      if (fact.predicate().equals(Predicates.EXISTS)
          || fact.predicate().equals(Predicates.TECHNICAL_REFERENCE)) {
        continue;
      }
      var state =
          switch (fact.state()) {
            case CONFIRMED -> "confirmed";
            case CANDIDATE -> "candidate";
            case REJECTED -> "rejected";
            case RETIRED -> "retired";
          };
      text.append(indent)
          .append(fact.predicate())
          .append(" [")
          .append(state)
          .append("]: ")
          .append(Json.write(fact.value()));
      if (!fact.scope().unrestricted()) {
        text.append(" scope: ").append(fact.scope().description());
      }
      for (var answer : catalog.answers(fact.urn())) {
        text.append(" (answer ")
            .append(Json.tree(answer.answerType()).asText())
            .append(": ")
            .append(answer.wording())
            .append(')');
      }
      text.append('\n');
    }
  }

  String renderDocuments() {
    if (documents.isEmpty()) {
      return "(none)\n";
    }
    var text = new StringBuilder();
    documents.forEach(
        (reference, observation) -> {
          text.append("[")
              .append(reference)
              .append("] ")
              .append(observation.content().path("title").asText(""))
              .append('\n');
          text.append(observation.content().path("text").asText(Json.write(observation.content())))
              .append("\n\n");
        });
    return text.toString();
  }

  /** Missing knowledge and user corrections reported at question time, if any triggered the run. */
  String renderQuestionTimeInput() {
    var text = new StringBuilder();
    for (var kind : List.of(ObservationKind.MISSING_KNOWLEDGE, ObservationKind.USER_CORRECTION)) {
      for (var observation : catalog.latestObservations(kind)) {
        text.append(Json.tree(kind).asText())
            .append(" (question ")
            .append(observation.question())
            .append("): ")
            .append(Json.write(observation.content()))
            .append('\n');
      }
    }
    return text.isEmpty() ? "(none)\n" : text.toString();
  }

  /**
   * Sample rows and a value profile of a Dataset's low-cardinality columns, read through the
   * application's SQL execution and recorded as data observations.
   */
  String renderData(String dataset) {
    var resource = datasetReference(dataset);
    var text = new StringBuilder();
    try {
      var sample =
          context
              .data()
              .query(
                  "sample",
                  Json.object().put("dataset", dataset),
                  "SELECT * FROM " + table(resource) + " LIMIT " + SAMPLE_ROWS,
                  List.of(resource));
      samples.put(dataset, sample);
      text.append("Sample rows of ").append(resource).append(":\n");
      for (var row : sample.rows()) {
        var object = Json.object();
        for (var i = 0; i < sample.columns().size(); i++) {
          object.set(sample.columns().get(i), row.get(i));
        }
        text.append("  ").append(Json.write(object)).append('\n');
      }
      for (var field : GeneratorSupport.confirmedFields(catalog, dataset)) {
        var column = field.descriptor().label();
        var profile =
            context
                .data()
                .query(
                    "profile",
                    Json.object().put("field", field.urn()),
                    "SELECT CAST("
                        + quote(column)
                        + " AS VARCHAR) AS v, count(*) AS n FROM "
                        + table(resource)
                        + " GROUP BY 1 ORDER BY 2 DESC, 1 LIMIT "
                        + (PROFILED_VALUES + 1),
                    List.of(resource));
        if (profile.rows().size() <= PROFILED_VALUES) {
          text.append("  Values of ").append(column).append(": ");
          var parts = new ArrayList<String>();
          for (var row : profile.rows()) {
            parts.add(
                (row.get(0).isNull() ? "NULL" : row.get(0).asText()) + " x" + row.get(1).asText());
          }
          text.append(String.join(", ", parts)).append('\n');
        }
      }
    } catch (DataAccessException e) {
      text.append("(data of ")
          .append(resource)
          .append(" could not be read: ")
          .append(e.getMessage())
          .append(")\n");
    }
    return text.toString();
  }

  // Rationale

  /** The rationale of a candidate: cited documents, or else the data read for the Dataset. */
  Rationale rationale(JsonNode cited, @Nullable String dataset) {
    var dependencies = new ArrayList<DependencyRef>();
    var checks = new ArrayList<Check>();
    for (var reference : cited) {
      var document = documents.get(reference.asText());
      if (document != null) {
        var ref = GeneratorSupport.onObservation(document, "document", document.content());
        dependencies.add(ref);
        checks.add(GeneratorSupport.supported(Check.Method.DOCUMENT, ref));
      }
    }
    var sample = dataset == null ? null : samples.get(dataset);
    DependencyRef sampleRef =
        sample == null
            ? null
            : new DependencyRef.OnObservation(
                sample.inputResource(), sample.inputObservation(), "sample", sample.digest());
    if (sampleRef != null) {
      dependencies.add(sampleRef);
    }
    if (checks.isEmpty()) {
      checks.add(GeneratorSupport.supported(Check.Method.INFERENCE, sampleRef));
    }
    return new Rationale(dependencies, checks);
  }

  record Rationale(List<DependencyRef> dependencies, List<Check> checks) {
    /** The same checks covering other validation items. */
    List<Check> covering(List<String> items) {
      return checks.stream()
          .map(check -> new Check(check.method(), items, check.result(), check.rationale()))
          .toList();
    }
  }

  // Asking the model

  JsonNode ask(String purpose, String prompt) throws GeneratorException {
    var current = prompt;
    for (var attempt = 0; attempt < 2; attempt++) {
      String answer;
      try {
        answer = context.model().complete(purpose, current);
      } catch (LanguageModelException e) {
        throw new GeneratorException("The language model failed: " + e.getMessage(), e);
      }
      var parsed = firstJsonObject(answer);
      if (parsed != null) {
        return parsed;
      }
      current =
          prompt
              + "\n\nYour previous answer could not be used. Answer again with exactly one JSON object in the required format, with no prose and no code fences.";
    }
    throw new GeneratorException("The language model did not answer with a JSON object.");
  }

  static @Nullable JsonNode firstJsonObject(String text) {
    var chunks = new ArrayList<String>();
    var fences = FENCE.matcher(text);
    while (fences.find()) {
      chunks.add(fences.group(1));
    }
    chunks.add(text);
    for (var chunk : chunks) {
      for (var start = chunk.indexOf('{'); start >= 0; start = chunk.indexOf('{', start + 1)) {
        try {
          var parser = Json.MAPPER.getFactory().createParser(chunk.substring(start));
          JsonNode node = Json.MAPPER.readTree(parser);
          if (node != null && node.isObject()) {
            return node;
          }
        } catch (java.io.IOException ignored) {
          // Try the next opening brace.
        }
      }
    }
    return null;
  }

  static @Nullable String text(JsonNode node, String field) {
    var value = node.path(field);
    if (!value.isTextual() || value.asText().isBlank()) {
      return null;
    }
    return value.asText().strip();
  }
}
