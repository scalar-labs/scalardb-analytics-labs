/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.translation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.scalar.db.analytics.labs.semantic.model.Document;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Scope;
import com.scalar.db.analytics.labs.semantic.model.UsageFeedback;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.AnswerType;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.InputKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.PolicyResult;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.SessionPurpose;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Transition;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.FactAddressRow;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Which text of a log record a translation says in another language: descriptions written in the
 * recording language, and never names, closed values, references, expressions, or observed inputs.
 */
class TranslatableTextTest {
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
  private static final Ref ADDRESS = Ref.urn("urn:scalardb:analytics:d:fact-address:a");
  private static final Ref FACT = Ref.urn("urn:scalardb:analytics:d:fact:a/1");
  private static final Ref POLICY = Ref.urn("urn:scalardb:analytics:d:log-record:c1.1");
  private static final Ref FEEDBACK = Ref.urn("urn:scalardb:analytics:d:input-resource:c2.1");
  private static final Ref DOCUMENT = Ref.urn("urn:scalardb:analytics:d:input-resource:c3.1");

  /** A context in which the Fact Address has a predicate and, optionally, a definition. */
  private static TranslatableText.Context at(String predicate, @Nullable String definition) {
    var row =
        new FactAddressRow(
            ADDRESS.urnValue(), "element", predicate, null, null, List.of(), definition, 1);
    return new TranslatableText.Context() {
      @Override
      public Optional<FactAddressRow> address(String factAddress) {
        return Optional.of(row);
      }

      @Override
      public Optional<InputKind> inputKind(String inputResource) {
        return Optional.of(
            inputResource.equals(FEEDBACK.urnValue())
                ? InputKind.USAGE_FEEDBACK
                : InputKind.DOCUMENT);
      }
    };
  }

  private static Map<String, String> valueText(String predicate, JsonNode value) {
    return TranslatableText.of(new LogRecord.Fact(null, ADDRESS, value, null), at(predicate, null));
  }

  @Test
  void aDescriptionValueIsTranslatedAndNamesClosedValuesAndExpressionsAreNot() {
    assertThat(valueText(Vocabulary.MEANING, JSON.textNode("注文の確定日時")))
        .containsExactly(Map.entry("value", "注文の確定日時"));
    assertThat(valueText(Vocabulary.BUSINESS_DEFINITION, JSON.textNode("返品を含まない")))
        .containsExactly(Map.entry("value", "返品を含まない"));
    assertThat(valueText(Vocabulary.CLASSIFICATION_RULE, JSON.textNode("生年月日から10歳刻み")))
        .containsExactly(Map.entry("value", "生年月日から10歳刻み"));
    assertThat(valueText(Vocabulary.CLASSIFIED_FIELD, JSON.textNode("urn:field"))).isEmpty();
    assertThat(
            valueText(
                Vocabulary.TIME_MEANING,
                JSON.objectNode().put("meaning", "注文の確定時刻").put("timeZone", "Asia/Tokyo")))
        .containsExactly(Map.entry("value.meaning", "注文の確定時刻"));

    // A business term's name is a Fact of its own in each language, so it is never translated.
    assertThat(valueText(Vocabulary.WRITTEN_FORM, JSON.textNode("EC売上"))).isEmpty();
    assertThat(valueText(Vocabulary.VALUE_KIND, JSON.textNode("amount"))).isEmpty();
    assertThat(valueText(Vocabulary.FILTER, JSON.textNode("status <> 'cancelled'"))).isEmpty();
    assertThat(
            valueText(
                Vocabulary.HIERARCHY,
                JSON.objectNode()
                    .set("levels", JSON.arrayNode().add(JSON.objectNode().put("name", "月")))))
        .isEmpty();
  }

  @Test
  void aFreeDescriptionPredicatesValueAndTheFactAddressDescriptionsAreTranslated() {
    var free =
        TranslatableText.of(
            new LogRecord.Fact(null, ADDRESS, JSON.textNode("日をまたいで合計できない"), null),
            at("free-description", "在庫数を日をまたいで合計できるか"));
    assertThat(free).containsExactly(Map.entry("value", "日をまたいで合計できない"));

    var address =
        TranslatableText.of(
            new LogRecord.FactAddress(
                Ref.urn("urn:scalardb:analytics:d:dataset:x"),
                "free-description",
                Scope.described("店舗別の集計", List.of()),
                "在庫数を日をまたいで合計できるか"),
            at(Vocabulary.MEANING, null));
    assertThat(address)
        .containsExactly(
            Map.entry("scope", "店舗別の集計"), Map.entry("predicateDefinition", "在庫数を日をまたいで合計できるか"));
  }

  @Test
  void anEvaluationsReasonAndReviewMaterialAreTranslatedButNotItsSuggestedAnswer() {
    var material =
        JSON.objectNode()
            .put("question", "売上に返品を含むか")
            .put("uncertain", "税の扱い")
            .put("suggestedAnswer", "consent");
    material.putArray("evidenceFor").add("文書が述べている");
    material.putArray("evidenceAgainst");
    material.putArray("suggestionReasons").add("文書と一致する");
    var evaluation =
        new LogRecord.EvaluatePolicy(
            FACT,
            POLICY,
            Transition.CONFIRM,
            PolicyResult.REQUEST_CONSENT,
            new LogRecord.Evaluator.Model(JSON.objectNode(), material),
            "policy-evaluation-model",
            "名前だけが示している");
    assertThat(TranslatableText.of(evaluation, at(Vocabulary.MEANING, null)))
        .containsExactly(
            Map.entry("reason", "名前だけが示している"),
            Map.entry("reviewMaterial.question", "売上に返品を含むか"),
            Map.entry("reviewMaterial.uncertain", "税の扱い"),
            Map.entry("reviewMaterial.evidenceFor[0]", "文書が述べている"),
            Map.entry("reviewMaterial.suggestionReasons[0]", "文書と一致する"));
  }

  @Test
  void wordingAndUsageFeedbackAreTranslatedAndObservedDocumentsAreNot() {
    var answer =
        new LogRecord.Answer(
            FACT, Transition.CONFIRM, AnswerType.CONSENT, "owner", "check", "税抜きで正しい", null);
    assertThat(TranslatableText.of(answer, at(Vocabulary.MEANING, null)))
        .containsExactly(Map.entry("wording", "税抜きで正しい"));

    var feedback =
        LogRecord.InputObservation.of(
            FEEDBACK,
            new UsageFeedback("税抜きの金額だった", SessionPurpose.COMPOSING_A_QUERY, "売上の質問", List.of()));
    assertThat(TranslatableText.of(feedback, at(Vocabulary.MEANING, null)))
        .containsExactly(Map.entry("content", "税抜きの金額だった"), Map.entry("contextSummary", "売上の質問"));

    // A document is observed in its own words, not written in the recording language.
    var document = LogRecord.InputObservation.of(DOCUMENT, new Document("用語集", "売上は税抜き"));
    assertThat(TranslatableText.of(document, at(Vocabulary.MEANING, null))).isEmpty();
  }
}
