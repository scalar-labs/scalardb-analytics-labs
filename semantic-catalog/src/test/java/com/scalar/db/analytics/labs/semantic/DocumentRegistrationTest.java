/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.analytics.labs.semantic.DocumentRegistration.Observed;
import com.scalar.db.analytics.labs.semantic.DocumentRegistration.Unchanged;
import com.scalar.db.analytics.labs.semantic.model.BuildTask;
import com.scalar.db.analytics.labs.semantic.model.Document;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.SemanticCatalogException;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import org.junit.jupiter.api.Test;

/**
 * Registering a document the semantics owner wrote: a new document becomes an Input Resource, a
 * change is observed again, and unchanged text records nothing (see {@code
 * docs/semantic-catalog/public-api.md}, "Registering an observation").
 */
class DocumentRegistrationTest extends StoreScenarioTest {
  private static final Document GLOSSARY =
      new Document("glossary.md", "# 販売ドメイン用語集\n\n- **EC売上**: EC の注文の税抜金額の合計。\n");

  @Test
  void aNewDocumentBecomesAnInputResourceWithItsFirstObservation() {
    var s = scenario();

    var registration = store.registerDocument(s.domain, "owner", null, GLOSSARY);

    assertThat(registration).isInstanceOf(Observed.class);
    var observed = (Observed) registration;
    assertThat(observed.newInputResource()).isTrue();
    assertThat(observed.commit()).isEqualTo(s.lastCommit());
    assertThat(store.observationContent(s.domain, observed.inputObservation()))
        .hasValueSatisfying(
            content -> {
              assertThat(content.path("name").asText()).isEqualTo("glossary.md");
              assertThat(content.path("text").asText()).isEqualTo(GLOSSARY.text());
            });
    // The observation starts a run of each Generator that reads documents, triggered by it.
    assertThat(store.buildTasks(s.domain))
        .singleElement()
        .satisfies(
            task -> {
              assertThat(task.work())
                  .isEqualTo(new BuildTask.Work.GeneratorRun("concept-inference"));
              assertThat(task.triggers())
                  .containsExactly(
                      Urns.logRecord(s.domain, Urns.position(observed.inputObservation())));
            });
  }

  @Test
  void changedTextIsObservedAgainAndUnchangedTextRecordsNothing() {
    var s = scenario();
    var first = (Observed) store.registerDocument(s.domain, "owner", null, GLOSSARY);

    var changed =
        store.registerDocument(
            s.domain,
            "owner",
            first.inputResource(),
            new Document("glossary.md", GLOSSARY.text() + "- **店舗売上**: 実店舗の販売の税抜金額の合計。\n"));

    assertThat(changed).isInstanceOf(Observed.class);
    var second = (Observed) changed;
    assertThat(second.newInputResource()).isFalse();
    assertThat(second.inputResource()).isEqualTo(first.inputResource());
    assertThat(second.inputObservation()).isNotEqualTo(first.inputObservation());

    var commit = s.lastCommit();
    var again =
        store.registerDocument(
            s.domain,
            "owner",
            first.inputResource(),
            new Document("renamed.md", GLOSSARY.text() + "- **店舗売上**: 実店舗の販売の税抜金額の合計。\n"));

    assertThat(again).isEqualTo(new Unchanged(first.inputResource(), second.inputObservation()));
    assertThat(s.lastCommit()).isEqualTo(commit);
  }

  @Test
  void anInputResourceThatIsNoDocumentIsRefused() {
    var s = scenario();
    var commit = s.begin();
    var schema =
        commit.add(
            LogRecord.InputResource.physicalSchema(
                "shop.public.orders", Scenario.table("shop.public.orders")));
    var written = s.write(commit);
    var physicalSchema = written.urn(schema);
    var last = s.lastCommit();

    assertThatThrownBy(() -> store.registerDocument(s.domain, "owner", physicalSchema, GLOSSARY))
        .isInstanceOf(SemanticCatalogException.class);
    assertThatThrownBy(() -> store.registerDocument(s.domain, "owner", "not-a-resource", GLOSSARY))
        .isInstanceOf(SemanticCatalogException.class);
    assertThat(s.lastCommit()).isEqualTo(last);
  }
}
