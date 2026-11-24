/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.scalar.db.analytics.labs.semantic.llm.LanguageModelException;
import com.scalar.db.analytics.labs.semantic.model.ElementIdentity;
import com.scalar.db.analytics.labs.semantic.model.Knowledge;
import com.scalar.db.analytics.labs.semantic.model.KnowledgeRequest;
import com.scalar.db.analytics.labs.semantic.model.LogPosition;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.QueuedTranslation;
import com.scalar.db.analytics.labs.semantic.model.Review;
import com.scalar.db.analytics.labs.semantic.model.SemanticCatalogException;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.AnswerType;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.PolicyResult;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Transition;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Translations made on request (see {@code docs/semantic-catalog/languages.md}, "Translations"): a
 * reader of a declared language other than the recording language asks for the text it shows, which
 * is queued, a translation run translates it outside the log apart from build tasks, and recording
 * text translates nothing. The test domains record in Japanese and also declare English.
 */
class TranslationTest extends StoreScenarioTest {
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

  /** Translates each text into English by prefixing it, so a test can tell which text it says. */
  static final Function<JsonNode, String> PREFIXING =
      input -> {
        var texts = JSON.objectNode();
        input
            .path("texts")
            .properties()
            .forEach(e -> texts.put(e.getKey(), "EN " + e.getValue().asText()));
        return JSON.objectNode().set("texts", texts).toString();
      };

  private static TranslationRun run(Scenario s, ScriptedModel model) {
    return store.runTranslations(s.domain, model);
  }

  private static List<QueuedTranslation> queue(Scenario s) {
    return store.translationQueue(s.domain);
  }

  /** A Field's meaning, written as a candidate in the recording language; its Fact's URN. */
  private static String meaning(Scenario s, String column, String text) {
    var c = s.begin();
    var dataset = c.dataset("shop.public.orders");
    var field = c.field(dataset, "shop.public.orders." + column);
    var fact = c.candidate(c.address(field, Vocabulary.MEANING), text);
    return s.write(c).urn(fact);
  }

  @Test
  void recordingTextTranslatesNothingAndARequestedTranslationIsMadeOutsideTheLog() {
    var s = scenario();
    var fact = meaning(s, "ordered_at", "注文が確定した日時");

    // Recording text in a domain that declares English queues nothing.
    assertThat(queue(s)).isEmpty();
    var commits = s.lastCommit();

    var requested = store.requestTranslations(s.domain, "en", List.of(fact));
    assertThat(requested.pending()).containsOnlyKeys(fact);
    assertThat(queue(s))
        .singleElement()
        .satisfies(
            queued -> {
              assertThat(queued.logRecord()).isEqualTo(requested.pending().get(fact));
              assertThat(queued.language()).isEqualTo("en");
              assertThat(queued.failure()).isNull();
            });
    assertThat(store.translations(s.domain, "en", List.of(fact))).isEmpty();

    var model = new ScriptedModel().translating(PREFIXING);
    var ran = run(s, model);
    assertThat(ran.translated())
        .containsExactly(
            new TranslationRun.Translated(requireNonNull(requested.pending().get(fact)), "en"));
    assertThat(ran.failed()).isEmpty();

    var translations = store.translations(s.domain, "en", List.of(fact));
    assertThat(translations).containsOnlyKeys(fact);
    var translation = java.util.Objects.requireNonNull(translations.get(fact));
    assertThat(translation.texts()).containsExactly(java.util.Map.entry("value", "EN 注文が確定した日時"));
    assertThat(translation.translator().path("model").path("adapter").asText())
        .isEqualTo("scripted");
    assertThat(model.calls)
        .filteredOn(call -> call.generator().equals(ScriptedModel.TRANSLATOR))
        .singleElement()
        .satisfies(
            call -> {
              assertThat(call.input().path("sourceLanguage").asText()).isEqualTo("ja");
              assertThat(call.input().path("targetLanguage").asText()).isEqualTo("en");
            });

    // Translating wrote nothing to the log and no build task, emptied the queue, and a translated
    // record is not requested again.
    assertThat(s.lastCommit()).isEqualTo(commits);
    assertThat(store.buildTasks(s.domain)).isEmpty();
    assertThat(queue(s)).isEmpty();
    var again = store.requestTranslations(s.domain, "en", List.of(fact));
    assertThat(again.pending()).isEmpty();
    assertThat(queue(s)).isEmpty();
  }

  /** A Field whose meaning is confirmed; the Field's URN and the meaning Fact's URN. */
  private record ConfirmedMeaning(String field, String fact) {}

  private static ConfirmedMeaning confirmedMeaning(Scenario s, String text) {
    var c = s.begin();
    var dataset = c.dataset("shop.public.orders");
    var field = c.field(dataset, "shop.public.orders.ordered_at");
    var fact = c.candidate(c.address(field, Vocabulary.MEANING), text);
    c.confirmByMachine(fact);
    var written = s.write(c);
    return new ConfirmedMeaning(written.urn(field), written.urn(fact));
  }

  private static LanguageRead<List<Knowledge.Answer>> meaningIn(
      Scenario s, String language, String field) {
    return store.read(
        s.domain,
        Point.LATEST,
        language,
        snapshot -> snapshot.request(List.of(KnowledgeRequest.of(field, Vocabulary.MEANING))));
  }

  @Test
  void aReadInAnotherLanguageReturnsOriginalsWithTheirTranslationsOncePendingOnesAreTranslated() {
    var s = scenario();
    var meaning = confirmedMeaning(s, "注文が確定した日時");
    var commits = s.lastCommit();

    // Before a translation run, the original comes with a pending mark, and the read queues the
    // translation.
    var before = meaningIn(s, "en", meaning.field());
    assertThat(before.recordingLanguage()).isEqualTo("ja");
    assertThat(before.language()).isEqualTo("en");
    assertThat(before.result())
        .singleElement()
        .satisfies(
            answer ->
                assertThat(answer.addresses())
                    .singleElement()
                    .satisfies(
                        address ->
                            assertThat(address.knowledge())
                                .isInstanceOfSatisfying(
                                    Knowledge.Selected.class,
                                    selected ->
                                        assertThat(selected.fact().revision().value().asText())
                                            .isEqualTo("注文が確定した日時"))));
    assertThat(before.texts())
        .containsOnlyKeys(meaning.fact())
        .extractingByKey(meaning.fact())
        .isInstanceOfSatisfying(
            LanguageRead.Text.Pending.class,
            pending ->
                assertThat(pending.originals())
                    .containsExactly(java.util.Map.entry("value", "注文が確定した日時")));
    assertThat(queue(s)).singleElement();

    // After a translation run, the same read returns the original beside its translation and
    // requests nothing; neither reading nor translating wrote to the log.
    run(s, new ScriptedModel().translating(PREFIXING));
    var after = meaningIn(s, "en", meaning.field());
    assertThat(after.texts())
        .containsOnlyKeys(meaning.fact())
        .extractingByKey(meaning.fact())
        .isInstanceOfSatisfying(
            LanguageRead.Text.Translated.class,
            translated -> {
              assertThat(translated.originals())
                  .containsExactly(java.util.Map.entry("value", "注文が確定した日時"));
              assertThat(translated.translation().language()).isEqualTo("en");
              assertThat(translated.translation().texts())
                  .containsExactly(java.util.Map.entry("value", "EN 注文が確定した日時"));
            });
    assertThat(queue(s)).isEmpty();
    assertThat(s.lastCommit()).isEqualTo(commits);
  }

  @Test
  void aReadInTheRecordingLanguageReturnsOriginalsOnlyAndAnUndeclaredLanguageIsInvalid() {
    var s = scenario();
    var meaning = confirmedMeaning(s, "注文が確定した日時");

    var inJapanese = meaningIn(s, "ja", meaning.field());
    assertThat(inJapanese.texts()).isEmpty();
    assertThat(inJapanese.result()).singleElement();
    assertThat(queue(s)).isEmpty();

    assertThatThrownBy(() -> meaningIn(s, "fr", meaning.field()))
        .isInstanceOf(SemanticCatalogException.class)
        .hasMessageContaining("does not declare the language fr");
    assertThat(queue(s)).isEmpty();
    assertThat(store.languages(s.domain).declared()).containsExactly("ja", "en");
    assertThat(store.languages(s.domain).primary()).isEqualTo("ja");
  }

  @Test
  void aReadInAnotherLanguageCarriesTheTextOfAnswersAndEvaluationsItReturns() {
    var s = scenario();
    var c = s.begin();
    var dataset = c.dataset("shop.public.orders");
    var field = c.field(dataset, "shop.public.orders.ordered_at");
    var rejected = c.candidate(c.address(field, Vocabulary.MEANING), "注文した日");
    var answer = c.answer(rejected, Transition.CONFIRM, AnswerType.REJECTION);
    c.add(new LogRecord.Reject(rejected, new LogRecord.Basis.Event(answer)));
    var awaiting = c.candidate(c.address(field, Vocabulary.NULL_MEANING), "未確定");
    var evaluation = c.evaluate(awaiting, Transition.CONFIRM, PolicyResult.REQUEST_CONSENT);
    var written = s.write(c);
    var answerRecord = written.urn(answer);
    var evaluationRecord = written.urn(evaluation);

    // A rejected Fact's value and the wording of the answer given to it.
    var knowledge =
        store.read(
            s.domain,
            Point.LATEST,
            "en",
            snapshot ->
                snapshot.request(
                    List.of(KnowledgeRequest.of(written.urn(field), Vocabulary.MEANING)),
                    Knowledge.View.WITH_UNCONFIRMED));
    assertThat(knowledge.texts()).containsKeys(written.urn(rejected), answerRecord);
    assertThat(knowledge.texts().get(answerRecord))
        .isInstanceOfSatisfying(
            LanguageRead.Text.Pending.class,
            pending -> {
              assertThat(pending.logRecord()).isEqualTo(answerRecord);
              assertThat(pending.originals())
                  .containsExactly(java.util.Map.entry("wording", "wording"));
            });

    // A review item's evaluation reason.
    var review =
        store.read(s.domain, Point.LATEST, "en", snapshot -> snapshot.reviewList(Review.Query.ALL));
    assertThat(review.texts()).containsKeys(written.urn(awaiting), evaluationRecord);
    assertThat(review.texts().get(evaluationRecord))
        .isInstanceOfSatisfying(
            LanguageRead.Text.Pending.class,
            pending -> assertThat(pending.originals()).containsEntry("reason", "test"));
    assertThat(queue(s)).extracting(QueuedTranslation::logRecord).contains(evaluationRecord);
  }

  @Test
  void aBusinessTermsNamesReadInAnotherLanguageGetNoTranslation() {
    var s = scenario();
    var c = s.begin();
    var term =
        c.add(
            new LogRecord.Element(
                new ElementIdentity.Unique(ElementKind.BUSINESS_TERM, "ec-sales")));
    c.confirmByMachine(c.closedRangeCandidate(c.address(term, Vocabulary.EXISTENCE), "exists"));
    c.confirmByMachine(
        c.closedRangeCandidate(c.address(term, Vocabulary.WRITTEN_FORM, "ja"), "EC売上"));
    var written = s.write(c);

    var read =
        store.read(
            s.domain,
            Point.LATEST,
            "en",
            snapshot ->
                snapshot.request(
                    List.of(KnowledgeRequest.of(written.urn(term), Vocabulary.WRITTEN_FORM))));
    assertThat(read.result().getFirst().addresses()).isNotEmpty();
    assertThat(read.texts()).isEmpty();
    assertThat(queue(s)).isEmpty();
  }

  @Test
  void aTextRequestedAgainIsQueuedOnce() {
    var s = scenario();
    var first = meaning(s, "ordered_at", "注文が確定した日時");
    var second = meaning(s, "shipped_at", "出荷した日時");

    store.requestTranslations(s.domain, "en", List.of(first));
    store.requestTranslations(s.domain, "en", List.of(second, first));
    assertThat(queue(s)).hasSize(2);

    var model = new ScriptedModel().translating(PREFIXING);
    run(s, model);
    assertThat(store.translations(s.domain, "en", List.of(first, second)))
        .containsOnlyKeys(first, second);
  }

  @Test
  void namesClosedValuesAndThePrimaryLanguageRequestNothing() {
    var s = scenario();
    var c = s.begin();
    var term =
        c.add(
            new LogRecord.Element(
                new ElementIdentity.Unique(ElementKind.BUSINESS_TERM, "ec-sales")));
    var existence = c.closedRangeCandidate(c.address(term, Vocabulary.EXISTENCE), "exists");
    var name = c.candidate(c.address(term, Vocabulary.WRITTEN_FORM, "ja"), "EC売上");
    var written = s.write(c);
    var fact = meaning(s, "ordered_at", "注文が確定した日時");

    // A business term's name in another language is a Fact of its own, never a translation.
    var requested =
        store.requestTranslations(
            s.domain, "en", List.of(written.urn(name), written.urn(existence)));
    assertThat(requested.pending()).isEmpty();

    // The recording language's text is the original.
    assertThat(store.requestTranslations(s.domain, "ja", List.of(fact)).pending()).isEmpty();
    assertThat(queue(s)).isEmpty();

    assertThatThrownBy(() -> store.requestTranslations(s.domain, "fr", List.of(fact)))
        .isInstanceOf(SemanticCatalogException.class)
        .hasMessageContaining("does not declare the language fr");
    assertThatThrownBy(
            () ->
                store.requestTranslations(
                    s.domain,
                    "en",
                    List.of("urn:scalardb:analytics:" + s.domain + ":fact:nothing/1")))
        .isInstanceOf(SemanticCatalogException.class)
        .hasMessageContaining("Unknown record");

    // A Fact Revision names a Fact Event of its Fact; the domain's first record is a policy
    // version.
    var notAnEvent = Urns.factRevision(fact, new LogPosition(1, 1));
    assertThatThrownBy(() -> store.requestTranslations(s.domain, "en", List.of(notAnEvent)))
        .isInstanceOf(SemanticCatalogException.class)
        .hasMessageContaining("Unknown record");
  }

  @Test
  void aFailedTranslationStaysQueuedWithItsReasonAndIsTriedAgainByTheNextRun() {
    var s = scenario();
    var ordered = meaning(s, "ordered_at", "注文が確定した日時");
    var shipped = meaning(s, "shipped_at", "出荷した日時");
    var shippedRecord =
        store.requestTranslations(s.domain, "en", List.of(ordered, shipped)).pending().get(shipped);

    // An answer that leaves a text out fails that translation; the other one is kept.
    var failing =
        run(
            s,
            new ScriptedModel()
                .translating(
                    input ->
                        input.path("texts").path("value").asText().contains("出荷")
                            ? "{\"texts\": {}}"
                            : PREFIXING.apply(input)));
    assertThat(failing.translated()).hasSize(1);
    assertThat(failing.failed())
        .singleElement()
        .satisfies(
            failed -> {
              assertThat(failed.logRecord()).isEqualTo(shippedRecord);
              assertThat(failed.reason()).contains("leaves out value");
            });
    assertThat(store.translations(s.domain, "en", List.of(ordered, shipped)))
        .containsOnlyKeys(ordered);
    assertThat(queue(s))
        .singleElement()
        .satisfies(
            queued -> {
              assertThat(queued.logRecord()).isEqualTo(shippedRecord);
              assertThat(queued.failure()).contains("leaves out value");
            });

    // The next run translates only the record still queued.
    var model = new ScriptedModel().translating(PREFIXING);
    var next = run(s, model);
    assertThat(next.translated())
        .containsExactly(new TranslationRun.Translated(requireNonNull(shippedRecord), "en"));
    assertThat(next.failed()).isEmpty();
    assertThat(queue(s)).isEmpty();
    assertThat(store.translations(s.domain, "en", List.of(ordered, shipped)))
        .containsOnlyKeys(ordered, shipped);
    assertThat(model.calls)
        .filteredOn(call -> call.generator().equals(ScriptedModel.TRANSLATOR))
        .singleElement()
        .satisfies(
            call -> assertThat(call.input().path("texts").path("value").asText()).contains("出荷"));
  }

  @Test
  void anUnavailableModelFailsEveryQueuedTranslationOnceAndLeavesThemQueued() {
    var s = scenario();
    var fact = meaning(s, "ordered_at", "注文が確定した日時");
    store.requestTranslations(s.domain, "en", List.of(fact));

    var ran =
        run(
            s,
            new ScriptedModel()
                .translating(
                    input -> {
                      throw new LanguageModelException("The model is unavailable");
                    }));
    assertThat(ran.translated()).isEmpty();
    assertThat(ran.failed())
        .singleElement()
        .satisfies(failed -> assertThat(failed.reason()).contains("unavailable"));
    assertThat(queue(s))
        .singleElement()
        .satisfies(queued -> assertThat(queued.failure()).contains("unavailable"));
  }
}
