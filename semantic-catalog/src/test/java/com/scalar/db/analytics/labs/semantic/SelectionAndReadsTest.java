/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.scalar.db.analytics.labs.semantic.model.Dependency;
import com.scalar.db.analytics.labs.semantic.model.ElementIdentity;
import com.scalar.db.analytics.labs.semantic.model.Knowledge;
import com.scalar.db.analytics.labs.semantic.model.KnowledgeRequest;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.MatchingCondition;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.SemanticCatalogException;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.AnswerType;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.PolicyResult;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Transition;
import java.util.List;
import org.junit.jupiter.api.Test;

class SelectionAndReadsTest extends StoreScenarioTest {
  @Test
  void theSelectionMovesToTheReplacementAndBackWhenTheReplacementIsRetired() {
    var s = scenario();
    var c = s.begin();
    var orders = c.dataset("shop.public.orders");
    var meaning = c.address(orders, "meaning");
    var first = c.candidate(meaning, "orders including tax");
    c.confirmByMachine(first);
    var written = s.write(c);
    var element = written.urn(orders);
    var firstFact = written.urn(first);
    var firstSelected = s.lastCommit();

    // A Fact made from the selected one is a new candidate until it is confirmed.
    c = s.begin();
    var second =
        c.candidate(
            written.ref(meaning), "orders including tax, excluding shipping", s.on(firstFact));
    var secondFact = s.write(c).urn(second);
    var awaiting = s.lastCommit();
    c = s.begin();
    c.confirmByMachine(Ref.urn(secondFact));
    s.write(c);
    var replaced = s.lastCommit();

    // Retiring the replacement returns the selection to the Fact it replaced.
    c = s.begin();
    c.retireByMachine(Ref.urn(secondFact));
    s.write(c);
    var returned = s.lastCommit();

    assertThat(selected(s.only(firstSelected, element, "meaning"))).isEqualTo(firstFact);
    var whileAwaiting = (Knowledge.Selected) s.only(awaiting, element, "meaning");
    assertThat(whileAwaiting.fact().revision().fact()).isEqualTo(firstFact);
    assertThat(whileAwaiting.candidates()).containsExactly(secondFact);
    assertThat(whileAwaiting.fact().newCandidates()).isTrue();
    assertThat(selected(s.only(replaced, element, "meaning"))).isEqualTo(secondFact);
    assertThat(selected(s.only(returned, element, "meaning"))).isEqualTo(firstFact);
  }

  @Test
  void independentlyMadeFactsConflictUntilTheDecidingPartyResolvesIt() {
    var s = scenario();
    var c = s.begin();
    var orders = c.dataset("shop.public.orders");
    var key = c.address(orders, "key");
    var declared = c.candidate(key, "order_id");
    c.confirmByMachine(declared);
    var written = s.write(c);
    var element = written.urn(orders);
    var keyAddress = written.ref(key);
    var declaredFact = written.urn(declared);

    // A Fact made without the selected one leaves two eligible Facts, and the old one stays.
    c = s.begin();
    var documented = c.candidate(keyAddress, "order_number");
    c.confirmByMachine(documented);
    var documentedFact = s.write(c).urn(documented);
    var conflict = (Knowledge.Conflict) s.only(s.lastCommit(), element, "key");
    assertThat(conflict.selected())
        .extracting(content -> content.revision().fact())
        .isEqualTo(declaredFact);
    assertThat(conflict.eligible())
        .extracting(content -> content.revision().fact())
        .containsExactlyInAnyOrder(declaredFact, documentedFact);

    // A resolution names exactly the eligible Facts.
    var partial = s.begin();
    partial.resolveConflict(keyAddress, Ref.urn(documentedFact), Ref.urn(documentedFact));
    assertRejected(s, partial, "chooses one Fact and passes over at least one other");
    var stranger = s.begin();
    stranger.resolveConflict(
        keyAddress, Ref.urn(documentedFact), Ref.urn(written.urn(new Ref.Temp(2))));
    assertRejected(s, stranger, "names exactly the eligible Facts");

    c = s.begin();
    c.resolveConflict(keyAddress, Ref.urn(documentedFact), Ref.urn(declaredFact));
    s.write(c);
    assertThat(selected(s.only(s.lastCommit(), element, "key"))).isEqualTo(documentedFact);

    // With one eligible Fact there is no conflict left to resolve.
    var again = s.begin();
    again.resolveConflict(keyAddress, Ref.urn(declaredFact), Ref.urn(documentedFact));
    assertRejected(s, again, "names exactly the eligible Facts");

    // Retiring the chosen Fact makes the passed-over one eligible again.
    c = s.begin();
    c.retireByMachine(Ref.urn(documentedFact));
    s.write(c);
    assertThat(selected(s.only(s.lastCommit(), element, "key"))).isEqualTo(declaredFact);
  }

  @Test
  void aResolutionLapsesWhenTheChosenFactIsRetiredAndConfirmedAgain() {
    var s = scenario();
    var c = s.begin();
    var orders = c.dataset("shop.public.orders");
    var premise = c.candidate(c.address(orders, "row-kind"), "event");
    c.confirmByMachine(premise);
    var key = c.address(orders, "key");
    var declared = c.candidate(key, "order_id");
    c.confirmByMachine(declared);
    var documented = c.candidate(key, "order_number");
    c.confirmByMachine(documented);
    var written = s.write(c);
    var element = written.urn(orders);
    var declaredFact = written.ref(declared);
    var documentedFact = written.urn(documented);

    c = s.begin();
    c.resolveConflict(written.ref(key), declaredFact, Ref.urn(documentedFact));
    s.write(c);
    c = s.begin();
    c.retireByMachine(declaredFact);
    s.write(c);
    assertThat(selected(s.only(s.lastCommit(), element, "key"))).isEqualTo(documentedFact);

    // Confirmed again on new input, the once-chosen Fact meets the selected one as an independent
    // Fact: a conflict, with the Fact in use still presented.
    c = s.begin();
    c.accept(declaredFact, s.on(written.urn(premise)));
    c.confirmByMachine(declaredFact);
    s.write(c);
    var conflict = (Knowledge.Conflict) s.only(s.lastCommit(), element, "key");
    assertThat(conflict.selected())
        .extracting(content -> content.revision().fact())
        .isEqualTo(documentedFact);
    assertThat(conflict.eligible()).hasSize(2);
  }

  @Test
  void twoReplacementsAtOnceLeaveTheReplacedFactSelectedWithItsContent() {
    var s = scenario();
    var c = s.begin();
    var orders = c.dataset("shop.public.orders");
    var meaning = c.address(orders, "meaning");
    var original = c.candidate(meaning, "orders");
    c.confirmByMachine(original);
    var written = s.write(c);
    var element = written.urn(orders);
    var originalFact = written.urn(original);

    c = s.begin();
    var one = c.candidate(written.ref(meaning), "sales orders", s.on(originalFact));
    var other = c.candidate(written.ref(meaning), "customer orders", s.on(originalFact));
    c.confirmByMachine(one);
    c.confirmByMachine(other);
    s.write(c);

    var conflict = (Knowledge.Conflict) s.only(s.lastCommit(), element, "meaning");
    assertThat(conflict.eligible()).hasSize(2);
    assertThat(conflict.selected())
        .extracting(content -> content.revision().fact())
        .isEqualTo(originalFact);
  }

  @Test
  void aBatchReadAtAnEarlierPointShowsFactsAsTheyStoodThen() {
    var s = scenario();
    var c = s.begin();
    var orders = c.dataset("shop.public.orders");
    var meaning = c.candidate(c.address(orders, "meaning"), "orders");
    c.confirmByMachine(meaning);
    var unit = c.candidate(c.address(orders, "unit"), "yen");
    c.confirmByMachine(unit);
    var written = s.write(c);
    var element = written.urn(orders);
    var earlier = s.lastCommit();

    // Both Facts change after the earlier point.
    c = s.begin();
    c.accept(written.ref(meaning), s.on(written.urn(unit)));
    c.proposeRetire(written.ref(unit));
    s.write(c);

    var requests =
        List.of(KnowledgeRequest.of(element, "meaning"), KnowledgeRequest.of(element, "unit"));
    List<Knowledge.Answer> then = s.at(earlier, snapshot -> snapshot.request(requests));
    for (var answer : then) {
      var shown = ((Knowledge.Selected) answer.addresses().getFirst().knowledge()).fact();
      assertThat(shown.revision().event().commit()).isEqualTo(earlier);
      assertThat(shown.revision().dependencies()).isEmpty();
    }
    List<Knowledge.Answer> now = s.latest(snapshot -> snapshot.request(requests));
    var meaningNow =
        ((Knowledge.Selected) now.getFirst().addresses().getFirst().knowledge()).fact();
    assertThat(meaningNow.revision().dependencies()).hasSize(1);
  }

  @Test
  void factsConfirmedTogetherAtAnAddressWithoutSelectionConflictWithNothingSelected() {
    var s = scenario();
    var c = s.begin();
    var orders = c.dataset("shop.public.orders");
    var unit = c.address(orders, "unit");
    var yen = c.candidate(unit, "yen");
    var dollars = c.candidate(unit, "dollars");
    c.confirmByMachine(yen);
    c.confirmByMachine(dollars);
    var written = s.write(c);
    var element = written.urn(orders);

    var conflict = (Knowledge.Conflict) s.only(s.lastCommit(), element, "unit");
    assertThat(conflict.selected()).isNull();
    assertThat(conflict.eligible()).hasSize(2);

    c = s.begin();
    c.resolveConflict(written.ref(unit), written.ref(yen), written.ref(dollars));
    s.write(c);
    assertThat(selected(s.only(s.lastCommit(), element, "unit"))).isEqualTo(written.urn(yen));
  }

  @Test
  void requestsReportCandidatesAwaitingJudgementAndMissingKnowledge() {
    var s = scenario();
    var c = s.begin();
    var orders = c.dataset("shop.public.orders");
    var key = c.candidate(c.address(orders, "key"), "order_id");
    var meaning = c.candidate(c.address(orders, "meaning"), "orders");
    c.evaluate(meaning, Transition.CONFIRM, PolicyResult.REQUEST_CONSENT);
    var rejected = c.candidate(c.address(orders, "row-kind"), "event");
    var answer = c.answer(rejected, Transition.CONFIRM, AnswerType.REJECTION);
    c.add(new LogRecord.Reject(rejected, new LogRecord.Basis.Event(answer)));
    var written = s.write(c);
    var element = written.urn(orders);

    c = s.begin();
    // A candidate whose premise, the key, is still a candidate itself.
    c.candidate(c.address(written.ref(orders), "row-grain"), "order line", s.on(written.urn(key)));
    s.write(c);
    var point = s.lastCommit();

    assertThat(s.only(point, element, "key"))
        .isEqualTo(
            new Knowledge.AwaitingJudgement(
                List.of(
                    Knowledge.Candidate.of(
                        written.urn(key), Knowledge.Waiting.POLICY_EVALUATION))));
    assertThat(s.only(point, element, "meaning"))
        .isEqualTo(
            new Knowledge.AwaitingJudgement(
                List.of(Knowledge.Candidate.of(written.urn(meaning), Knowledge.Waiting.CONSENT))));
    assertThat(
            ((Knowledge.AwaitingJudgement) s.only(point, element, "row-grain"))
                .candidates()
                .getFirst()
                .waitingFor())
        .isEqualTo(Knowledge.Waiting.DEPENDENCIES);
    var missing = (Knowledge.MissingKnowledge) s.only(point, element, "row-kind");
    assertThat(missing.rejectedOrRetired())
        .singleElement()
        .satisfies(
            ended -> {
              assertThat(ended.fact().revision().fact()).isEqualTo(written.urn(rejected));
              assertThat(ended.answers())
                  .singleElement()
                  .satisfies(
                      given -> {
                        assertThat(given.answerType()).isEqualTo(AnswerType.REJECTION);
                        assertThat(given.decidedBy()).isEqualTo("owner");
                        assertThat(given.wording()).isEqualTo("wording");
                      });
            });
    assertThat(s.knowledge(point, element, "uniqueness")).isEmpty();

    var unknown = "urn:scalardb:analytics:" + s.domain + ":dataset:shop.public.none";
    assertThatThrownBy(() -> s.knowledge(point, unknown, "key"))
        .isInstanceOf(SemanticCatalogException.class)
        .hasMessageContaining("Target not found");
  }

  @Test
  void theFieldsOfADatasetAndTheirKnowledgeAreReadInOneRequest() {
    var s = scenario();
    var c = s.begin();
    var orders = c.dataset("shop.public.orders");
    c.confirmByMachine(new Ref.Temp(2));
    var amount = field(c, orders, "shop.public.orders.amount", true);
    var status = field(c, orders, "shop.public.orders.status", true);
    var pending = field(c, orders, "shop.public.orders.note", false);
    var amountMeaning = c.candidate(c.address(amount, "meaning"), "order amount including tax");
    c.confirmByMachine(amountMeaning);
    var written = s.write(c);
    var point = s.lastCommit();

    // The screen first finds the presented Fields of the Dataset.
    List<Knowledge.Element> fields =
        s.at(point, snapshot -> snapshot.findElements(ElementKind.FIELD, written.urn(orders)));
    assertThat(fields)
        .extracting(Knowledge.Element::element)
        .containsExactlyInAnyOrder(written.urn(amount), written.urn(status))
        .doesNotContain(written.urn(pending));
    List<Knowledge.Element> datasets =
        s.at(point, snapshot -> snapshot.findElements(ElementKind.DATASET, null));
    assertThat(datasets).extracting(Knowledge.Element::element).contains(written.urn(orders));

    // Then it asks for every predicate of every Field at once.
    var requests =
        fields.stream()
            .flatMap(
                field ->
                    List.of("meaning", Vocabulary.EXISTENCE).stream()
                        .map(predicate -> KnowledgeRequest.of(field.element(), predicate)))
            .toList();
    List<Knowledge.Answer> answers = s.at(point, snapshot -> snapshot.request(requests));
    assertThat(answers).extracting(Knowledge.Answer::request).containsExactlyElementsOf(requests);
    for (var answer : answers) {
      var target = ((KnowledgeRequest.Target.Element) answer.request().target()).element();
      if ("meaning".equals(answer.request().predicate())) {
        if (target.equals(written.urn(amount))) {
          assertThat(selected(answer.addresses().getFirst().knowledge()))
              .isEqualTo(written.urn(amountMeaning));
        } else {
          assertThat(answer.addresses()).isEmpty();
        }
      } else {
        assertThat(answer.addresses().getFirst().knowledge())
            .isInstanceOf(Knowledge.Selected.class);
      }
    }
  }

  @Test
  void anEdgeIsRequestedByItsKindAndEnds() {
    var s = scenario();
    var c = s.begin();
    var lines = c.dataset("shop.public.order_lines");
    var orders = c.dataset("shop.public.orders");
    var lineShop = c.field(lines, "shop.public.order_lines.shop_id");
    var lineOrder = c.field(lines, "shop.public.order_lines.order_no");
    var shop = c.field(orders, "shop.public.orders.shop_id");
    var order = c.field(orders, "shop.public.orders.order_no");
    var correspondence =
        c.correspondence(
            MatchingCondition.direct(
                MatchingCondition.Pair.of(lineShop, shop),
                MatchingCondition.Pair.of(lineOrder, order)));
    c.closedRangeCandidate(c.address(correspondence, "set-relationship"), "subset");
    var written = s.write(c);
    var point = s.lastCommit();

    // An end is named by its Fields in any order.
    var forward =
        KnowledgeRequest.edge(
            ElementKind.IDENTIFIER_CORRESPONDENCE,
            List.of(written.urn(lineOrder), written.urn(lineShop)),
            List.of(written.urn(shop), written.urn(order)),
            "set-relationship");
    var backward =
        KnowledgeRequest.edge(
            ElementKind.IDENTIFIER_CORRESPONDENCE,
            List.of(written.urn(shop), written.urn(order)),
            List.of(written.urn(lineShop), written.urn(lineOrder)),
            "set-relationship");
    var part =
        KnowledgeRequest.edge(
            ElementKind.IDENTIFIER_CORRESPONDENCE,
            List.of(written.urn(lineShop)),
            List.of(written.urn(shop)),
            "set-relationship");
    List<Knowledge.Answer> answers =
        s.at(
            point,
            snapshot ->
                snapshot.request(
                    List.of(forward, backward, part), Knowledge.View.WITH_UNCONFIRMED));
    assertThat(answers.getFirst().addresses())
        .singleElement()
        .satisfies(at -> assertThat(at.element()).isEqualTo(written.urn(correspondence)));
    // A directed edge is named in its direction, and an end is the whole Field set.
    assertThat(answers.get(1).addresses()).isEmpty();
    assertThat(answers.get(2).addresses()).isEmpty();
  }

  @Test
  void aSelectedFactShowsWhenAnInputItDependsOnHasANewerObservation() {
    var s = scenario();
    var c = s.begin();
    var orders = c.dataset("shop.public.orders");
    var resource = c.add(LogRecord.InputResource.physicalSchema("shop.orders"));
    var observation =
        c.add(
            new LogRecord.InputObservation(
                resource, null, JsonNodeFactory.instance.objectNode().put("columns", 3)));
    var kind =
        c.candidate(
            c.address(orders, "row-kind"), "event", new Dependency.OnInput(resource, observation));
    c.confirmByMachine(kind);
    var written = s.write(c);
    var element = written.urn(orders);
    var before = s.lastCommit();

    c = s.begin();
    c.add(
        new LogRecord.InputObservation(
            written.ref(resource), null, JsonNodeFactory.instance.objectNode().put("columns", 4)));
    s.write(c);

    assertThat(((Knowledge.Selected) s.only(before, element, "row-kind")).fact().newerObservation())
        .isFalse();
    assertThat(
            ((Knowledge.Selected) s.only(s.lastCommit(), element, "row-kind"))
                .fact()
                .newerObservation())
        .isTrue();
  }

  /** A Field of a Dataset with accepted Facts, its existence confirmed when asked. */
  private static Ref field(Scenario.Commit c, Ref dataset, String column, boolean confirmed) {
    var field =
        c.add(
            new LogRecord.Element(
                ElementIdentity.PhysicalObject.field(dataset, Scenario.column(column))));
    var existence = c.closedRangeCandidate(c.address(field, Vocabulary.EXISTENCE), "exists");
    c.closedRangeCandidate(
        c.address(field, Vocabulary.TECHNICAL_REFERENCE), Scenario.column(column).canonical());
    if (confirmed) {
      c.confirmByMachine(existence);
    }
    return field;
  }

  private static String selected(Knowledge knowledge) {
    assertThat(knowledge).isInstanceOf(Knowledge.Selected.class);
    return ((Knowledge.Selected) knowledge).fact().revision().fact();
  }
}
