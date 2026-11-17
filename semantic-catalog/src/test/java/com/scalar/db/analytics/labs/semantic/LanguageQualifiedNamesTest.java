/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.scalar.db.analytics.labs.semantic.model.ElementIdentity;
import com.scalar.db.analytics.labs.semantic.model.Knowledge;
import com.scalar.db.analytics.labs.semantic.model.KnowledgeRequest;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A business term's names in the domain's declared languages (see {@code
 * docs/semantic-catalog/languages.md}, "Names: one Fact per language"). The test domains declare
 * Japanese and English.
 */
class LanguageQualifiedNamesTest extends StoreScenarioTest {
  @Test
  void namesInDifferentLanguagesAreBothSelectedAndEitherFindsTheTerm() {
    var s = scenario();
    var c = s.begin();
    var measure = confirmedNode(c, ElementKind.MEASURE, "ec-sales");
    var term = confirmedNode(c, ElementKind.BUSINESS_TERM, "ec-sales");
    var written = s.write(c);
    c = s.begin();
    var termRef = written.ref(term);
    c.confirmByMachine(
        c.closedRangeCandidate(c.address(termRef, Vocabulary.WRITTEN_FORM, "ja"), "EC売上"));
    c.confirmByMachine(
        c.closedRangeCandidate(c.address(termRef, Vocabulary.WRITTEN_FORM, "en"), "EC sales"));
    c.confirmByMachine(
        c.closedRangeCandidate(
            c.address(termRef, Vocabulary.REFERENCED_ELEMENT), written.urn(measure)));
    s.write(c);
    var termUrn = written.urn(term);

    var names = s.knowledge(s.lastCommit(), termUrn, Vocabulary.WRITTEN_FORM);
    assertThat(names)
        .extracting(Knowledge.AtAddress::language, Knowledge.AtAddress::factAddress)
        .containsExactlyInAnyOrder(
            tuple(
                "ja",
                "urn:scalardb:analytics:"
                    + s.domain
                    + ":fact-address:business-term:ec-sales/written-form;ja"),
            tuple(
                "en",
                "urn:scalardb:analytics:"
                    + s.domain
                    + ":fact-address:business-term:ec-sales/written-form;en"));
    assertThat(names).allMatch(name -> name.knowledge() instanceof Knowledge.Selected);
    assertThat(missingNames(s, termUrn)).isEmpty();
    for (var name : List.of("EC売上", "EC sales")) {
      List<Knowledge.Element> found = s.latest(snapshot -> snapshot.findElements(null, null, name));
      assertThat(found)
          .extracting(Knowledge.Element::element)
          .containsExactly(written.urn(measure));
    }
  }

  @Test
  void twoNamesInOneLanguageConflict() {
    var s = scenario();
    var c = s.begin();
    var term = confirmedNode(c, ElementKind.BUSINESS_TERM, "ec-sales");
    var address = c.address(term, Vocabulary.WRITTEN_FORM, "en");
    c.confirmByMachine(c.closedRangeCandidate(address, "EC sales"));
    var written = s.write(c);

    // The same language finds the same Fact Address, and a second name there conflicts.
    c = s.begin();
    c.confirmByMachine(
        c.closedRangeCandidate(
            c.address(written.ref(term), Vocabulary.WRITTEN_FORM, "en"), "online revenue"));
    s.write(c);

    assertThat(s.only(s.lastCommit(), written.urn(term), Vocabulary.WRITTEN_FORM))
        .isInstanceOf(Knowledge.Conflict.class);
  }

  @Test
  void aNameMissingInADeclaredLanguageIsMissingKnowledgeForThatLanguage() {
    var s = scenario();
    var c = s.begin();
    var term = confirmedNode(c, ElementKind.BUSINESS_TERM, "ec-sales");
    c.confirmByMachine(
        c.closedRangeCandidate(c.address(term, Vocabulary.WRITTEN_FORM, "ja"), "EC売上"));
    var written = s.write(c);

    assertThat(missingNames(s, written.urn(term)))
        .containsExactly(new Knowledge.Missing(written.urn(term), Vocabulary.WRITTEN_FORM, "en"));
  }

  @Test
  void aNameNeedsADeclaredLanguageAndOtherPredicatesTakeNone() {
    var s = scenario();
    var c = s.begin();
    var term = confirmedNode(c, ElementKind.BUSINESS_TERM, "ec-sales");
    var written = s.write(c);

    var withoutLanguage = s.begin();
    withoutLanguage.closedRangeCandidate(
        withoutLanguage.address(written.ref(term), Vocabulary.WRITTEN_FORM), "EC sales");
    assertRejected(s, withoutLanguage, "is language-qualified and needs one of the declared");

    var undeclared = s.begin();
    undeclared.closedRangeCandidate(
        undeclared.address(written.ref(term), Vocabulary.WRITTEN_FORM, "fr"), "ventes en ligne");
    assertRejected(s, undeclared, "Language fr is not among the domain's declared languages");

    var onAnotherPredicate = s.begin();
    onAnotherPredicate.candidate(
        onAnotherPredicate.address(written.ref(term), Vocabulary.REFERENCED_ELEMENT, "en"),
        "anything");
    assertRejected(s, onAnotherPredicate, "is not language-qualified and takes no language");
  }

  private static Ref confirmedNode(Scenario.Commit c, ElementKind kind, String slug) {
    var node = c.add(new LogRecord.Element(new ElementIdentity.Unique(kind, slug)));
    c.confirmByMachine(c.closedRangeCandidate(c.address(node, Vocabulary.EXISTENCE), "exists"));
    return node;
  }

  private static List<Knowledge.Missing> missingNames(Scenario s, String term) {
    return s.latest(
            snapshot ->
                snapshot.request(List.of(KnowledgeRequest.of(term, Vocabulary.WRITTEN_FORM))))
        .getFirst()
        .missing();
  }
}
