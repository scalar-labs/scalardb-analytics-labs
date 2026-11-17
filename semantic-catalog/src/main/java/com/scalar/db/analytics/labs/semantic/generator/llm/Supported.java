/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.scalar.db.analytics.labs.semantic.generator.DataObservations;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext;
import com.scalar.db.analytics.labs.semantic.generator.ModelGenerator.Ask;
import com.scalar.db.analytics.labs.semantic.llm.LanguageModelException;
import com.scalar.db.analytics.labs.semantic.model.Check;
import com.scalar.db.analytics.labs.semantic.model.Dependency;
import com.scalar.db.analytics.labs.semantic.model.Document;
import com.scalar.db.analytics.labs.semantic.model.LogRecordJson;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.CheckResult;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ValidationMethod;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * A value a model gave with what supports it, and the check it becomes (see {@code
 * docs/semantic-catalog/generators.md}, "Meaning inference" and "Input passed to external models"):
 * a {@code document} check when the value cites a document the call read and the quotation is in
 * it, an {@code inference} check with the basis the model stated otherwise.
 */
final class Supported {
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

  private Supported() {}

  /** A value the model gave, with the basis it stated and the document it cited. */
  record Given(JsonNode value, String basis, @Nullable Citation citation) {}

  /** A document a model cites for a value, by its Input Resource, with the text it quotes. */
  record Citation(String document, String quotation) {}

  /**
   * Reads one supported value, {@code {"value": ..., "basis": ..., "citation": {"document": ...,
   * "quotation": ...}}} with the citation optional.
   *
   * @param what what the value is of, for the failure message
   * @param unreadable makes the failure of an answer not in the form asked for
   * @return null when the item or its value is null, which means the model gave none
   */
  static @Nullable Given given(
      String what, JsonNode item, Function<String, LanguageModelException> unreadable) {
    if (item.isMissingNode() || item.isNull()) {
      return null;
    }
    var basis = item.path("basis");
    if (!item.isObject() || !item.has("value") || !basis.isTextual() || basis.asText().isBlank()) {
      throw unreadable.apply("the value of " + what + " has no value or no basis");
    }
    if (item.get("value").isNull()) {
      return null;
    }
    return new Given(item.get("value"), basis.asText(), citation(what, item, unreadable));
  }

  /**
   * Reads the support stated for a claim that has no value of its own, such as a correspondence's
   * existence: {@code {"basis": ..., "citation": {"document": ..., "quotation": ...}}} with the
   * citation optional.
   *
   * @return null when the item is absent or null, which means the model stated no support
   */
  static @Nullable Given stated(
      String what, JsonNode item, Function<String, LanguageModelException> unreadable) {
    if (item.isMissingNode() || item.isNull()) {
      return null;
    }
    var basis = item.path("basis");
    if (!item.isObject() || !basis.isTextual() || basis.asText().isBlank()) {
      throw unreadable.apply("the support of " + what + " has no basis");
    }
    return new Given(JSON.nullNode(), basis.asText(), citation(what, item, unreadable));
  }

  private static @Nullable Citation citation(
      String what, JsonNode item, Function<String, LanguageModelException> unreadable) {
    var citation = item.path("citation");
    if (citation.isMissingNode() || citation.isNull()) {
      return null;
    }
    var document = citation.path("document");
    var quotation = citation.path("quotation");
    if (!document.isTextual() || !quotation.isTextual()) {
      throw unreadable.apply("the citation of " + what + " has no document or no quotation");
    }
    return new Citation(document.asText(), quotation.asText());
  }

  /**
   * The check of a value: {@code document} quoting the cited observation when the quotation was
   * found, {@code inference} with the basis otherwise.
   *
   * @param quoted the Input Observation the quotation was found in, as {@link
   *     CitedDocuments#verified} gives it
   * @param statistics the data observation an inference read, if any
   */
  static Check check(Given given, @Nullable String quoted, @Nullable Ref statistics) {
    return check(given, quoted, statistics, JSON.textNode(Vocabulary.VALUE_ITEM));
  }

  /**
   * The check of a claim over some validation items, such as the pairs and normalization steps of a
   * correspondence's existence; otherwise as {@link #check(Given, String, Ref)}.
   */
  static Check check(
      Given given, @Nullable String quoted, @Nullable Ref statistics, JsonNode items) {
    if (quoted == null || given.citation() == null) {
      return new Check(
          ValidationMethod.INFERENCE,
          items,
          CheckResult.SUPPORTED,
          null,
          statistics,
          DataObservations.stored(JSON.objectNode().put("basis", given.basis())));
    }
    return new Check(
        ValidationMethod.DOCUMENT,
        items,
        CheckResult.SUPPORTED,
        null,
        Ref.urn(quoted),
        DataObservations.stored(
            JSON.objectNode()
                .put("quotation", given.citation().quotation())
                .put("basis", given.basis())));
  }

  /**
   * The documents a run's answers cite, each read once in the recording transaction and kept in the
   * form a quotation is checked against.
   */
  static final class CitedDocuments {
    private final GeneratorContext context;
    private final Map<String, String> texts = new HashMap<>();

    CitedDocuments(GeneratorContext context) {
      this.context = context;
    }

    /**
     * The Input Observation a citation quotes, when the call read the cited document and the
     * quotation is in that observation's text; null otherwise, so the value is only an inference.
     */
    @Nullable String verified(Ask ask, @Nullable Citation citation) {
      if (citation == null) {
        return null;
      }
      var observation = passedObservation(ask, citation.document());
      if (observation == null) {
        return null;
      }
      var text =
          texts.computeIfAbsent(
              observation,
              urn ->
                  Quotations.collapsed(
                      context
                          .observationContent(urn)
                          .map(content -> LogRecordJson.fromStored(content, Document.class).text())
                          .orElse("")));
      return Quotations.found(citation.quotation(), text) ? observation : null;
    }

    /**
     * The observation of a document the call read: a document among the call's input whose
     * observation the targets' premises name.
     */
    private static @Nullable String passedObservation(Ask ask, String document) {
      var passed = false;
      for (var section : ask.input().path("documents")) {
        if (section.path("document").asText().equals(document)) {
          passed = true;
          break;
        }
      }
      if (!passed) {
        return null;
      }
      for (var target : ask.targets()) {
        for (var premise : target.premises()) {
          if (premise instanceof Dependency.OnInput input
              && input.inputResource().urnValue().equals(document)) {
            return input.observation().urnValue();
          }
        }
      }
      return null;
    }
  }
}
