/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.analytics.labs.semantic.model.BuildTask;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.SemanticCatalogException;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.UsageFeedback;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.SessionPurpose;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Registering usage feedback a session found worth keeping (see {@code
 * docs/semantic-catalog/public-api.md}, "Registering an observation"): each registration is a new
 * Input Resource, and feedback that relates to Facts starts the Generators that read it.
 */
class UsageFeedbackRegistrationTest extends StoreScenarioTest {
  @Test
  void missingKnowledgeIsRecordedAsNewFeedbackEachTime() {
    var s = scenario();
    var feedback =
        new UsageFeedback(
            "EC売上にどの注文が含まれるかが分からない",
            SessionPurpose.COMPOSING_A_QUERY,
            "2026年9月の EC 売上を求める質問",
            List.of());

    var first = store.registerUsageFeedback(s.domain, "query-agent", feedback);
    var second = store.registerUsageFeedback(s.domain, "query-agent", feedback);

    assertThat(first.catalogSnapshot())
        .isEqualTo(Urns.catalogSnapshot(s.domain, s.lastCommit() - 1));
    assertThat(second.catalogSnapshot()).isEqualTo(Urns.catalogSnapshot(s.domain, s.lastCommit()));
    assertThat(second.inputResource()).isNotEqualTo(first.inputResource());
    assertThat(store.observationContent(s.domain, first.inputObservation()))
        .hasValueSatisfying(
            content -> {
              assertThat(content.path("content").asText()).isEqualTo(feedback.content());
              assertThat(content.path("contextSummary").asText())
                  .isEqualTo(feedback.contextSummary());
            });
    // No Generator reads feedback without related Facts yet.
    assertThat(first.buildTasks()).isEmpty();
    assertThat(store.buildTasks(s.domain)).isEmpty();
  }

  @Test
  void feedbackOnAFactStartsTheGeneratorsThatReadIt() {
    var s = scenario();
    var commit = s.begin();
    var dataset = commit.dataset("shop.public.orders");
    var fact = commit.candidate(commit.address(dataset, "row-kind"), "event");
    var factUrn = s.judge(commit).urn(fact);

    var registration =
        store.registerUsageFeedback(
            s.domain,
            "query-agent",
            new UsageFeedback(
                "注文の行はイベントではなく注文明細の状態を表す",
                SessionPurpose.COMPOSING_A_QUERY,
                "注文の件数を数える質問",
                List.of(Ref.urn(factUrn))));

    assertThat(registration.buildTasks()).isNotEmpty();
    assertThat(store.buildTasks(s.domain))
        .filteredOn(task -> registration.buildTasks().contains(task.number()))
        .extracting(BuildTask::work)
        .contains(new BuildTask.Work.GeneratorRun("correction-interpretation"));
  }

  @Test
  void feedbackOnAFactThatDoesNotExistWritesNothing() {
    var s = scenario();
    var before = s.lastCommit();
    var unknown =
        Urns.fact(
            Urns.factAddress(
                Urns.element(s.domain, Vocabulary.ElementKind.MEASURE, "sales"), "existence"),
            1);

    assertThatThrownBy(
            () ->
                store.registerUsageFeedback(
                    s.domain,
                    "query-agent",
                    new UsageFeedback(
                        "売上は税抜",
                        SessionPurpose.COMPOSING_A_QUERY,
                        "売上の質問",
                        List.of(Ref.urn(unknown)))))
        .isInstanceOf(SemanticCatalogException.class);
    assertThat(s.lastCommit()).isEqualTo(before);
  }
}
