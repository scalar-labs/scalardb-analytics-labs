/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import static com.scalar.db.analytics.labs.semantic.Judgement.declaredDataset;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.analytics.labs.semantic.model.Languages;
import com.scalar.db.analytics.labs.semantic.model.SemanticCatalogException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A domain's recording language: the primary language it was created with, in which every
 * description in its log is to be written (see {@code docs/semantic-catalog/languages.md},
 * "Descriptions: one recording language"). The shared test domains record in Japanese.
 */
class RecordingLanguageTest extends StoreScenarioTest {
  private static SemanticCatalogStore storeDeclaring(Languages languages) {
    return SemanticCatalogStore.open(TestStore.settings(), TestStore.PARSER, domain -> languages);
  }

  @Test
  void aDomainKeepsTheRecordingLanguageItWasCreatedWith() {
    var s = scenario();
    var before = s.lastCommit();
    var c = s.begin();
    declaredDataset(c, "shop.public.orders");
    var request = c.request.build();

    // Making English primary for a domain created in Japanese changes its recording language, so
    // every operation on it fails and nothing is written.
    try (var english = storeDeclaring(new Languages(List.of("en", "ja"), "en"))) {
      assertThatThrownBy(() -> english.commit(s.domain, request))
          .isInstanceOf(SemanticCatalogException.class)
          .hasMessageContaining("records in ja")
          .hasMessageContaining("cannot change");
      assertThatThrownBy(() -> english.read(s.domain, Point.LATEST, CatalogSnapshot::commit))
          .isInstanceOf(SemanticCatalogException.class);
    }
    assertThat(s.lastCommit()).isEqualTo(before);

    // Declaring another language keeps the recording language, so the domain stays usable.
    try (var more = storeDeclaring(new Languages(List.of("ja", "en", "ko"), "ja"))) {
      assertThat(more.commit(s.domain, request).commit()).isPresent();
    }
  }

  @Test
  void anyDeclaredLanguageCanBeARecordingLanguage() {
    try (var french = storeDeclaring(new Languages(List.of("fr", "en"), "fr"))) {
      var s = new Scenario(french);
      var c = s.begin();
      declaredDataset(c, "shop.public.orders");
      assertThat(s.judge(c).commit()).isPresent();
      assertThat(french.replayCheck(s.domain).missingFromStore()).isEmpty();
    }
  }
}
