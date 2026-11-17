/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.scalar.db.analytics.labs.semantic.model.Check;
import com.scalar.db.analytics.labs.semantic.model.Dependency;
import com.scalar.db.analytics.labs.semantic.model.ElementIdentity;
import com.scalar.db.analytics.labs.semantic.model.Hierarchy;
import com.scalar.db.analytics.labs.semantic.model.Knowledge;
import com.scalar.db.analytics.labs.semantic.model.KnowledgeRequest;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.MatchingCondition;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Review;
import com.scalar.db.analytics.labs.semantic.model.Scope;
import com.scalar.db.analytics.labs.semantic.model.UsageFeedback;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.AnswerType;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.CheckResult;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.PolicyResult;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.SessionPurpose;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Transition;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ValidationMethod;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * The reads a Query Agent composes queries from: reading along the hierarchy, requesting all
 * predicates of an element, expanded Fact Revision content, and finding elements by a business
 * term's written form.
 */
class AgentReadsTest extends StoreScenarioTest {
  /**
   * Two tables, orders and customers, an identifier correspondence from an order's customer ID to
   * the customer ID, a Measure based on orders whose aggregation rule awaits consent, a concept,
   * and a business term referring to the Measure.
   */
  private record Sample(
      Scenario s,
      String orders,
      String customerId,
      String customers,
      String id,
      String correspondence,
      String measure,
      String concept,
      String term) {}

  private Sample sample() {
    var s = scenario();
    var c = s.begin();
    var orders = confirmedDataset(c, "shop.public.orders");
    var customerId = confirmedField(c, orders, "shop.public.orders.customer_id");
    var customers = confirmedDataset(c, "shop.public.customers");
    var id = confirmedField(c, customers, "shop.public.customers.id");
    var correspondence =
        confirmedNode(
            c,
            new ElementIdentity.IdentifierCorrespondence(
                MatchingCondition.direct(MatchingCondition.Pair.of(customerId, id))));
    var measure = confirmedNode(c, new ElementIdentity.Unique(ElementKind.MEASURE, "ec-sales"));
    var concept = confirmedNode(c, new ElementIdentity.Unique(ElementKind.CONCEPT, "customer"));
    var term = confirmedNode(c, new ElementIdentity.Unique(ElementKind.BUSINESS_TERM, "sales"));
    var written = s.write(c);

    c = s.begin();
    var measureRef = written.ref(measure);
    var base =
        c.closedRangeDependency(
            c.address(measureRef, Vocabulary.BASE_DATASET), written.urn(orders));
    c.confirmByMachine(base.fact());
    var rule =
        c.aggregationRule(
            c.address(measureRef, Vocabulary.AGGREGATION_RULE),
            "count-distinct",
            "customer_id",
            base);
    c.evaluate(rule, Transition.CONFIRM, PolicyResult.REQUEST_CONSENT);
    var termRef = written.ref(term);
    c.confirmByMachine(c.closedRangeCandidate(c.address(termRef, Vocabulary.WRITTEN_FORM), "売上"));
    c.confirmByMachine(
        c.closedRangeCandidate(
            c.address(termRef, Vocabulary.REFERENCED_ELEMENT), written.urn(measure)));
    s.write(c);
    return new Sample(
        s,
        written.urn(orders),
        written.urn(customerId),
        written.urn(customers),
        written.urn(id),
        written.urn(correspondence),
        written.urn(measure),
        written.urn(concept),
        written.urn(term));
  }

  @Test
  void aTableIsReadAsOneUnitWithItsMeasureAndCorrespondence() {
    var sample = sample();
    var level =
        sample.s.latest(
            snapshot ->
                snapshot.read(
                    new Review.Position("shop", "public", "orders"), Hierarchy.Depth.CHILDREN));

    var table = (Hierarchy.Table) level;
    assertThat(table.canonical()).isEqualTo("shop.public.orders");
    var unit = Objects.requireNonNull(table.unit());
    assertThat(unit.dataset().element().element()).isEqualTo(sample.orders);
    assertThat(unit.fields())
        .extracting(e -> e.element().element())
        .containsExactly(sample.customerId);
    assertThat(unit.measures())
        .extracting(e -> e.element().element())
        .containsExactly(sample.measure);
    assertThat(unit.correspondences())
        .extracting(e -> e.element().element())
        .containsExactly(sample.correspondence);
    // Existence of the Dataset, Field, Measure, and correspondence, and the base Dataset.
    // Missing: the Dataset's 5 row and key predicates, the Field's 6 meaning predicates, the
    // Measure's 4 without a Fact, and the correspondence's 2.
    assertThat(table.counts()).isEqualTo(new Hierarchy.Counts(5, 1, 17));
  }

  @Test
  void aRecursiveRootReadReturnsTheWholeDomainAndItsCounts() {
    var sample = sample();
    var root =
        (Hierarchy.Root)
            sample.s.latest(
                snapshot -> snapshot.read(Review.Position.ROOT, Hierarchy.Depth.RECURSIVE));

    var dataSource = root.dataSources().getFirst();
    assertThat(root.dataSources())
        .singleElement()
        .extracting(Hierarchy.DataSource::registration)
        .isEqualTo("shop");
    var namespace = dataSource.namespaces().getFirst();
    assertThat(namespace.namespace()).isEqualTo("public");
    assertThat(namespace.tables())
        .extracting(Hierarchy.Table::canonical)
        .containsExactly("shop.public.customers", "shop.public.orders");
    assertThat(namespace.tables()).allSatisfy(table -> assertThat(table.unit()).isNotNull());
    var customers = namespace.tables().getFirst();
    var customersUnit = Objects.requireNonNull(customers.unit());
    assertThat(customersUnit.correspondences())
        .extracting(e -> e.element().element())
        .containsExactly(sample.correspondence);
    assertThat(customersUnit.measures()).isEmpty();
    assertThat(customers.counts()).isEqualTo(new Hierarchy.Counts(3, 0, 13));
    // The correspondence is in both tables but counted once above them.
    assertThat(namespace.counts()).isEqualTo(new Hierarchy.Counts(7, 1, 28));
    assertThat(dataSource.counts()).isEqualTo(namespace.counts());

    assertThat(root.domainWide())
        .extracting(e -> e.element().element())
        .containsExactlyInAnyOrder(sample.concept, sample.term);
    // The concept adds its existence and its missing meaning; the business term its three Facts.
    assertThat(root.counts()).isEqualTo(new Hierarchy.Counts(11, 1, 29));
  }

  @Test
  void aReadReturnsConfirmedKnowledgeUnlessUnconfirmedKnowledgeIsAskedFor() throws Exception {
    var sample = sample();
    var position = new Review.Position("shop", "public", "orders");
    var confirmed =
        (Hierarchy.Table)
            sample.s.latest(snapshot -> snapshot.read(position, Hierarchy.Depth.CHILDREN));
    var withUnconfirmed =
        (Hierarchy.Table)
            sample.s.latest(
                snapshot ->
                    snapshot.read(
                        position, Hierarchy.Depth.CHILDREN, Knowledge.View.WITH_UNCONFIRMED));

    var measure = Objects.requireNonNull(confirmed.unit()).measures().getFirst().predicates();
    // The aggregation rule awaits consent, so only the confirmed base Dataset and existence show.
    assertThat(measure.addresses())
        .extracting(Knowledge.AtAddress::predicate)
        .containsExactlyInAnyOrder(Vocabulary.BASE_DATASET, Vocabulary.EXISTENCE);
    assertThat(measure.addresses())
        .allSatisfy(at -> assertThat(at.knowledge()).isInstanceOf(Knowledge.Selected.class));
    var all = Objects.requireNonNull(withUnconfirmed.unit()).measures().getFirst().predicates();
    assertThat(all.addresses())
        .filteredOn(at -> at.predicate().equals(Vocabulary.AGGREGATION_RULE))
        .singleElement()
        .satisfies(
            at -> assertThat(at.knowledge()).isInstanceOf(Knowledge.AwaitingJudgement.class));
    // Missing knowledge and counts do not depend on the view.
    assertThat(all.missing()).isEqualTo(measure.missing());
    assertThat(withUnconfirmed.counts()).isEqualTo(confirmed.counts());

    // The JSON form names each status instead of leaving it to the fields present.
    var json = new ObjectMapper().valueToTree(all);
    assertThat(json.findValuesAsText("status"))
        .containsExactlyInAnyOrder("awaiting-judgement", "selected", "selected");
  }

  @Test
  void aConflictWithASelectedFactIsConfirmedKnowledgeAndOneWithoutIsNot() {
    var s = scenario();
    var c = s.begin();
    var orders = confirmedDataset(c, "shop.public.orders");
    var unit = c.address(orders, Vocabulary.UNIT);
    c.confirmByMachine(c.candidate(unit, "yen"));
    c.confirmByMachine(c.candidate(unit, "dollars"));
    var element = s.write(c).urn(orders);

    var unselected =
        s.latest(snapshot -> snapshot.request(List.of(KnowledgeRequest.of(element, "unit"))))
            .getFirst();
    assertThat(unselected.addresses()).isEmpty();
    // A conflict is not missing knowledge: confirmed Facts exist, but none is chosen yet.
    assertThat(unselected.missing()).isEmpty();
    var shown =
        s.latest(
                snapshot ->
                    snapshot.request(
                        List.of(KnowledgeRequest.of(element, "unit")),
                        Knowledge.View.WITH_UNCONFIRMED))
            .getFirst();
    assertThat(shown.addresses())
        .singleElement()
        .satisfies(at -> assertThat(at.knowledge()).isInstanceOf(Knowledge.Conflict.class));
  }

  @Test
  void aReadOfTheChildrenOnlyGivesTheirCountsWithoutTheirContent() {
    var sample = sample();
    var root =
        (Hierarchy.Root)
            sample.s.latest(
                snapshot -> snapshot.read(Review.Position.ROOT, Hierarchy.Depth.CHILDREN));
    assertThat(root.dataSources())
        .singleElement()
        .satisfies(
            dataSource -> {
              assertThat(dataSource.namespaces()).isEmpty();
              assertThat(dataSource.counts()).isEqualTo(new Hierarchy.Counts(7, 1, 28));
            });

    var namespace =
        (Hierarchy.Namespace)
            sample.s.latest(
                snapshot ->
                    snapshot.read(
                        new Review.Position("shop", "public", null), Hierarchy.Depth.CHILDREN));
    assertThat(namespace.tables())
        .hasSize(2)
        .allSatisfy(table -> assertThat(table.unit()).isNull());
  }

  @Test
  void aTableWhoseDatasetAwaitsConsentIsCountedByItsReviewItems() {
    var s = scenario();
    var c = s.begin();
    var dataset = (Ref.Temp) c.dataset("shop.public.new_table");
    c.evaluate(
        new Ref.Temp(dataset.number() + 2), Transition.CONFIRM, PolicyResult.REQUEST_CONSENT);
    s.write(c);

    List<Review.Group> groups = s.latest(snapshot -> snapshot.reviewList(Review.Query.ALL));
    assertThat(groups).extracting(Review.Group::table).containsExactly("shop.public.new_table");
    var root =
        (Hierarchy.Root)
            s.latest(snapshot -> snapshot.read(Review.Position.ROOT, Hierarchy.Depth.RECURSIVE));
    assertThat(root.counts().awaitingReview()).isEqualTo(1);
    var table =
        (Hierarchy.Table)
            s.latest(
                snapshot ->
                    snapshot.read(
                        new Review.Position("shop", "public", "new_table"),
                        Hierarchy.Depth.CHILDREN));
    assertThat(table.counts()).isEqualTo(new Hierarchy.Counts(0, 1, 0));
    assertThat(table.unit()).isNull();
  }

  @Test
  void aStringInInputsReadThatOnlyLooksLikeAUrnIsNotExpanded() {
    var s = scenario();
    var c = s.begin();
    var orders = confirmedDataset(c, "shop.public.orders");
    var written = s.write(c);

    c = s.begin();
    var inputsRead = JsonNodeFactory.instance.objectNode();
    inputsRead.put("short", "urn:scalardb:analytics:broken");
    inputsRead.put(
        "revision", "urn:scalardb:analytics:" + s.domain + ":fact-revision:no-event-here");
    inputsRead.put("record", "urn:scalardb:analytics:" + s.domain + ":log-record:not-a-position");
    var kind =
        c.add(
            new LogRecord.Fact(
                c.address(written.ref(orders), Vocabulary.ROW_KIND),
                JsonNodeFactory.instance.textNode("event"),
                null));
    c.add(new LogRecord.Accept(kind, List.of(), List.of(), inputsRead, Scenario.NOTHING));
    var revision = s.revision(s.write(c).urn(kind)).urn();

    var content = s.latest(snapshot -> snapshot.revisionContent(revision)).orElseThrow();
    assertThat(content.referenced()).isEmpty();
  }

  @Test
  void theReviewListPlacesAMeasureUnderItsBaseTable() {
    var sample = sample();
    List<Review.Group> groups = sample.s.latest(snapshot -> snapshot.reviewList(Review.Query.ALL));
    assertThat(groups)
        .singleElement()
        .satisfies(
            group -> {
              assertThat(group.table()).isEqualTo("shop.public.orders");
              assertThat(group.items())
                  .singleElement()
                  .satisfies(item -> assertThat(item.target().element()).isEqualTo(sample.measure));
            });
  }

  @Test
  void aBusinessTermsWrittenFormFindsTheElementItRefersTo() {
    var sample = sample();
    List<Knowledge.Element> found =
        sample.s.latest(snapshot -> snapshot.findElements(null, null, "売上"));
    assertThat(found).extracting(Knowledge.Element::element).containsExactly(sample.measure);
    List<Knowledge.Element> none =
        sample.s.latest(snapshot -> snapshot.findElements(null, null, "sales"));
    assertThat(none).isEmpty();
  }

  @Test
  void aReadAtAPastPointReturnsWhatItReturnedAtThatCommit() {
    var sample = sample();
    var point = sample.s.lastCommit();
    Hierarchy.Level then =
        sample.s.at(
            point, snapshot -> snapshot.read(Review.Position.ROOT, Hierarchy.Depth.RECURSIVE));

    var c = sample.s.begin();
    c.confirmByMachine(
        c.candidate(c.address(Ref.urn(sample.orders), Vocabulary.ROW_KIND), "event"));
    sample.s.write(c);

    Hierarchy.Level again =
        sample.s.at(
            point, snapshot -> snapshot.read(Review.Position.ROOT, Hierarchy.Depth.RECURSIVE));
    assertThat(again).isEqualTo(then);
    Hierarchy.Level now =
        sample.s.latest(snapshot -> snapshot.read(Review.Position.ROOT, Hierarchy.Depth.RECURSIVE));
    assertThat(now.counts()).isEqualTo(new Hierarchy.Counts(12, 1, 28));
  }

  @Test
  void allPredicatesOfAnElementShowMissingCoreKnowledgeAndFreeDescriptions() {
    var s = scenario();
    var c = s.begin();
    var orders = confirmedDataset(c, "shop.public.orders");
    c.confirmByMachine(c.candidate(c.address(orders, Vocabulary.ROW_KIND), "event"));
    var summable =
        c.add(
            new LogRecord.FactAddress(
                orders,
                "summable-across-days",
                Scope.EMPTY,
                "Whether order counts may be summed across days"));
    c.candidate(summable, "yes");
    var written = s.write(c);
    var element = written.urn(orders);

    var answer =
        s.latest(
                snapshot ->
                    snapshot.request(
                        List.of(KnowledgeRequest.all(element)), Knowledge.View.WITH_UNCONFIRMED))
            .getFirst();
    assertThat(answer.missing())
        .extracting(Knowledge.Missing::predicate)
        .containsExactly(
            Vocabulary.ROW_CONCEPT, Vocabulary.ROW_GRAIN, Vocabulary.ROW_INCLUSION, Vocabulary.KEY);
    assertThat(answer.addresses())
        .extracting(Knowledge.AtAddress::predicate)
        .contains(Vocabulary.EXISTENCE, Vocabulary.PHYSICAL_CATALOG_REFERENCE, Vocabulary.ROW_KIND);
    assertThat(answer.addresses())
        .filteredOn(at -> at.predicateDefinition() != null)
        .singleElement()
        .satisfies(
            at -> {
              assertThat(at.predicate()).isEqualTo("summable-across-days");
              assertThat(at.knowledge()).isInstanceOf(Knowledge.AwaitingJudgement.class);
            });

    // A request by name still leaves the free-description predicate out.
    var named =
        s.latest(
                snapshot ->
                    snapshot.request(List.of(KnowledgeRequest.of(element, "summable-across-days"))))
            .getFirst();
    assertThat(named.addresses()).isEmpty();
    assertThat(named.missing())
        .containsExactly(new Knowledge.Missing(element, "summable-across-days"));
  }

  @Test
  void aCorrectedCandidateShowsTheFeedbackAndTheRejectedFactWithItsAnswer() {
    var s = scenario();
    var c = s.begin();
    var orders = confirmedDataset(c, "shop.public.orders");
    var rowKind = c.address(orders, Vocabulary.ROW_KIND);
    var snapshotKind = c.candidate(rowKind, "snapshot");
    var correction =
        c.add(
            new LogRecord.Answer(
                snapshotKind,
                Transition.CONFIRM,
                AnswerType.CORRECTION,
                "owner",
                "authority-check",
                "each row is an order event",
                null));
    c.add(new LogRecord.Reject(snapshotKind, new LogRecord.Basis.Event(correction)));
    var feedback = c.add(LogRecord.InputResource.usageFeedback());
    var observation =
        c.add(
            LogRecord.InputObservation.of(
                feedback,
                new UsageFeedback(
                    "each row is an order event",
                    SessionPurpose.CONSTRUCTING_THE_CATALOG,
                    "row kind of orders presented as snapshot",
                    List.of())));
    var written = s.write(c);

    c = s.begin();
    var inputsRead = JsonNodeFactory.instance.objectNode();
    inputsRead.putArray("rejected").add(written.urn(snapshotKind));
    inputsRead.put("answer", written.urn(correction));
    var eventKind =
        c.add(
            new LogRecord.Fact(
                written.ref(rowKind), JsonNodeFactory.instance.textNode("event"), null));
    c.add(
        new LogRecord.Accept(
            eventKind,
            List.of(new Dependency.OnInput(written.ref(feedback), written.ref(observation))),
            List.of(),
            inputsRead,
            Scenario.NOTHING));
    var corrected = s.write(c);
    var revision = s.revision(corrected.urn(eventKind)).urn();

    var content = s.latest(snapshot -> snapshot.revisionContent(revision)).orElseThrow();
    assertThat(content.revision().inputsRead()).isEqualTo(inputsRead);
    // Dependencies come first; the order within inputs read is that of the stored JSON.
    assertThat(content.referenced().getFirst().urn()).isEqualTo(written.urn(observation));
    assertThat(content.referenced())
        .extracting(Knowledge.Referenced::urn)
        .containsExactlyInAnyOrder(
            written.urn(observation), written.urn(snapshotKind), written.urn(correction));
    var said = (Knowledge.Referenced.Observation) content.referenced().getFirst();
    assertThat(said.content().path("content").asText()).isEqualTo("each row is an order event");
    var rejected = only(content.referenced(), Knowledge.Referenced.Revision.class);
    assertThat(rejected.revision().value().asText()).isEqualTo("snapshot");
    assertThat(rejected.stateAtPoint()).isEqualTo(FactState.REJECTED);
    assertThat(rejected.answers())
        .singleElement()
        .satisfies(
            answer -> {
              assertThat(answer.answerType()).isEqualTo(AnswerType.CORRECTION);
              assertThat(answer.wording()).isEqualTo("each row is an order event");
              assertThat(answer.decidedBy()).isEqualTo("owner");
            });
    var answered = only(content.referenced(), Knowledge.Referenced.Answer.class);
    assertThat(answered.answer().logRecord()).isEqualTo(written.urn(correction));
  }

  private static <T extends Knowledge.Referenced> T only(
      List<Knowledge.Referenced> referenced, Class<T> type) {
    var found = referenced.stream().filter(type::isInstance).map(type::cast).toList();
    assertThat(found).hasSize(1);
    return found.getFirst();
  }

  @Test
  void validationItemsNoSupportingCheckExaminedAreListed() {
    var s = scenario();
    var c = s.begin();
    var lines = confirmedDataset(c, "shop.public.order_lines");
    var orders = confirmedDataset(c, "shop.public.orders");
    var lineShop = confirmedField(c, lines, "shop.public.order_lines.shop_id");
    var lineOrder = confirmedField(c, lines, "shop.public.order_lines.order_no");
    var shop = confirmedField(c, orders, "shop.public.orders.shop_id");
    var order = confirmedField(c, orders, "shop.public.orders.order_no");
    var written = s.write(c);

    c = s.begin();
    var correspondence =
        c.add(
            new LogRecord.Element(
                new ElementIdentity.IdentifierCorrespondence(
                    MatchingCondition.direct(
                        MatchingCondition.Pair.of(written.ref(lineShop), written.ref(shop)),
                        MatchingCondition.Pair.of(written.ref(lineOrder), written.ref(order))))));
    var existence = c.closedRangeFact(c.address(correspondence, Vocabulary.EXISTENCE), "exists");
    var shopPair = "pair:" + written.urn(lineShop) + "=" + written.urn(shop);
    c.add(
        new LogRecord.Accept(
            existence,
            List.of(),
            List.of(
                new Check(
                    ValidationMethod.INFERENCE,
                    JsonNodeFactory.instance.textNode(shopPair),
                    CheckResult.SUPPORTED,
                    null,
                    null,
                    Scenario.NOTHING)),
            Scenario.NOTHING,
            Scenario.NOTHING));
    c.confirmByMachine(existence);
    var edge = s.write(c).urn(correspondence);

    var answer =
        s.latest(snapshot -> snapshot.request(List.of(KnowledgeRequest.of(edge, "existence"))))
            .getFirst();
    var content = ((Knowledge.Selected) answer.addresses().getFirst().knowledge()).fact();
    assertThat(content.unexaminedItems())
        .containsExactly("pair:" + written.urn(lineOrder) + "=" + written.urn(order));
  }

  private static Ref confirmedDataset(Scenario.Commit c, String table) {
    var dataset = (Ref.Temp) c.dataset(table);
    c.confirmByMachine(new Ref.Temp(dataset.number() + 2));
    return dataset;
  }

  private static Ref confirmedField(Scenario.Commit c, Ref dataset, String column) {
    var field =
        c.add(
            new LogRecord.Element(
                ElementIdentity.PhysicalCatalogObject.field(dataset, Scenario.column(column))));
    c.confirmByMachine(c.closedRangeCandidate(c.address(field, Vocabulary.EXISTENCE), "exists"));
    c.closedRangeCandidate(
        c.address(field, Vocabulary.PHYSICAL_CATALOG_REFERENCE),
        Scenario.column(column).canonical());
    return field;
  }

  private static Ref confirmedNode(Scenario.Commit c, ElementIdentity identity) {
    var node = c.add(new LogRecord.Element(identity));
    c.confirmByMachine(c.closedRangeCandidate(c.address(node, Vocabulary.EXISTENCE), "exists"));
    return node;
  }
}
