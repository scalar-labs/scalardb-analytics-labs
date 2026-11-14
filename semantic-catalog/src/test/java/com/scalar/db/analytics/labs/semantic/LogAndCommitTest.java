/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.scalar.db.analytics.labs.semantic.model.ElementIdentity;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.MatchingCondition;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.UsageFeedback;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Normalization;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.SessionPurpose;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogAndCommitTest extends StoreScenarioTest {
  @Test
  void urnsAreIssuedFromLogPositionsAndTemporaryNumbersAreReplaced() {
    var s = scenario();
    var c = s.begin();
    var orders = c.dataset("shop.orders");
    var rowKind = c.address(orders, "row-kind");
    var resource = c.add(LogRecord.InputResource.physicalSchema("shop.orders"));
    var first =
        c.add(
            new LogRecord.InputObservation(
                resource, null, JsonNodeFactory.instance.objectNode().put("columns", 3)));
    c.add(new LogRecord.ObservationEvent(first, Scenario.NOTHING));
    var written = s.write(c);

    // The policy version is commit 1, so this is commit 2, and each record has its position:
    // the Dataset's two Facts each come with their acceptance.
    var prefix = "urn:scalardb:analytics:" + s.domain + ":";
    assertThat(written.commit()).hasValue(2);
    assertThat(written.urn(orders)).isEqualTo(prefix + "element:c2.1");
    assertThat(written.urn(rowKind)).isEqualTo(prefix + "fact-address:c2.8");
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
    var orders = c.dataset("shop.orders");
    var customers = c.dataset("shop.customers");
    var written = s.write(c);
    var ordersUrn = written.urn(orders);
    var customersUrn = written.urn(customers);
    var before = s.lastCommit();

    // A Dataset is found by its technical reference; its empty-scope Fact Addresses and
    // closed-range Facts are found too, so nothing new is written.
    c = s.begin();
    var again = c.datasetProposal("shop.orders");
    var existence = c.address(again, Vocabulary.EXISTENCE);
    var reproposed = s.write(c);
    assertThat(reproposed.urn(again)).isEqualTo(ordersUrn);
    assertThat(reproposed.urn(existence)).isEqualTo(written.urn(new Ref.Temp(1)));
    assertThat(reproposed.commit()).isEmpty();
    assertThat(s.lastCommit()).isEqualTo(before);

    c = s.begin();
    var customerId = c.field(Ref.urn(ordersUrn), "shop.orders.customer_id");
    var id = c.field(Ref.urn(customersUrn), "shop.customers.id");
    var correspondence =
        c.correspondence(MatchingCondition.direct(MatchingCondition.Pair.of(customerId, id)));
    var setRelationship =
        c.closedRangeCandidate(c.address(correspondence, "set-relationship"), "subset");
    var one = c.concept();
    var other = c.concept();
    var sameObject = sameObject(c, one, other);
    var edges = s.write(c);

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
    absent.address(Ref.urn("urn:scalardb:analytics:" + s.domain + ":element:c99.1"), "meaning");
    assertRejected(s, absent, "Missing reference: no element");

    var later = s.begin();
    later.address(new Ref.Temp(5), "meaning");
    assertRejected(s, later, "does not name an earlier record");

    var otherDomain = s.begin();
    otherDomain.address(Ref.urn("urn:scalardb:analytics:elsewhere:element:c2.1"), "meaning");
    assertRejected(s, otherDomain, "another domain");

    var field = s.begin();
    var concept = field.add(new LogRecord.Element(new ElementIdentity.Unique(ElementKind.CONCEPT)));
    field.add(
        new LogRecord.Element(
            new ElementIdentity.PhysicalObject(ElementKind.FIELD, concept, "shop.orders.id")));
    assertRejected(s, field, "The parent of a Field is a Dataset");

    assertThat(s.lastCommit()).isEqualTo(before);
  }

  @Test
  void aCorrespondenceIsIdentifiedByItsMatchingCondition() {
    var s = scenario();
    var c = s.begin();
    var members = c.dataset("pos.members");
    var customers = c.dataset("shop.customers");
    var memberShop = c.field(members, "pos.members.shop_code");
    var memberMail = c.field(members, "pos.members.mail");
    var customerShop = c.field(customers, "shop.customers.shop_code");
    var customerMail = c.field(customers, "shop.customers.email");
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
    var orders = c.dataset("shop.orders");
    var customers = c.dataset("shop.customers");
    var members = c.dataset("pos.members");
    var customerId = c.field(orders, "shop.orders.customer_id");
    var id = c.field(customers, "shop.customers.id");
    var memberName = c.field(members, "pos.members.name");
    var customerName = c.field(customers, "shop.customers.name");
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
    var customers = c.dataset("shop.customers");
    var mapping = c.dataset("crm.customer_members");
    var members = c.dataset("pos.members");
    var orders = c.dataset("shop.orders");
    var customerId = c.field(customers, "shop.customers.id");
    var mappedCustomer = c.field(mapping, "crm.customer_members.customer_id");
    var mappedMember = c.field(mapping, "crm.customer_members.member_id");
    var memberId = c.field(members, "pos.members.id");
    var orderId = c.field(orders, "shop.orders.id");
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
                List.of(Ref.urn("urn:scalardb:analytics:" + s.domain + ":fact:c99.1")))));
    assertRejected(s, unknownFact, "Missing reference: no Fact");

    assertThat(s.lastCommit()).isEqualTo(before);
  }

  @Test
  void theReplayCheckReportsMaterializedRecordsThatDivergeFromTheLog() throws Exception {
    var s = scenario();
    var c = s.begin();
    var orders = c.dataset("shop.orders");
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
