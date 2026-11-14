/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.analytics.labs.semantic.model.Dependency;
import com.scalar.db.analytics.labs.semantic.model.ElementIdentity;
import com.scalar.db.analytics.labs.semantic.model.FactRevision;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.LogRecord.Basis;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.AnswerType;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ConfirmationPath;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.PolicyResult;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Transition;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FactLifecycleTest extends StoreScenarioTest {
  @Test
  void statesAndRevisionsAreReadAsTheyStoodAtEachCommit() {
    var s = scenario();
    var c = s.begin();
    var orders = c.dataset("shop.public.orders");
    var key = c.candidate(c.address(orders, "key"), "order_id");
    var written = s.write(c);
    var fact = written.urn(key);
    var accepted = s.lastCommit();
    var candidateRevision = s.revision(fact).urn();

    c = s.begin();
    var evaluation =
        c.evaluate(written.ref(key), Transition.CONFIRM, PolicyResult.MACHINE_JUDGEMENT);
    c.add(new LogRecord.Confirm(written.ref(key), ConfirmationPath.MACHINE_JUDGEMENT, evaluation));
    var confirmation = s.write(c);
    var confirmed = s.lastCommit();
    // The evaluation produced a revision of its own, inside the commit, before the confirmation.
    var evaluatedRevision = Urns.factRevision(fact, Urns.position(confirmation.urn(evaluation)));

    c = s.begin();
    c.retireByMachine(written.ref(key));
    s.write(c);
    var retired = s.lastCommit();

    assertThat(s.stateAt(accepted - 1, fact)).isEmpty();
    assertThat(s.stateAt(accepted, fact)).contains(FactState.CANDIDATE);
    assertThat(s.stateAt(confirmed, fact)).contains(FactState.CONFIRMED);
    assertThat(s.stateAt(retired, fact)).contains(FactState.RETIRED);

    // A Fact Revision is the Fact with its events applied up to the one that produced it.
    var latest = s.revision(fact);
    assertThat(latest.state()).isEqualTo(FactState.RETIRED);
    assertThat(latest.confirmationPath()).isEqualTo(ConfirmationPath.MACHINE_JUDGEMENT);
    var earlier = s.latest(snapshot -> snapshot.revision(candidateRevision)).orElseThrow();
    assertThat(earlier.state()).isEqualTo(FactState.CANDIDATE);
    assertThat(earlier.confirmationPath()).isNull();
    var atConfirmation = s.at(confirmed, snapshot -> snapshot.latestRevision(fact)).orElseThrow();
    assertThat(atConfirmation.state()).isEqualTo(FactState.CONFIRMED);

    // A revision from inside a commit is read by its URN, though no point shows it.
    var evaluated = s.latest(snapshot -> snapshot.revision(evaluatedRevision)).orElseThrow();
    assertThat(evaluated.state()).isEqualTo(FactState.CANDIDATE);
    assertThat(evaluated.urn()).isEqualTo(evaluatedRevision);

    // A revision produced after the snapshot's commit is not in it.
    Optional<FactRevision> notYet = s.at(accepted, snapshot -> snapshot.revision(latest.urn()));
    assertThat(notYet).isEmpty();
  }

  @Test
  void consentConfirmsAndRetiresWhenThePolicyAsksForIt() {
    var s = scenario();
    var c = s.begin();
    var orders = c.dataset("shop.public.orders");
    var meaning = c.candidate(c.address(orders, "meaning"), "orders placed by customers");
    var request = c.evaluate(meaning, Transition.CONFIRM, PolicyResult.REQUEST_CONSENT);
    var written = s.write(c);
    var fact = written.ref(meaning);

    // An evaluation requesting consent is no basis for a machine-judgement confirmation.
    var withoutConsent = s.begin();
    withoutConsent.add(
        new LogRecord.Confirm(fact, ConfirmationPath.MACHINE_JUDGEMENT, written.ref(request)));
    assertRejected(s, withoutConsent, "rests on the latest policy evaluation");

    c = s.begin();
    var consent = c.answer(fact, Transition.CONFIRM, AnswerType.CONSENT);
    c.add(new LogRecord.Confirm(fact, ConfirmationPath.HUMAN_CONSENT, consent));
    s.write(c);
    assertThat(s.state(fact.urnValue())).contains(FactState.CONFIRMED);

    c = s.begin();
    c.proposeRetire(fact);
    c.evaluate(fact, Transition.RETIRE, PolicyResult.REQUEST_CONSENT);
    s.write(c);
    c = s.begin();
    var retirementConsent = c.answer(fact, Transition.RETIRE, AnswerType.CONSENT);
    c.add(new LogRecord.Retire(fact, new Basis.Event(retirementConsent)));
    s.write(c);
    assertThat(s.state(fact.urnValue())).contains(FactState.RETIRED);
  }

  @Test
  void factEventsTheStateDoesNotAcceptOrWithoutTheirBasisFailTheCommit() {
    var s = scenario();
    var c = s.begin();
    var orders = c.dataset("shop.public.orders");
    var key = c.address(orders, "key");
    var candidate = c.candidate(key, "order_id");
    var written = s.write(c);
    var fact = written.ref(candidate);

    // A confirmation resting on an acceptance instead of a policy evaluation or a consent.
    var withoutBasis = s.begin();
    var accept = withoutBasis.accept(fact);
    withoutBasis.add(new LogRecord.Confirm(fact, ConfirmationPath.MACHINE_JUDGEMENT, accept));
    assertRejected(s, withoutBasis, "rests on the latest policy evaluation");

    // A consent confirmation needs a consent answer.
    var withoutConsent = s.begin();
    var unknown = withoutConsent.answer(fact, Transition.CONFIRM, AnswerType.UNKNOWN);
    withoutConsent.add(new LogRecord.Confirm(fact, ConfirmationPath.HUMAN_CONSENT, unknown));
    assertRejected(s, withoutConsent, "rests on the latest answer");

    // A candidate is never retired; a retirement proposal against it can only reject it.
    var retireCandidate = s.begin();
    var evaluation =
        retireCandidate.evaluate(fact, Transition.CONFIRM, PolicyResult.MACHINE_JUDGEMENT);
    retireCandidate.add(new LogRecord.Retire(fact, new Basis.Event(evaluation)));
    assertRejected(s, retireCandidate, "in state candidate does not accept retire");

    // A new element comes with the acceptance of its existence.
    var bareElement = s.begin();
    bareElement.add(new LogRecord.Element(new ElementIdentity.Unique(ElementKind.CONCEPT, "bare")));
    assertRejected(s, bareElement, "acceptance of a Fact of its existence");

    // A new Fact is accepted in the commit that creates it.
    var bareFact = s.begin();
    bareFact.closedRangeFact(written.ref(key), "order_number");
    assertRejected(s, bareFact, "is accepted in the commit that creates it");

    assertThat(s.state(fact.urnValue())).contains(FactState.CANDIDATE);

    // Confirmed, it accepts neither a confirmation nor a rejection.
    c = s.begin();
    var evaluated = c.evaluate(fact, Transition.CONFIRM, PolicyResult.MACHINE_JUDGEMENT);
    c.add(new LogRecord.Confirm(fact, ConfirmationPath.MACHINE_JUDGEMENT, evaluated));
    var confirmed = s.write(c);
    var again = s.begin();
    again.add(
        new LogRecord.Confirm(fact, ConfirmationPath.MACHINE_JUDGEMENT, confirmed.ref(evaluated)));
    assertRejected(s, again, "in state confirmed does not accept confirm");
    var reject = s.begin();
    var rejection = reject.answer(fact, Transition.CONFIRM, AnswerType.REJECTION);
    reject.add(new LogRecord.Reject(fact, new Basis.Event(rejection)));
    assertRejected(s, reject, "in state confirmed does not accept reject");
  }

  @Test
  void aRetirementProposalAgainstACandidateRejectsIt() {
    var s = scenario();
    var c = s.begin();
    var orders = c.dataset("shop.public.orders");
    var candidate = c.candidate(c.address(orders, "key"), "order_id");
    var written = s.write(c);
    var fact = written.ref(candidate);

    c = s.begin();
    var proposal = c.proposeRetire(fact);
    c.add(new LogRecord.Reject(fact, new Basis.Event(proposal)));
    s.write(c);
    assertThat(s.state(fact.urnValue())).contains(FactState.REJECTED);
  }

  @Test
  void rejectedFactsReturnToCandidateOnlyWithANewDependency() {
    var s = scenario();
    var c = s.begin();
    var orders = c.dataset("shop.public.orders");
    var key = c.candidate(c.address(orders, "key"), "order_id");
    c.confirmByMachine(key);
    var rejected = c.candidate(c.address(orders, "meaning"), "orders");
    var answer = c.answer(rejected, Transition.CONFIRM, AnswerType.REJECTION);
    c.add(new LogRecord.Reject(rejected, new Basis.Event(answer)));
    var written = s.write(c);
    var fact = written.ref(rejected);
    assertThat(s.state(fact.urnValue())).contains(FactState.REJECTED);

    var unchanged = s.begin();
    unchanged.accept(fact);
    assertRejected(s, unchanged, "brings no new dependency reference");

    // An answer to a rejected Fact is recorded without changing it.
    c = s.begin();
    c.answer(fact, Transition.CONFIRM, AnswerType.CONSENT);
    s.write(c);
    assertThat(s.state(fact.urnValue())).contains(FactState.REJECTED);

    var renewed = s.begin();
    renewed.accept(fact, s.on(written.urn(key)));
    s.write(renewed);
    assertThat(s.state(fact.urnValue())).contains(FactState.CANDIDATE);
  }

  @Test
  void confirmationWaitsForPremisesAndRetiringAPremiseRetiresWhatRestsOnIt() {
    var s = scenario();
    var c = s.begin();
    var orders = c.dataset("shop.public.orders");
    var key = c.candidate(c.address(orders, "key"), "order_id");
    var rowKind = c.candidate(c.address(orders, "row-kind"), "event");
    var written = s.write(c);
    var keyFact = written.urn(key);

    // Uniqueness at another Fact Address has the key as its premise, so until the key is
    // confirmed it is neither evaluated for confirmation nor confirmed.
    var early = s.begin();
    var waiting =
        early.candidate(early.address(written.ref(orders), "uniqueness"), "unique", s.on(keyFact));
    early.evaluate(waiting, Transition.CONFIRM, PolicyResult.MACHINE_JUDGEMENT);
    assertRejected(s, early, "which is not confirmed");

    c = s.begin();
    c.confirmByMachine(Ref.urn(keyFact));
    s.write(c);
    c = s.begin();
    var unique = c.candidate(c.address(written.ref(orders), "uniqueness"), "unique", s.on(keyFact));
    c.confirmByMachine(unique);
    var uniqueFact = s.write(c).urn(unique);

    var alone = s.begin();
    alone.retireByMachine(Ref.urn(keyFact));
    assertRejected(s, alone, "is not retired with it");

    // Accepting the retired key again in the same commit does not excuse the missing cascade.
    var reaccepted = s.begin();
    reaccepted.retireByMachine(Ref.urn(keyFact));
    reaccepted.accept(Ref.urn(keyFact), s.on(written.urn(rowKind)));
    assertRejected(s, reaccepted, "is not retired with it");

    var cascaded = s.begin();
    cascaded.retireByMachine(Ref.urn(keyFact));
    cascaded.add(new LogRecord.Retire(Ref.urn(uniqueFact), new Basis.Cascade(Ref.urn(keyFact))));
    s.write(cascaded);
    assertThat(s.state(uniqueFact)).contains(FactState.RETIRED);
  }

  @Test
  void aRejectedPremiseCascadesToTheCandidatesThatRestOnIt() {
    var s = scenario();
    var c = s.begin();
    var orders = c.dataset("shop.public.orders");
    var key = c.candidate(c.address(orders, "key"), "order_id");
    var written = s.write(c);
    var keyFact = written.ref(key);

    c = s.begin();
    var unique =
        c.candidate(
            c.address(written.ref(orders), "uniqueness"), "unique", s.on(keyFact.urnValue()));
    var dependent = s.write(c).ref(unique);

    // The cascade needs its cause rejected first.
    var premature = s.begin();
    premature.add(new LogRecord.Reject(dependent, new Basis.Cascade(keyFact)));
    assertRejected(s, premature, "rests on a rejected or retired premise dependency");

    c = s.begin();
    var answer = c.answer(keyFact, Transition.CONFIRM, AnswerType.REJECTION);
    c.add(new LogRecord.Reject(keyFact, new Basis.Event(answer)));
    c.add(new LogRecord.Reject(dependent, new Basis.Cascade(keyFact)));
    s.write(c);
    assertThat(s.state(dependent.urnValue())).contains(FactState.REJECTED);
  }

  @Test
  void replacementDependenciesNeitherMakeConfirmationWaitNorCascade() {
    var s = scenario();
    var c = s.begin();
    var orders = c.dataset("shop.public.orders");
    var meaning = c.address(orders, "meaning");
    var first = c.candidate(meaning, "orders");
    var written = s.write(c);
    var firstFact = written.urn(first);

    // A Fact at the same Fact Address made taking the first into account replaces it once
    // confirmed, and its confirmation does not wait for the first.
    c = s.begin();
    var second = c.candidate(written.ref(meaning), "orders placed by customers", s.on(firstFact));
    c.confirmByMachine(second);
    var secondFact = s.write(c).urn(second);
    assertThat(s.state(secondFact)).contains(FactState.CONFIRMED);

    // Retiring the replaced Fact leaves its replacement standing.
    c = s.begin();
    c.confirmByMachine(Ref.urn(firstFact));
    s.write(c);
    c = s.begin();
    c.retireByMachine(Ref.urn(firstFact));
    s.write(c);
    assertThat(s.state(firstFact)).contains(FactState.RETIRED);
    assertThat(s.state(secondFact)).contains(FactState.CONFIRMED);

    // A replacement dependency is no basis for a cascade.
    var cascadeOnReplacement = s.begin();
    cascadeOnReplacement.add(
        new LogRecord.Retire(Ref.urn(secondFact), new Basis.Cascade(Ref.urn(firstFact))));
    assertRejected(s, cascadeOnReplacement, "rests on a retired premise dependency");
  }

  @Test
  void anAcceptanceReplacesTheDependenciesWhileCascadesFollowTheConfirmation() {
    var s = scenario();
    var c = s.begin();
    var orders = c.dataset("shop.public.orders");
    var amount = c.candidate(c.address(orders, "amount-kind"), "numeric");
    var netAmount = c.candidate(c.address(orders, "net-amount-kind"), "numeric");
    c.confirmByMachine(netAmount);
    var rule = c.candidate(c.address(orders, "sales-rule"), "sum of amounts");
    var written = s.write(c);
    var amountFact = written.urn(amount);
    var netAmountFact = written.urn(netAmount);
    var ruleFact = written.ref(rule);

    // The rule first rests on the amount, then on the net amount instead.
    c = s.begin();
    c.accept(ruleFact, s.on(amountFact));
    s.write(c);
    c = s.begin();
    c.accept(ruleFact, s.on(netAmountFact));
    s.write(c);
    assertThat(s.revision(ruleFact.urnValue()).dependencies()).containsExactly(s.on(netAmountFact));

    // The dropped amount, still a candidate, no longer holds the rule back.
    c = s.begin();
    c.confirmByMachine(ruleFact);
    s.write(c);
    assertThat(s.state(ruleFact.urnValue())).contains(FactState.CONFIRMED);

    // Accepted again on the amount alone, the rule stays confirmed with the net amount as its
    // premise, so retiring the net amount still retires the rule.
    c = s.begin();
    c.accept(ruleFact, s.on(amountFact));
    s.write(c);
    var alone = s.begin();
    alone.retireByMachine(Ref.urn(netAmountFact));
    assertRejected(s, alone, "is not retired with it");
    c = s.begin();
    c.retireByMachine(Ref.urn(netAmountFact));
    c.add(new LogRecord.Retire(ruleFact, new Basis.Cascade(Ref.urn(netAmountFact))));
    s.write(c);
    assertThat(s.state(ruleFact.urnValue())).contains(FactState.RETIRED);
  }

  @Test
  void judgementsFromAnEarlierJudgementOrBeforePremisesWereConfirmedDoNotCount() {
    var s = scenario();
    var c = s.begin();
    var orders = c.dataset("shop.public.orders");
    var key = c.candidate(c.address(orders, "key"), "order_id");
    var firstEvaluation = c.evaluate(key, Transition.CONFIRM, PolicyResult.MACHINE_JUDGEMENT);
    c.add(new LogRecord.Confirm(key, ConfirmationPath.MACHINE_JUDGEMENT, firstEvaluation));
    var meaning = c.candidate(c.address(orders, "meaning"), "orders");
    c.confirmByMachine(meaning);
    var premise = c.candidate(c.address(orders, "row-kind"), "event");
    var written = s.write(c);
    var keyFact = written.ref(key);

    // Retired and accepted again, the Fact is judged anew; its first evaluation does not count.
    c = s.begin();
    c.retireByMachine(keyFact);
    s.write(c);
    c = s.begin();
    c.accept(keyFact, s.on(written.urn(meaning)));
    s.write(c);
    var reused = s.begin();
    reused.add(
        new LogRecord.Confirm(
            keyFact, ConfirmationPath.MACHINE_JUDGEMENT, written.ref(firstEvaluation)));
    assertRejected(s, reused, "belongs to an earlier judgement");

    // A consent given while a premise was a candidate does not confirm once it is confirmed.
    c = s.begin();
    var dependent =
        c.candidate(
            c.address(written.ref(orders), "row-grain"), "order line", s.on(written.urn(premise)));
    var consent = c.answer(dependent, Transition.CONFIRM, AnswerType.CONSENT);
    var early = s.write(c);
    var late = s.begin();
    late.confirmByMachine(written.ref(premise));
    late.add(
        new LogRecord.Confirm(
            early.ref(dependent), ConfirmationPath.HUMAN_CONSENT, early.ref(consent)));
    assertRejected(s, late, "was made before Fact");
  }

  @Test
  void dependencyCyclesAndMissingReferencesFailTheCommit() {
    var s = scenario();
    var c = s.begin();
    var orders = c.dataset("shop.public.orders");
    var meaning = c.address(orders, "meaning");
    var first = c.candidate(meaning, "orders");
    var written = s.write(c);
    c = s.begin();
    var second = c.candidate(written.ref(meaning), "sales orders", s.on(written.urn(first)));
    var secondFact = s.write(c).urn(second);
    var before = s.lastCommit();

    // Cycles are rejected whatever the kind of dependency.
    var cycle = s.begin();
    cycle.accept(written.ref(first), s.on(secondFact));
    assertRejected(s, cycle, "forms a cycle");

    var self = s.begin();
    self.accept(written.ref(first), s.on(written.urn(first)));
    assertRejected(s, self, "depends on itself");

    var missing = s.begin();
    var prefix = "urn:scalardb:analytics:" + s.domain + ":";
    missing.accept(
        written.ref(first),
        new Dependency.OnFact(
            Ref.urn(prefix + "fact:dataset:shop.public.none/existence/1"),
            Ref.urn(prefix + "fact-revision:dataset:shop.public.none/existence/1@c99.2")));
    assertRejected(s, missing, "Missing reference");

    var notARevision = s.begin();
    notARevision.accept(
        written.ref(first),
        new Dependency.OnFact(
            Ref.urn(secondFact),
            Ref.urn(prefix + "fact-revision:dataset:shop.public.none/existence/1@c1.1")));
    assertRejected(s, notARevision, "Missing reference: no Fact Revision");

    assertThat(s.lastCommit()).isEqualTo(before);
  }

  @Test
  void theStoredFactRecordMustEqualTheFoldOfItsEvents() throws Exception {
    var s = scenario();
    var c = s.begin();
    var orders = c.dataset("shop.public.orders");
    var key = c.candidate(c.address(orders, "key"), "order_id");
    c.confirmByMachine(key);
    var fact = s.write(c).urn(key);
    assertThat(store.replayCheck(s.domain).matches()).isTrue();

    // A stored Fact Record that differs from folding the Fact's events is reported.
    try (var connection = TestStore.connect();
        var update =
            connection.prepareStatement(
                "UPDATE semantic_catalog.fact_records SET state = 'CANDIDATE'"
                    + " WHERE domain = ? AND fact = ?")) {
      update.setString(1, s.domain);
      update.setString(2, fact);
      assertThat(update.executeUpdate()).isEqualTo(1);
      var diverged = store.replayCheck(s.domain);
      assertThat(diverged.missingFromStore()).singleElement().asString().contains("CONFIRMED");
      assertThat(diverged.unexpectedInStore()).singleElement().asString().contains("CANDIDATE");

      // Restore the row, so the check after the scenario passes again.
      try (var restore =
          connection.prepareStatement(
              "UPDATE semantic_catalog.fact_records SET state = 'CONFIRMED'"
                  + " WHERE domain = ? AND fact = ?")) {
        restore.setString(1, s.domain);
        restore.setString(2, fact);
        restore.executeUpdate();
      }
    }
  }

  @Test
  void aDatasetIsFoundOnlyByATechnicalReferenceNeitherRejectedNorRetired() {
    var s = scenario();
    var c = s.begin();
    var orders = c.dataset("shop.public.orders");
    var written = s.write(c);
    // The Dataset's records are the element, then the existence and technical-reference
    // Fact Addresses, each followed by its Fact.
    var reference = written.ref(new Ref.Temp(4));

    c = s.begin();
    var answer = c.answer(reference, Transition.CONFIRM, AnswerType.REJECTION);
    c.add(new LogRecord.Reject(reference, new Basis.Event(answer)));
    s.write(c);

    c = s.begin();
    var proposed = c.dataset("shop.public.orders");
    var reproposed = s.write(c);
    assertThat(reproposed.urn(proposed)).isNotEqualTo(written.urn(orders));
    assertThat(s.state(reproposed.urn(new Ref.Temp(4)))).contains(FactState.CANDIDATE);
  }
}
