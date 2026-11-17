/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.translation;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.InputKind;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.FactAddressRow;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The text of a log record that a translation says in another language (see {@code
 * docs/semantic-catalog/languages.md}, "Translations"): its descriptions, written in the domain's
 * recording language. It is the one definition of which parts of a record are translated.
 *
 * <p>Each text is keyed by where it is in the record, such as {@code value}, {@code value.meaning},
 * or {@code reviewMaterial.evidenceFor[0]}, so a translation can be shown beside the text it says.
 * Business-term names, closed values, references, expression values, and observed documents and
 * physical schemas are not translatable text: a name in another language is a Fact of its own, and
 * the rest is not a description in the recording language.
 */
public final class TranslatableText {
  /** The predicates whose value is a description in text. */
  private static final Set<String> TEXT_VALUED =
      Set.of(
          Vocabulary.MEANING,
          Vocabulary.UNIT,
          Vocabulary.NULL_MEANING,
          Vocabulary.ROW_GRAIN,
          Vocabulary.ROW_INCLUSION,
          Vocabulary.CALENDAR_RULE,
          Vocabulary.CLASSIFICATION_RULE,
          Vocabulary.BUSINESS_DEFINITION);

  /** The closed parts of review material, which are not text. */
  private static final Set<String> CLOSED_REVIEW_MATERIAL = Set.of("suggestedAnswer");

  private TranslatableText() {}

  /** What the text of a record depends on beyond the record itself. */
  public interface Context {
    /** The Fact Address a Fact's value is at. */
    Optional<FactAddressRow> address(String factAddress);

    /** The kind of an Input Resource an observation observes. */
    Optional<InputKind> inputKind(String inputResource);
  }

  /**
   * The translatable text of a record stored in the log, in the order it appears in the record;
   * empty when it has none.
   */
  public static Map<String, String> of(LogRecord record, Context context) {
    var texts = new LinkedHashMap<String, String>();
    switch (record) {
      case LogRecord.Fact fact ->
          context
              .address(fact.address().urnValue())
              .ifPresent(address -> factValue(address, fact.value(), texts));
      case LogRecord.FactAddress address -> {
        put(texts, "scope", address.scope().description());
        put(texts, "predicateDefinition", address.predicateDefinition());
      }
      case LogRecord.EvaluatePolicy evaluation -> {
        put(texts, "reason", evaluation.reason());
        if (evaluation.evaluator() instanceof LogRecord.Evaluator.Model model
            && model.reviewMaterial() != null) {
          model
              .reviewMaterial()
              .properties()
              .forEach(
                  entry -> {
                    if (!CLOSED_REVIEW_MATERIAL.contains(entry.getKey())) {
                      leaves(texts, "reviewMaterial." + entry.getKey(), entry.getValue());
                    }
                  });
        }
      }
      case LogRecord.PolicyVersion version -> put(texts, "reason", version.reason());
      case LogRecord.Answer answer -> put(texts, "wording", answer.wording());
      case LogRecord.ResolveConflict resolution -> put(texts, "wording", resolution.wording());
      case LogRecord.InputObservation observation -> {
        if (context.inputKind(observation.inputResource().urnValue()).orElse(null)
            == InputKind.USAGE_FEEDBACK) {
          put(texts, "content", observation.content().path("content").textValue());
          put(texts, "contextSummary", observation.content().path("contextSummary").textValue());
        }
      }
      default -> {}
    }
    return texts;
  }

  private static void factValue(FactAddressRow address, JsonNode value, Map<String, String> texts) {
    if (address.predicateDefinition() != null || TEXT_VALUED.contains(address.predicate())) {
      put(texts, "value", value.textValue());
    } else if (address.predicate().equals(Vocabulary.TIME_MEANING)) {
      put(texts, "value.meaning", value.path("meaning").textValue());
    }
  }

  /** Every text of a JSON value, keyed from its key: an array's items by their index. */
  private static void leaves(Map<String, String> texts, String key, JsonNode value) {
    if (value.isTextual()) {
      put(texts, key, value.textValue());
    } else if (value.isArray()) {
      for (var i = 0; i < value.size(); i++) {
        leaves(texts, key + "[" + i + "]", value.get(i));
      }
    } else if (value.isObject()) {
      value
          .properties()
          .forEach(entry -> leaves(texts, key + "." + entry.getKey(), entry.getValue()));
    }
  }

  private static void put(Map<String, String> texts, String key, @Nullable String text) {
    if (text != null && !text.isBlank()) {
      texts.put(key, text);
    }
  }
}
