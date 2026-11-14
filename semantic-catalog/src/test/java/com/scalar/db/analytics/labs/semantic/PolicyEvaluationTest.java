/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import static com.scalar.db.analytics.labs.semantic.Judgement.check;
import static com.scalar.db.analytics.labs.semantic.Judgement.declaredDataset;
import static com.scalar.db.analytics.labs.semantic.Judgement.fact;
import static com.scalar.db.analytics.labs.semantic.Judgement.noCounterexample;
import static com.scalar.db.analytics.labs.semantic.Judgement.setPolicy;
import static com.scalar.db.analytics.labs.semantic.Judgement.supported;
import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.analytics.labs.semantic.model.ElementIdentity;
import com.scalar.db.analytics.labs.semantic.model.Knowledge;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.Review;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.AnswerType;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.CheckResult;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ConfirmationPath;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Preset;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ValidationMethod;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Policy evaluation inside the commit that makes a candidate evaluable: what each preset adopts or
 * sends to the deciding party, and what {@code auto} leaves for the policy evaluation model.
 */
class PolicyEvaluationTest extends StoreScenarioTest {
  @Test
  void aNewDomainStartsWithAutoNotCountingUnenforcedDeclarations() {
    var s = scenario();
    var check = store.checkPolicyVersion(s.domain, LogRecord.PolicyVersion.DEFAULT);
    assertThat(check.current()).isEqualTo(LogRecord.PolicyVersion.DEFAULT);
    assertThat(check.current().preset()).isEqualTo(Preset.AUTO);
    assertThat(check.current().countsDeclaredUnenforced()).isFalse();
  }

  @Test
  void autoAdoptsInlineOnlyWhatDeterministicChecksSupport() {
    var s = scenario();
    var c = s.begin();
    var orders = declaredDataset(c, "shop.public.orders");
    var exists = orders.existence().on();
    var enforced =
        fact(
            c,
            c.address(orders.dataset(), "key"),
            "order_id",
            List.of(supported(ValidationMethod.DECLARED_ENFORCED, "order_id")),
            exists);
    var inferred =
        fact(
            c,
            c.address(orders.dataset(), "row-kind"),
            "event",
            List.of(supported(ValidationMethod.INFERENCE, "event")),
            exists);
    var partly =
        fact(
            c,
            c.address(orders.dataset(), "row-grain"),
            "order line",
            List.of(
                supported(ValidationMethod.DECLARED_ENFORCED, "order"),
                supported(ValidationMethod.INFERENCE, "line")),
            exists);
    var written = s.judge(c);

    assertThat(s.state(written.urn(orders.existence().fact()))).contains(FactState.CONFIRMED);
    assertThat(s.state(written.urn(enforced.fact()))).contains(FactState.CONFIRMED);
    assertThat(s.revision(written.urn(enforced.fact())).confirmationPath())
        .isEqualTo(ConfirmationPath.MACHINE_JUDGEMENT);
    // Signals the deterministic path cannot weigh wait for the policy evaluation model.
    assertThat(s.state(written.urn(inferred.fact()))).contains(FactState.CANDIDATE);
    assertThat(s.state(written.urn(partly.fact()))).contains(FactState.CANDIDATE);
    assertThat(written.transitions().awaitingModel())
        .containsExactlyInAnyOrder(written.urn(inferred.fact()), written.urn(partly.fact()));
    var waiting =
        (Knowledge.AwaitingJudgement)
            s.only(s.lastCommit(), written.urn(orders.dataset()), "row-kind");
    assertThat(waiting.candidates().getFirst().waiting())
        .isEqualTo(Knowledge.Waiting.POLICY_EVALUATION);
    assertThat(reviewedFacts(s)).isEmpty();
  }

  @Test
  void dataWithoutACounterexampleNeedsADeclarationToAdoptARestrictingValueInline() {
    var s = scenario();
    var c = s.begin();
    var orders = declaredDataset(c, "shop.public.orders");
    var exists = orders.existence().on();
    var dataOnly =
        fact(
            c,
            c.address(orders.dataset(), "uniqueness"),
            "does not repeat",
            List.of(noCounterexample("does not repeat")),
            exists);
    var declaredToo =
        fact(
            c,
            c.address(orders.dataset(), "key"),
            "order_id",
            List.of(
                noCounterexample("order_id"),
                supported(ValidationMethod.DECLARED_UNENFORCED, "order_id")),
            exists);
    var counterexample =
        fact(
            c,
            c.address(orders.dataset(), "row-inclusion"),
            "returns included",
            List.of(supported(ValidationMethod.DATA_CHECK, "returns included")),
            exists);
    var unenforcedOnly =
        fact(
            c,
            c.address(orders.dataset(), "row-grain"),
            "order line",
            List.of(supported(ValidationMethod.DECLARED_UNENFORCED, "order line")),
            exists);
    var written = s.judge(c);

    assertThat(s.state(written.urn(dataOnly.fact()))).contains(FactState.CANDIDATE);
    assertThat(s.state(written.urn(declaredToo.fact()))).contains(FactState.CONFIRMED);
    assertThat(s.state(written.urn(counterexample.fact()))).contains(FactState.CONFIRMED);
    // An unenforced declaration is not deterministic unless the policy version counts it.
    assertThat(s.state(written.urn(unenforcedOnly.fact()))).contains(FactState.CANDIDATE);

    // Checking a version that counts it shows what it would adopt before it is set.
    var check =
        store.checkPolicyVersion(
            s.domain, new LogRecord.PolicyVersion(Preset.AUTO, true, "owner", "test"));
    assertThat(check.adoptedInline()).containsExactly(written.urn(unenforcedOnly.fact()));
    assertThat(check.toModel()).containsExactly(written.urn(dataOnly.fact()));
    assertThat(check.catalogSnapshot()).isEqualTo(s.latest(CatalogSnapshot::urn));
  }

  @Test
  void aRefutedCandidateOrASecondFactAlwaysGoesToAPerson() {
    var s = scenario();
    var c = s.begin();
    var orders = declaredDataset(c, "shop.public.orders");
    var exists = orders.existence().on();
    var kind = c.address(orders.dataset(), "row-kind");
    var refuted =
        fact(
            c,
            c.address(orders.dataset(), "key"),
            "order_id",
            List.of(
                supported(ValidationMethod.DECLARED_ENFORCED, "order_id"),
                check(ValidationMethod.DATA_CHECK, "order_id", CheckResult.REFUTED)),
            exists);
    var first =
        fact(
            c,
            kind,
            "event",
            List.of(supported(ValidationMethod.DECLARED_ENFORCED, "event")),
            exists);
    var written = s.judge(c);
    assertThat(s.state(written.urn(refuted.fact()))).contains(FactState.CANDIDATE);
    assertThat(s.state(written.urn(first.fact()))).contains(FactState.CONFIRMED);
    assertThat(reviewedFacts(s)).containsExactly(written.urn(refuted.fact()));

    c = s.begin();
    var second =
        fact(
            c,
            written.ref(kind),
            "state",
            List.of(supported(ValidationMethod.DECLARED_ENFORCED, "state")),
            s.on(written.urn(orders.existence().fact())));
    var later = s.judge(c);
    assertThat(s.state(later.urn(second.fact()))).contains(FactState.CANDIDATE);
    var item =
        (Review.Candidate)
            s.latest(snapshot -> snapshot.reviewList(Review.Query.ALL)).stream()
                .flatMap(group -> group.items().stream())
                .filter(found -> found.target().predicate().equals("row-kind"))
                .findFirst()
                .orElseThrow();
    assertThat(item.replaces()).isEqualTo(written.urn(first.fact()));
  }

  @Test
  void manualAsksForEveryCandidateAndFullAutoAdoptsEvery() {
    var s = scenario();
    setPolicy(s, Preset.MANUAL);
    var c = s.begin();
    var orders = declaredDataset(c, "shop.public.orders");
    var written = s.judge(c);
    assertThat(s.state(written.urn(orders.existence().fact()))).contains(FactState.CANDIDATE);
    assertThat(reviewedFacts(s)).hasSize(2);

    setPolicy(s, Preset.FULL_AUTO);
    c = s.begin();
    var customers = declaredDataset(c, "shop.public.customers");
    var guessed =
        fact(
            c,
            c.address(customers.dataset(), "row-kind"),
            "current state",
            List.of(supported(ValidationMethod.INFERENCE, "current state")),
            customers.existence().on());
    var later = s.judge(c);
    assertThat(s.state(later.urn(guessed.fact()))).contains(FactState.CONFIRMED);
  }

  @Test
  void anAdoptionPendingAPremiseTakesEffectWhenThePremiseIsConfirmed() {
    var s = scenario();
    var c = s.begin();
    var measure =
        c.add(new LogRecord.Element(new ElementIdentity.Unique(ElementKind.MEASURE, "ec-sales")));
    var existence =
        fact(
            c,
            c.address(measure, Vocabulary.EXISTENCE),
            "exists",
            List.of(check(ValidationMethod.DOCUMENT, "exists", CheckResult.REFUTED)));
    var rule =
        fact(
            c,
            c.address(measure, "aggregation-rule"),
            "sum(amount)",
            List.of(supported(ValidationMethod.DECLARED_ENFORCED, "sum(amount)")),
            existence.on());
    var written = s.judge(c);
    // The existence awaits consent, so the rule is evaluated now and adopted pending it.
    assertThat(s.state(written.urn(rule.fact()))).contains(FactState.CANDIDATE);
    var waiting =
        (Knowledge.AwaitingJudgement)
            s.only(s.lastCommit(), written.urn(measure), "aggregation-rule");
    assertThat(waiting.candidates().getFirst().waiting()).isEqualTo(Knowledge.Waiting.DEPENDENCIES);

    var outcome =
        Judgement.submit(s, Judgement.answer(written.urn(existence.fact()), AnswerType.CONSENT));
    assertThat(outcome.cascaded().confirmed()).containsExactly(written.urn(rule.fact()));
    assertThat(s.revision(written.urn(rule.fact())).confirmationPath())
        .isEqualTo(ConfirmationPath.MACHINE_JUDGEMENT);
  }

  @Test
  void retirementProposalsAreEvaluatedAndRetirementCascades() {
    var s = scenario();
    var c = s.begin();
    var orders = declaredDataset(c, "shop.public.orders");
    var key =
        fact(
            c,
            c.address(orders.dataset(), "key"),
            "order_id",
            List.of(supported(ValidationMethod.DECLARED_ENFORCED, "order_id")),
            orders.existence().on());
    var guess =
        fact(
            c,
            c.address(orders.dataset(), "row-kind"),
            "event",
            List.of(supported(ValidationMethod.INFERENCE, "event")),
            orders.existence().on());
    var written = s.judge(c);
    var existence = written.urn(orders.existence().fact());

    // A proposal against a candidate rejects it instead.
    c = s.begin();
    Judgement.proposeRetire(c, written.ref(guess.fact()));
    s.judge(c);
    assertThat(s.state(written.urn(guess.fact()))).contains(FactState.REJECTED);

    // A table that no longer exists, as the source declares, retires its Dataset inline, and the
    // retirement cascades to what was confirmed resting on it.
    c = s.begin();
    Judgement.proposeRetire(
        c,
        written.ref(orders.existence().fact()),
        supported(ValidationMethod.DECLARED_ENFORCED, "gone"));
    var retired = s.judge(c);
    assertThat(s.state(existence)).contains(FactState.RETIRED);
    assertThat(s.state(written.urn(key.fact()))).contains(FactState.RETIRED);
    assertThat(retired.transitions().retired()).containsExactly(existence, written.urn(key.fact()));
  }

  @Test
  void retirementCascadesAlongTheDependenciesAFactWasConfirmedWith() {
    var s = scenario();
    var c = s.begin();
    var orders = declaredDataset(c, "shop.public.orders");
    var kind =
        fact(
            c,
            c.address(orders.dataset(), "row-kind"),
            "event",
            List.of(supported(ValidationMethod.DECLARED_ENFORCED, "event")),
            orders.existence().on());
    var grain =
        fact(
            c,
            c.address(orders.dataset(), "row-grain"),
            "order line",
            List.of(supported(ValidationMethod.DECLARED_ENFORCED, "order line")),
            orders.existence().on(),
            kind.on());
    var written = s.judge(c);
    var grainFact = written.urn(grain.fact());
    assertThat(s.state(grainFact)).contains(FactState.CONFIRMED);

    // A later acceptance drops the row kind, but the grain was confirmed resting on it.
    c = s.begin();
    c.add(
        new LogRecord.Accept(
            written.ref(grain.fact()),
            List.of(s.on(written.urn(orders.existence().fact()))),
            List.of(supported(ValidationMethod.DECLARED_ENFORCED, "order line")),
            Scenario.NOTHING,
            Scenario.NOTHING));
    s.judge(c);
    c = s.begin();
    Judgement.proposeRetire(
        c, written.ref(kind.fact()), supported(ValidationMethod.DECLARED_ENFORCED, "gone"));
    var retired = s.judge(c);
    assertThat(retired.transitions().retired())
        .containsExactly(written.urn(kind.fact()), grainFact);
  }

  static List<String> reviewedFacts(Scenario s) {
    return s.latest(snapshot -> snapshot.reviewList(Review.Query.ALL)).stream()
        .flatMap(group -> group.items().stream())
        .map(
            item ->
                switch (item) {
                  case Review.Candidate candidate -> candidate.fact().revision().fact();
                  case Review.Retirement retirement -> retirement.fact().revision().fact();
                  case Review.Conflict conflict -> conflict.target().factAddress();
                })
        .toList();
  }
}
