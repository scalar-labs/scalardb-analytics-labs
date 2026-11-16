/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import static com.scalar.db.analytics.labs.semantic.Judgement.answer;
import static com.scalar.db.analytics.labs.semantic.Judgement.declaredDataset;
import static com.scalar.db.analytics.labs.semantic.Judgement.fact;
import static com.scalar.db.analytics.labs.semantic.Judgement.setPolicy;
import static com.scalar.db.analytics.labs.semantic.Judgement.submit;
import static com.scalar.db.analytics.labs.semantic.Judgement.supported;
import static com.scalar.db.analytics.labs.semantic.PolicyEvaluationTest.reviewedFacts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.analytics.labs.semantic.model.CommitRejectedException;
import com.scalar.db.analytics.labs.semantic.model.Review;
import com.scalar.db.analytics.labs.semantic.model.ReviewPresentation;
import com.scalar.db.analytics.labs.semantic.model.SemanticCatalogException;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.AnswerType;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ConfirmationPath;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Preset;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Transition;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ValidationMethod;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The deciding party's side of construction: the review list, review presentations, answers in one
 * submission, the cascades they cause, and conflict resolution.
 */
class ReviewAndAnswersTest extends StoreScenarioTest {
  /** Under {@code manual}, a Dataset and a uniqueness resting on it, accepted together. */
  private record Orders(String existence, String reference, String uniqueness) {}

  private static Orders manualOrders(Scenario s) {
    setPolicy(s, Preset.MANUAL);
    var c = s.begin();
    var orders = declaredDataset(c, "shop.public.orders");
    var unique =
        fact(
            c,
            c.address(orders.dataset(), "uniqueness"),
            "does not repeat",
            List.of(supported(ValidationMethod.DECLARED_ENFORCED, "does not repeat")),
            orders.existence().on());
    var written = s.judge(c);
    return new Orders(
        written.urn(orders.existence().fact()),
        written.urn(orders.reference().fact()),
        written.urn(unique.fact()));
  }

  @Test
  void aCandidateIsListedWithThePremisesItRestsOnAndBothAreConsentedToAtOnce() {
    var s = scenario();
    var orders = manualOrders(s);

    var groups = s.latest(snapshot -> snapshot.reviewList(Review.Query.ALL));
    assertThat(groups).extracting(Review.Group::table).containsExactly("shop.public.orders");
    var items = groups.getFirst().items();
    assertThat(items).hasSize(3);
    var uniqueness = candidate(items, orders.uniqueness());
    var existence = candidate(items, orders.existence());
    assertThat(uniqueness.premiseItems()).containsExactly(orders.existence());
    assertThat(uniqueness.premises())
        .extracting(premise -> premise.revision().fact())
        .containsExactly(orders.existence());
    assertThat(existence.premiseItems()).isEmpty();
    assertThat(existence.cascadesTo()).containsExactly(orders.uniqueness());
    assertThat(uniqueness.target().physicalCatalogReference()).isEqualTo("shop.public.orders");
    assertThat(uniqueness.needArose()).isEqualTo(s.lastCommit());
    assertThat(uniqueness.evaluation().record().result())
        .isEqualTo(Vocabulary.PolicyResult.REQUEST_CONSENT);

    // Submitted dependent first, the answers still apply premise first, in one commit.
    var before = s.lastCommit();
    var outcome =
        submit(
            s,
            answer(orders.uniqueness(), AnswerType.CONSENT),
            answer(orders.existence(), AnswerType.CONSENT));
    assertThat(s.lastCommit()).isEqualTo(before + 1);
    assertThat(outcome.outcomes())
        .extracting(Answers.Outcome::result)
        .containsExactly(Answers.Result.CONFIRMED, Answers.Result.CONFIRMED);
    assertThat(s.state(orders.uniqueness())).contains(FactState.CONFIRMED);
    assertThat(s.revision(orders.uniqueness()).confirmationPath())
        .isEqualTo(ConfirmationPath.HUMAN_CONSENT);
    assertThat(reviewedFacts(s)).containsExactly(orders.reference());
  }

  @Test
  void aConsentWhileAPremiseAwaitsConsentWaitsForIt() {
    var s = scenario();
    var orders = manualOrders(s);
    var early = submit(s, answer(orders.uniqueness(), AnswerType.CONSENT));
    assertThat(early.outcomes().getFirst().result())
        .isEqualTo(Answers.Result.UNCONFIRMED_DEPENDENCIES);
    assertThat(s.state(orders.uniqueness())).contains(FactState.CANDIDATE);

    var later = submit(s, answer(orders.existence(), AnswerType.CONSENT));
    assertThat(later.cascaded().confirmed()).containsExactly(orders.uniqueness());
  }

  @Test
  void rejectingOrCorrectingAPremiseRejectsWhatRestsOnIt() {
    var s = scenario();
    var orders = manualOrders(s);
    var outcome = submit(s, answer(orders.existence(), AnswerType.CORRECTION));
    var first = outcome.outcomes().getFirst();
    assertThat(first.result()).isEqualTo(Answers.Result.REJECTED);
    assertThat(outcome.cascaded().rejected()).containsExactly(orders.uniqueness());
    assertThat(s.state(orders.uniqueness())).contains(FactState.REJECTED);

    // The correction's wording is registered as usage feedback, a new Input Resource.
    var feedback = java.util.Objects.requireNonNull(first.feedback());
    assertThat(Urns.kind(feedback)).isEqualTo(Urns.INPUT_RESOURCE);
  }

  @Test
  void anUnknownAnswerLeavesTheCandidateOutOfReviewUntilNewInputArrives() {
    var s = scenario();
    var orders = manualOrders(s);
    var outcome = submit(s, answer(orders.reference(), AnswerType.UNKNOWN));
    assertThat(outcome.outcomes().getFirst().result()).isEqualTo(Answers.Result.UNKNOWN);
    assertThat(s.state(orders.reference())).contains(FactState.CANDIDATE);
    assertThat(reviewedFacts(s)).doesNotContain(orders.reference());
  }

  @Test
  void answersToARetirementProposalRetireOrKeepTheFact() {
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
    var row =
        fact(
            c,
            c.address(orders.dataset(), "row-kind"),
            "event",
            List.of(supported(ValidationMethod.DECLARED_ENFORCED, "event")),
            orders.existence().on());
    var written = s.judge(c);
    setPolicy(s, Preset.MANUAL);
    c = s.begin();
    Judgement.proposeRetire(c, written.ref(key.fact()));
    Judgement.proposeRetire(c, written.ref(row.fact()));
    s.judge(c);
    assertThat(reviewedFacts(s))
        .containsExactlyInAnyOrder(written.urn(key.fact()), written.urn(row.fact()));

    var outcome =
        submit(
            s,
            answer(written.urn(key.fact()), Transition.RETIRE, AnswerType.CONSENT, null),
            answer(written.urn(row.fact()), Transition.RETIRE, AnswerType.REJECTION, null));
    assertThat(outcome.outcomes())
        .extracting(Answers.Outcome::result)
        .containsExactly(Answers.Result.RETIRED, Answers.Result.RETIREMENT_DECLINED);
    assertThat(s.state(written.urn(key.fact()))).contains(FactState.RETIRED);
    assertThat(s.state(written.urn(row.fact()))).contains(FactState.CONFIRMED);

    // An answer to a Fact another path already judged is recorded without a transition.
    var late =
        submit(
            s,
            answer(written.urn(row.fact()), AnswerType.CONSENT),
            answer(written.urn(key.fact()), Transition.RETIRE, AnswerType.REJECTION, null));
    assertThat(late.outcomes())
        .extracting(Answers.Outcome::result)
        .containsExactly(Answers.Result.ALREADY_JUDGED, Answers.Result.ALREADY_JUDGED);
  }

  @Test
  void aSubmissionWithoutAnAuthorityCheckWritesNothing() {
    var s = scenario();
    var orders = manualOrders(s);
    var before = s.lastCommit();
    assertThatThrownBy(
            () ->
                store.submitAnswers(
                    s.domain,
                    new Answers.Submission(
                        "owner", " ", List.of(answer(orders.existence(), AnswerType.CONSENT)))))
        .isInstanceOf(SemanticCatalogException.class)
        .hasMessageContaining("authority-check");
    assertThat(s.lastCommit()).isEqualTo(before);
  }

  @Test
  void aConflictIsListedAndResolvedByChoosingAnEligibleFact() {
    var s = scenario();
    setPolicy(s, Preset.FULL_AUTO);
    var c = s.begin();
    var orders = declaredDataset(c, "shop.public.orders");
    var kind = c.address(orders.dataset(), "row-kind");
    var event =
        fact(
            c,
            kind,
            "event",
            List.of(supported(ValidationMethod.INFERENCE, "event")),
            orders.existence().on());
    var state =
        fact(
            c,
            kind,
            "state",
            List.of(supported(ValidationMethod.INFERENCE, "state")),
            orders.existence().on());
    var written = s.judge(c);
    var address = written.urn(kind);

    var conflict =
        (Review.Conflict)
            s.latest(snapshot -> snapshot.reviewList(Review.Query.ALL)).getFirst().items().stream()
                .filter(item -> item instanceof Review.Conflict)
                .findFirst()
                .orElseThrow();
    assertThat(conflict.target().factAddress()).isEqualTo(address);
    assertThat(conflict.eligible()).hasSize(2);

    var presentation =
        store.registerReviewPresentation(
            s.domain,
            new ReviewPresentation(
                ReviewPresentation.Kind.CONFLICT,
                List.of(
                    new ReviewPresentation.Target(written.urn(event.fact()), address),
                    new ReviewPresentation.Target(written.urn(state.fact()), address)),
                "owner",
                "2026-11-14T00:00:00Z",
                "comparison table",
                List.of()));
    assertThatThrownBy(
            () ->
                store.resolveConflict(
                    s.domain,
                    new ConflictResolution(
                        address,
                        written.urn(event.fact()),
                        List.of(),
                        "owner",
                        "authority-check",
                        "an order line is an event",
                        presentation)))
        .isInstanceOf(CommitRejectedException.class);
    var resolved =
        store.resolveConflict(
            s.domain,
            new ConflictResolution(
                address,
                written.urn(event.fact()),
                List.of(written.urn(state.fact())),
                "owner",
                "authority-check",
                "an order line is an event",
                presentation));
    assertThat(resolved.commit()).isPresent();
    assertThat(reviewedFacts(s)).doesNotContain(address);
    assertThat(store.reviewPresentation(s.domain, presentation).orElseThrow().resultingEvents())
        .hasSize(1);
  }

  @Test
  void presentationsRecordTheirAnswersAndShowOnLaterItems() {
    var s = scenario();
    var orders = manualOrders(s);
    var address =
        s.latest(snapshot -> snapshot.latestRevision(orders.reference()).orElseThrow())
            .factAddress();
    var presentation =
        store.registerReviewPresentation(
            s.domain,
            new ReviewPresentation(
                ReviewPresentation.Kind.CONFIRMATION,
                List.of(new ReviewPresentation.Target(orders.reference(), address)),
                "owner",
                "2026-11-14T00:00:00Z",
                "agent session",
                List.of()));
    var item =
        candidate(
            s.latest(snapshot -> snapshot.reviewList(Review.Query.ALL)).getFirst().items(),
            orders.reference());
    assertThat(item.earlierPresentations()).containsExactly(presentation);

    var outcome =
        submit(s, answer(orders.reference(), Transition.CONFIRM, AnswerType.CONSENT, presentation));
    assertThat(store.reviewPresentation(s.domain, presentation).orElseThrow().resultingEvents())
        .containsExactly(outcome.outcomes().getFirst().answer());

    assertThatThrownBy(
            () ->
                submit(s, answer(orders.existence(), Transition.CONFIRM, AnswerType.CONSENT, 999L)))
        .isInstanceOf(SemanticCatalogException.class)
        .hasMessageContaining("Unknown review presentation");
  }

  @Test
  void theReviewListIsReadBelowAPositionAndNarrowedByKind() {
    var s = scenario();
    setPolicy(s, Preset.MANUAL);
    var c = s.begin();
    declaredDataset(c, "shop.public.orders");
    declaredDataset(c, "pos.public.purchases");
    s.judge(c);
    var shop =
        s.latest(
            snapshot ->
                snapshot.reviewList(
                    new Review.Query(new Review.Position("shop", null, null), null, null, null)));
    assertThat(shop).extracting(Review.Group::table).containsExactly("shop.public.orders");
    var table =
        s.latest(
            snapshot ->
                snapshot.reviewList(
                    new Review.Query(
                        new Review.Position("pos", "public", "purchases"), null, null, null)));
    assertThat(table).extracting(Review.Group::table).containsExactly("pos.public.purchases");
    var conflicts =
        s.latest(
            snapshot ->
                snapshot.reviewList(
                    new Review.Query(
                        Review.Position.ROOT, ReviewPresentation.Kind.CONFLICT, null, null)));
    assertThat(conflicts).isEmpty();
  }

  private static Review.Candidate candidate(List<Review.Item> items, String fact) {
    return items.stream()
        .filter(item -> item instanceof Review.Candidate)
        .map(Review.Candidate.class::cast)
        .filter(item -> item.fact().revision().fact().equals(fact))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No item for " + fact + " in " + items));
  }
}
