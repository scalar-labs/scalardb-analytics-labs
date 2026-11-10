/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.scalar.db.analytics.labs.semantic.model.ElementIdentity;
import com.scalar.db.analytics.labs.semantic.model.IdentityKey;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import org.junit.jupiter.api.Test;

class LogAndCommitTest extends StoreScenarioTest {
  @Test
  void urnsAreIssuedFromLogPositionsAndTemporaryNumbersAreReplaced() {
    var s = scenario();
    var c = s.begin();
    var orders = c.dataset("shop.orders");
    var rowKind = c.address(orders, "row-kind");
    var resource = c.add(new LogRecord.InputResource("physical-schema", "shop.orders", null));
    var first =
        c.add(
            new LogRecord.InputObservation(
                resource, null, JsonNodeFactory.instance.objectNode().put("columns", 3)));
    c.add(new LogRecord.ObservationEvent(first, Scenario.NOTHING));
    var written = s.write(c);

    // The policy version is commit 1, so this is commit 2, and each record has its position.
    var prefix = "urn:scalardb:analytics:" + s.domain + ":";
    assertThat(written.commit()).hasValue(2);
    assertThat(written.urn(orders)).isEqualTo(prefix + "element:c2.1");
    assertThat(written.urn(rowKind)).isEqualTo(prefix + "fact-address:c2.6");
    assertThat(written.urn(resource)).isEqualTo(prefix + "input-resource:c2.7");
    assertThat(written.urn(first)).isEqualTo(prefix + "input-observation:c2.8");

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
    var again = c.dataset("shop.orders");
    var existence = c.address(again, Vocabulary.EXISTENCE);
    var reproposed = s.write(c);
    assertThat(reproposed.urn(again)).isEqualTo(ordersUrn);
    assertThat(reproposed.urn(existence)).isEqualTo(written.urn(new Ref.Temp(1)));
    assertThat(reproposed.commit()).isEmpty();
    assertThat(s.lastCommit()).isEqualTo(before);

    c = s.begin();
    var join = join(c, Ref.urn(ordersUrn), Ref.urn(customersUrn));
    var correspondence = correspondence(c, Ref.urn(ordersUrn), Ref.urn(customersUrn));
    var multiplicity = c.closedRangeFact(c.address(join, "multiplicity"), "many-to-one");
    var edges = s.write(c);

    // The same join, and the undirected correspondence with its ends reversed.
    c = s.begin();
    var joinAgain = join(c, Ref.urn(ordersUrn), Ref.urn(customersUrn));
    var reversed = correspondence(c, Ref.urn(customersUrn), Ref.urn(ordersUrn));
    var sameValue = c.closedRangeFact(c.address(joinAgain, "multiplicity"), "many-to-one");
    var otherValue = c.closedRangeFact(c.address(joinAgain, "multiplicity"), "one-to-one");
    var proposed = s.write(c);
    assertThat(proposed.urn(joinAgain)).isEqualTo(edges.urn(join));
    assertThat(proposed.urn(reversed)).isEqualTo(edges.urn(correspondence));
    assertThat(proposed.urn(sameValue)).isEqualTo(edges.urn(multiplicity));
    assertThat(proposed.urn(otherValue)).isNotEqualTo(edges.urn(multiplicity));

    c = s.begin();
    var table = c.add(new LogRecord.InputResource("physical-schema", "shop.orders", null));
    var firstResource = s.write(c).urn(table);
    c = s.begin();
    var sameTable = c.add(new LogRecord.InputResource("physical-schema", "shop.orders", null));
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

  private static Ref join(Scenario.Commit c, Ref from, Ref to) {
    return c.add(
        new LogRecord.Element(
            new ElementIdentity.Edge(
                ElementKind.JOIN,
                from,
                to,
                true,
                IdentityKey.choice("orders.customer_id = customers.id"),
                JsonNodeFactory.instance
                    .arrayNode()
                    .add(
                        JsonNodeFactory.instance
                            .objectNode()
                            .put("from", "customer_id")
                            .put("to", "id")))));
  }

  private static Ref correspondence(Scenario.Commit c, Ref one, Ref other) {
    return c.add(
        new LogRecord.Element(
            new ElementIdentity.Edge(
                ElementKind.IDENTIFIER_CORRESPONDENCE,
                one,
                other,
                false,
                IdentityKey.choice("direct"),
                JsonNodeFactory.instance.objectNode().put("match", "direct"))));
  }
}
