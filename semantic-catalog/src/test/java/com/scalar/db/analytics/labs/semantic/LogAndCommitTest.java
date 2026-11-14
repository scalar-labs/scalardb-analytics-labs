/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.scalar.db.analytics.labs.semantic.model.ElementIdentity;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.MatchingCondition;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Scope;
import com.scalar.db.analytics.labs.semantic.model.UsageFeedback;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Normalization;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.SessionPurpose;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogAndCommitTest extends StoreScenarioTest {
  @Test
  void urnsAreBuiltFromNamesAndTemporaryNumbersAreReplaced() {
    var s = scenario();
    var c = s.begin();
    var orders = c.dataset("shop.public.orders");
    var rowKind = c.address(orders, "row-kind");
    var resource = c.add(LogRecord.InputResource.physicalSchema("shop.orders"));
    var first =
        c.add(
            new LogRecord.InputObservation(
                resource, null, JsonNodeFactory.instance.objectNode().put("columns", 3)));
    c.add(new LogRecord.ObservationEvent(first, Scenario.NOTHING));
    var amount = c.field(orders, "shop.public.orders.amount");
    var unitPrice = c.field(orders, "shop.public.orders.unit price");
    var sales = c.measure("ec-sales");
    var meaning = c.address(amount, "meaning");
    var gross = c.candidate(meaning, "gross amount");
    var net = c.candidate(meaning, "net amount");
    var scoped =
        c.add(
            new LogRecord.FactAddress(
                amount, "meaning", Scope.described("for accounting", List.of()), null));
    var written = s.write(c);

    // The policy version is commit 1, so this is commit 2. A person can tell from each URN of an
    // element, a Fact Address, or a Fact what it names; names that a URN cannot carry as written
    // are percent-encoded.
    var prefix = "urn:scalardb:analytics:" + s.domain + ":";
    assertThat(written.commit()).hasValue(2);
    assertThat(written.urn(orders)).isEqualTo(prefix + "dataset:shop.public.orders");
    assertThat(written.urn(amount)).isEqualTo(prefix + "field:shop.public.orders.amount");
    assertThat(written.urn(unitPrice)).isEqualTo(prefix + "field:shop.public.orders.unit%20price");
    assertThat(written.urn(sales)).isEqualTo(prefix + "measure:ec-sales");
    assertThat(written.urn(rowKind))
        .isEqualTo(prefix + "fact-address:dataset:shop.public.orders/row-kind");
    assertThat(written.urn(new Ref.Temp(2)))
        .isEqualTo(prefix + "fact:dataset:shop.public.orders/existence/1");
    assertThat(written.urn(meaning))
        .isEqualTo(prefix + "fact-address:field:shop.public.orders.amount/meaning");
    assertThat(written.urn(gross))
        .isEqualTo(prefix + "fact:field:shop.public.orders.amount/meaning/1");
    assertThat(written.urn(net))
        .isEqualTo(prefix + "fact:field:shop.public.orders.amount/meaning/2");
    // A Fact Address with an applicability scope is found only by pointing, so it takes a
    // sequence number and leaves the plain identifier to the one found by element and predicate.
    assertThat(written.urn(scoped))
        .isEqualTo(prefix + "fact-address:field:shop.public.orders.amount/meaning~2");
    assertThat(s.revision(written.urn(gross)).urn())
        .isEqualTo(
            prefix
                + "fact-revision:field:shop.public.orders.amount/meaning/1@c2."
                + (((Ref.Temp) gross).number() + 2));

    // Records that are not named keep the URN of their log position: the Dataset's two Facts each
    // come with their acceptance.
    assertThat(written.urn(resource)).isEqualTo(prefix + "input-resource:c2.9");
    assertThat(written.urn(first)).isEqualTo(prefix + "input-observation:c2.10");

    // A later observation of the same Input Resource refers to the earlier one.
    c = s.begin();
    var second =
        c.add(
            new LogRecord.InputObservation(
                written.ref(resource),
                null,
                JsonNodeFactory.instance.objectNode().put("columns", 4)));
    var next = s.write(c);
    assertThat(next.urn(second)).isEqualTo(prefix + "input-observation:c3.1");
    String snapshot = s.latest(CatalogSnapshot::urn);
    assertThat(snapshot).isEqualTo(prefix + "catalog-snapshot:3");
  }

  @Test
  void proposalsOfExistingRecordsYieldTheExistingRecords() {
    var s = scenario();
    var c = s.begin();
    var orders = c.dataset("shop.public.orders");
    var customers = c.dataset("shop.public.customers");
    var written = s.write(c);
    var ordersUrn = written.urn(orders);
    var customersUrn = written.urn(customers);
    var before = s.lastCommit();

    // A Dataset is found by its technical reference; its empty-scope Fact Addresses and
    // closed-range Facts are found too, so nothing new is written.
    c = s.begin();
    var again = c.datasetProposal("shop.public.orders");
    var existence = c.address(again, Vocabulary.EXISTENCE);
    var reproposed = s.write(c);
    assertThat(reproposed.urn(again)).isEqualTo(ordersUrn);
    assertThat(reproposed.urn(existence)).isEqualTo(written.urn(new Ref.Temp(1)));
    assertThat(reproposed.commit()).isEmpty();
    assertThat(s.lastCommit()).isEqualTo(before);

    c = s.begin();
    var customerId = c.field(Ref.urn(ordersUrn), "shop.public.orders.customer_id");
    var id = c.field(Ref.urn(customersUrn), "shop.public.customers.id");
    var correspondence =
        c.correspondence(MatchingCondition.direct(MatchingCondition.Pair.of(customerId, id)));
    var setRelationship =
        c.closedRangeCandidate(c.address(correspondence, "set-relationship"), "subset");
    var one = c.concept("member");
    var other = c.concept("customer");
    var sameObject = sameObject(c, one, other);
    var edges = s.write(c);
    // An edge is named by its kind and the identifiers of its ends; an undirected edge's ends are
    // in a fixed order.
    var prefix = "urn:scalardb:analytics:" + s.domain + ":";
    assertThat(edges.urn(correspondence))
        .isEqualTo(
            prefix
                + "identifier-correspondence:shop.public.orders.customer_id,"
                + "shop.public.customers.id");
    assertThat(edges.urn(sameObject)).isEqualTo(prefix + "concept-relationship:customer,member");

    // The same correspondence, and the undirected concept relationship with its ends reversed.
    c = s.begin();
    var sameCorrespondence =
        c.correspondence(
            MatchingCondition.direct(
                MatchingCondition.Pair.of(edges.ref(customerId), edges.ref(id))));
    var reversed = sameObject(c, edges.ref(other), edges.ref(one));
    var sameValue = c.closedRangeFact(c.address(sameCorrespondence, "set-relationship"), "subset");
    var otherValue =
        c.closedRangeCandidate(c.address(sameCorrespondence, "set-relationship"), "equal");
    var proposed = s.write(c);
    assertThat(proposed.urn(sameCorrespondence)).isEqualTo(edges.urn(correspondence));
    assertThat(proposed.urn(reversed)).isEqualTo(edges.urn(sameObject));
    assertThat(proposed.urn(sameValue)).isEqualTo(edges.urn(setRelationship));
    assertThat(proposed.urn(otherValue)).isNotEqualTo(edges.urn(setRelationship));

    c = s.begin();
    var table = c.add(LogRecord.InputResource.physicalSchema("shop.orders"));
    var firstResource = s.write(c).urn(table);
    c = s.begin();
    var sameTable = c.add(LogRecord.InputResource.physicalSchema("shop.orders"));
    var secondResource = s.write(c);
    assertThat(secondResource.urn(sameTable)).isEqualTo(firstResource);
    assertThat(secondResource.commit()).isEmpty();
  }

  @Test
  void missingReferencesFailTheCommit() {
    var s = scenario();
    var before = s.lastCommit();

    var absent = s.begin();
    absent.address(
        Ref.urn("urn:scalardb:analytics:" + s.domain + ":dataset:shop.public.none"), "meaning");
    assertRejected(s, absent, "Missing reference: no element");

    var later = s.begin();
    later.address(new Ref.Temp(5), "meaning");
    assertRejected(s, later, "does not name an earlier record");

    var otherDomain = s.begin();
    otherDomain.address(
        Ref.urn("urn:scalardb:analytics:elsewhere:dataset:shop.public.orders"), "meaning");
    assertRejected(s, otherDomain, "another domain");

    var field = s.begin();
    var concept =
        field.add(new LogRecord.Element(new ElementIdentity.Unique(ElementKind.CONCEPT, "order")));
    field.add(
        new LogRecord.Element(
            ElementIdentity.PhysicalObject.field(
                concept, Scenario.column("shop.public.orders.id"))));
    assertRejected(s, field, "The parent of a Field is a Dataset");

    assertThat(s.lastCommit()).isEqualTo(before);
  }

  @Test
  void sameNamesNeverMakeTheSameElement() {
    var s = scenario();
    var c = s.begin();
    var sales = c.measure("ec-sales");
    var otherSales = c.measure("ec-sales");
    var first = s.write(c);
    c = s.begin();
    var laterSales = c.measure("ec-sales");
    var later = s.write(c);

    // Two Measures a model names alike stay distinct: the store appends a sequence number to a
    // slug already taken, in log order, whether earlier in the request or in an earlier commit.
    var prefix = "urn:scalardb:analytics:" + s.domain + ":";
    assertThat(first.urn(sales)).isEqualTo(prefix + "measure:ec-sales");
    assertThat(first.urn(otherSales)).isEqualTo(prefix + "measure:ec-sales~2");
    assertThat(later.urn(laterSales)).isEqualTo(prefix + "measure:ec-sales~3");

    // A slug is not shared across kinds, since an element's kind is part of its URN.
    c = s.begin();
    var concept = c.concept("ec-sales");
    assertThat(s.write(c).urn(concept)).isEqualTo(prefix + "concept:ec-sales");

    // Facts at one Fact Address keep counting across commits.
    c = s.begin();
    var meaning = c.address(first.ref(sales), "meaning");
    c.candidate(meaning, "sales including tax");
    var withMeaning = s.write(c);
    c = s.begin();
    var excludingTax = c.candidate(withMeaning.ref(meaning), "sales excluding tax");
    assertThat(s.write(c).urn(excludingTax)).isEqualTo(prefix + "fact:measure:ec-sales/meaning/2");
  }

  @Test
  void aFieldIsAColumnOfItsDatasetsTable() {
    var s = scenario();
    var c = s.begin();
    var orders = c.dataset("shop.public.orders");
    var customers = c.dataset("shop.public.customers");
    c.field(customers, "shop.public.customers.id");
    var written = s.write(c);
    var ordersUrn = written.urn(orders);
    var before = s.lastCommit();

    // A column of another table, whether or not a Field for it exists already.
    var elsewhere = s.begin();
    elsewhere.field(Ref.urn(ordersUrn), "shop.public.customers.name");
    assertRejected(s, elsewhere, "belongs to its Dataset's table");
    var existingElsewhere = s.begin();
    existingElsewhere.field(Ref.urn(ordersUrn), "shop.public.customers.id");
    assertRejected(s, existingElsewhere, "belongs to Dataset " + written.urn(customers));

    // A technical reference never moves, so a Dataset cannot be pointed at another table.
    var moved = s.begin();
    var reference = moved.address(Ref.urn(ordersUrn), Vocabulary.TECHNICAL_REFERENCE);
    moved.accept(moved.closedRangeFact(reference, Scenario.table("shop.public.sales").canonical()));
    assertRejected(s, moved, "A technical reference never moves");

    var proposed = s.begin();
    proposed.add(
        new LogRecord.Element(
            ordersUrn.replace(":dataset:", ":concept:"),
            new ElementIdentity.Unique(ElementKind.CONCEPT, "order")));
    assertRejected(s, proposed, "issued when it is written");

    assertThat(s.lastCommit()).isEqualTo(before);
  }

  @Test
  void aSlugIsAShortBusinessName() {
    for (var slug : List.of("", "EC-Sales", "ec sales", "ec--sales", "-ec", "ec.sales")) {
      assertThatThrownBy(() -> new ElementIdentity.Unique(ElementKind.MEASURE, slug))
          .as(slug)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void aCorrespondenceIsIdentifiedByItsMatchingCondition() {
    var s = scenario();
    var c = s.begin();
    var members = c.dataset("pos.public.members");
    var customers = c.dataset("shop.public.customers");
    var memberShop = c.field(members, "pos.public.members.shop_code");
    var memberMail = c.field(members, "pos.public.members.mail");
    var customerShop = c.field(customers, "shop.public.customers.shop_code");
    var customerMail = c.field(customers, "shop.public.customers.email");
    var composite =
        c.correspondence(
            MatchingCondition.direct(
                MatchingCondition.Pair.of(customerShop, memberShop),
                MatchingCondition.Pair.of(customerMail, memberMail)));
    var written = s.write(c);
    var shop = MatchingCondition.Pair.of(written.ref(customerShop), written.ref(memberShop));
    var mail = MatchingCondition.Pair.of(written.ref(customerMail), written.ref(memberMail));

    c = s.begin();
    // The pairs stated in another order: the same correspondence.
    var reordered = c.correspondence(MatchingCondition.direct(mail, shop));
    // E-mail addresses that match only when case is ignored: another correspondence.
    var ignoringCase =
        c.correspondence(
            MatchingCondition.direct(
                shop,
                new MatchingCondition.Pair(
                    MatchingCondition.Side.of(written.ref(customerMail), Normalization.LOWER_CASE),
                    MatchingCondition.Side.of(written.ref(memberMail), Normalization.LOWER_CASE))));
    // From the members to the customers: another correspondence, since it is directed.
    var reversed =
        c.correspondence(
            MatchingCondition.direct(
                MatchingCondition.Pair.of(written.ref(memberShop), written.ref(customerShop)),
                MatchingCondition.Pair.of(written.ref(memberMail), written.ref(customerMail))));
    var proposed = s.write(c);
    assertThat(proposed.urn(reordered)).isEqualTo(written.urn(composite));
    assertThat(proposed.urn(ignoringCase)).isNotEqualTo(written.urn(composite));
    assertThat(proposed.urn(reversed))
        .isNotEqualTo(written.urn(composite))
        .isNotEqualTo(proposed.urn(ignoringCase));
  }

  @Test
  void eachEndOfACorrespondenceIsTheFieldsOfOneDataset() {
    var s = scenario();
    var c = s.begin();
    var orders = c.dataset("shop.public.orders");
    var customers = c.dataset("shop.public.customers");
    var members = c.dataset("pos.public.members");
    var customerId = c.field(orders, "shop.public.orders.customer_id");
    var id = c.field(customers, "shop.public.customers.id");
    var memberName = c.field(members, "pos.public.members.name");
    var customerName = c.field(customers, "shop.public.customers.name");
    var written = s.write(c);
    var before = s.lastCommit();

    // The starting end would take Fields of the orders and of the members.
    var twoDatasets = s.begin();
    twoDatasets.correspondence(
        MatchingCondition.direct(
            MatchingCondition.Pair.of(written.ref(customerId), written.ref(id)),
            MatchingCondition.Pair.of(written.ref(memberName), written.ref(customerName))));
    assertRejected(s, twoDatasets, "the Fields of one Dataset");

    var notFields = s.begin();
    notFields.correspondence(
        MatchingCondition.direct(
            MatchingCondition.Pair.of(written.ref(orders), written.ref(customers))));
    assertRejected(s, notFields, "matches Fields");

    assertThat(s.lastCommit()).isEqualTo(before);
  }

  @Test
  void aCorrespondenceThroughAMappingTableIsAChainOfHops() {
    var s = scenario();
    var c = s.begin();
    var customers = c.dataset("shop.public.customers");
    var mapping = c.dataset("crm.public.customer_members");
    var members = c.dataset("pos.public.members");
    var orders = c.dataset("shop.public.orders");
    var customerId = c.field(customers, "shop.public.customers.id");
    var mappedCustomer = c.field(mapping, "crm.public.customer_members.customer_id");
    var mappedMember = c.field(mapping, "crm.public.customer_members.member_id");
    var memberId = c.field(members, "pos.public.members.id");
    var orderId = c.field(orders, "shop.public.orders.id");
    var written = s.write(c);

    // From the customers through the mapping table to the members.
    c = s.begin();
    var throughMapping =
        c.correspondence(
            new MatchingCondition(
                List.of(
                    new MatchingCondition.Hop(
                        List.of(
                            MatchingCondition.Pair.of(
                                written.ref(customerId), written.ref(mappedCustomer)))),
                    new MatchingCondition.Hop(
                        List.of(
                            MatchingCondition.Pair.of(
                                written.ref(mappedMember), written.ref(memberId)))))));
    assertThat(s.write(c).urn(throughMapping)).isNotNull();
    var before = s.lastCommit();

    // The second hop starts from the orders, which the first hop did not arrive at.
    var disconnected = s.begin();
    disconnected.correspondence(
        new MatchingCondition(
            List.of(
                new MatchingCondition.Hop(
                    List.of(
                        MatchingCondition.Pair.of(
                            written.ref(customerId), written.ref(mappedCustomer)))),
                new MatchingCondition.Hop(
                    List.of(
                        MatchingCondition.Pair.of(written.ref(orderId), written.ref(memberId)))))));
    assertRejected(s, disconnected, "starts from the Dataset the previous hop arrived at");

    assertThat(s.lastCommit()).isEqualTo(before);
  }

  @Test
  void eachRegistrationOfUsageFeedbackIsANewInputResource() {
    var s = scenario();
    var feedback =
        new UsageFeedback(
            "Sales are tax-exclusive",
            SessionPurpose.COMPOSING_A_QUERY,
            "Asked for monthly sales; the user corrected the total",
            List.of());

    var c = s.begin();
    var first = c.add(LogRecord.InputResource.usageFeedback());
    c.add(LogRecord.InputObservation.of(first, feedback));
    var second = c.add(LogRecord.InputResource.usageFeedback());
    c.add(LogRecord.InputObservation.of(second, feedback));
    var written = s.write(c);
    assertThat(written.urn(first)).isNotEqualTo(written.urn(second));

    c = s.begin();
    var again = c.add(LogRecord.InputResource.usageFeedback());
    c.add(LogRecord.InputObservation.of(again, feedback));
    assertThat(s.write(c).urn(again)).isNotIn(written.urn(first), written.urn(second));
  }

  @Test
  void usageFeedbackCarriesItsPurposeAndContextAndIsNeverRevised() {
    var s = scenario();
    var c = s.begin();
    var resource = c.add(LogRecord.InputResource.usageFeedback());
    c.add(
        LogRecord.InputObservation.of(
            resource,
            new UsageFeedback(
                "Members are shops' customers",
                SessionPurpose.CONSTRUCTING_THE_CATALOG,
                "Review of the member correspondence",
                List.of())));
    var written = s.write(c);
    var before = s.lastCommit();

    var revised = s.begin();
    revised.add(
        LogRecord.InputObservation.of(
            written.ref(resource),
            new UsageFeedback(
                "Members are not customers",
                SessionPurpose.CONSTRUCTING_THE_CATALOG,
                "Review of the member correspondence",
                List.of())));
    assertRejected(s, revised, "never revised");

    var withoutContext = s.begin();
    var bare = withoutContext.add(LogRecord.InputResource.usageFeedback());
    withoutContext.add(
        new LogRecord.InputObservation(
            bare,
            null,
            JsonNodeFactory.instance
                .objectNode()
                .put("content", "Sales are tax-exclusive")
                .put("purpose", SessionPurpose.COMPOSING_A_QUERY.name())));
    assertRejected(s, withoutContext, "summary of its session's context");

    var empty = s.begin();
    var nothing = empty.add(LogRecord.InputResource.usageFeedback());
    empty.add(new LogRecord.InputObservation(nothing, null, NullNode.getInstance()));
    assertRejected(s, empty, "summary of its session's context");

    var unknownFact = s.begin();
    var related = unknownFact.add(LogRecord.InputResource.usageFeedback());
    unknownFact.add(
        LogRecord.InputObservation.of(
            related,
            new UsageFeedback(
                "That one is tax-exclusive",
                SessionPurpose.COMPOSING_A_QUERY,
                "Monthly sales by shop",
                List.of(
                    Ref.urn(
                        "urn:scalardb:analytics:"
                            + s.domain
                            + ":fact:dataset:shop.public.none/existence/1")))));
    assertRejected(s, unknownFact, "Missing reference: no Fact");

    assertThat(s.lastCommit()).isEqualTo(before);
  }

  @Test
  void theReplayCheckReportsMaterializedRecordsThatDivergeFromTheLog() throws Exception {
    var s = scenario();
    var c = s.begin();
    var orders = c.dataset("shop.public.orders");
    var rowKind = c.address(orders, "row-kind");
    var written = s.write(c);
    var address = written.urn(rowKind);
    assertThat(store.replayCheck(s.domain).matches()).isTrue();

    try (var connection = TestStore.connect();
        var update =
            connection.prepareStatement(
                "UPDATE semantic_catalog.fact_addresses SET predicate = 'unit'"
                    + " WHERE domain = ? AND fact_address = ?")) {
      update.setString(1, s.domain);
      update.setString(2, address);
      assertThat(update.executeUpdate()).isEqualTo(1);
      var diverged = store.replayCheck(s.domain);
      assertThat(diverged.matches()).isFalse();
      assertThat(diverged.missingFromStore()).singleElement().asString().contains("row-kind");
      assertThat(diverged.unexpectedInStore()).singleElement().asString().contains("unit");

      // Restore the row, so the check after the scenario passes again.
      try (var restore =
          connection.prepareStatement(
              "UPDATE semantic_catalog.fact_addresses SET predicate = 'row-kind'"
                  + " WHERE domain = ? AND fact_address = ?")) {
        restore.setString(1, s.domain);
        restore.setString(2, address);
        restore.executeUpdate();
      }
    }
  }

  /** An undirected concept relationship stating that two concepts denote the same object. */
  private static Ref sameObject(Scenario.Commit c, Ref one, Ref other) {
    return withExistence(
        c,
        c.add(
            new LogRecord.Element(
                new ElementIdentity.ConceptRelationship(one, other, false, "same-object"))));
  }

  private static Ref withExistence(Scenario.Commit c, Ref element) {
    c.closedRangeCandidate(c.address(element, Vocabulary.EXISTENCE), "exists");
    return element;
  }
}
