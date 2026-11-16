/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalar.db.analytics.labs.semantic.TableMetadataObservation.DoesNotExist;
import com.scalar.db.analytics.labs.semantic.TableMetadataObservation.Read;
import com.scalar.db.analytics.labs.semantic.TableMetadataObservation.SourceMetadata;
import com.scalar.db.analytics.labs.semantic.TableMetadataObservation.SourceMetadataSchema;
import com.scalar.db.analytics.labs.semantic.TableMetadataRegistration.DerivedRecordOnly;
import com.scalar.db.analytics.labs.semantic.TableMetadataRegistration.NotNewer;
import com.scalar.db.analytics.labs.semantic.TableMetadataRegistration.Observed;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class TableMetadataRegistrationTest extends StoreScenarioTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String TABLE = "shop.public.orders";

  /** Table metadata of the orders table, with the given Analytics type of its amount column. */
  private static JsonNode orders(String amountType, String extraColumn) {
    return json(
        """
        {"table": ["shop", "public", "orders"], "kind": "table",
         "columns": [
           {"name": "id", "ordinal": 1, "nullable": false, "dataSourceType": "integer",
            "analyticsType": {"kind": "int"}},
           {"name": "amount", "ordinal": 2, "nullable": true, "dataSourceType": "numeric(10,2)",
            "analyticsType": %s}%s],
         "constraints": [{"kind": "primary-key", "name": "orders_pkey", "enforced": true,
                          "columns": ["id"]}]}
        """
            .formatted(amountType, extraColumn));
  }

  private static final String DECIMAL = "{\"kind\": \"decimal\", \"precision\": 10, \"scale\": 2}";

  /** Source metadata of the orders table, with the given table comment. */
  private static SourceMetadata ordersSource(String comment) {
    return new SourceMetadata(
        "postgresql",
        json("{\"table\": {\"name\": \"orders\", \"comment\": \"%s\"}}".formatted(comment)));
  }

  private static TableMetadataObservation observation(long version, JsonNode tableMetadata) {
    return observation(version, tableMetadata, ordersSource("Orders"));
  }

  private static TableMetadataObservation observation(
      long version, JsonNode tableMetadata, SourceMetadata sourceMetadata) {
    return observation(TABLE, version, tableMetadata, sourceMetadata, Scenario.SCHEMA);
  }

  private static TableMetadataObservation observation(
      String table,
      long version,
      JsonNode tableMetadata,
      SourceMetadata sourceMetadata,
      SourceMetadataSchema schema) {
    return new TableMetadataObservation(
        table,
        Optional.of(Scenario.table(table)),
        version,
        new Read(
            true, tableMetadata, sourceMetadata, schema, Instant.parse("2026-11-15T00:00:00Z")),
        json("{\"connector\": {\"name\": \"postgresql\", \"version\": \"0.1.0\"}}"),
        "digest-" + version);
  }

  @Test
  void aNewTableBecomesAnInputResourceObservedThroughItsSourceMetadata() {
    var s = scenario();

    var registration =
        store.registerTableMetadata(s.domain, "app", observation(1, orders(DECIMAL, "")));

    assertThat(registration).isInstanceOf(Observed.class);
    var observed = (Observed) registration;
    assertThat(observed.newInputResource()).isTrue();
    assertThat(observed.commit()).isEqualTo(s.lastCommit());
    // The source metadata is the observation; the table metadata the connector derived from it
    // is not.
    var content = store.observationContent(s.domain, observed.inputObservation()).orElseThrow();
    assertThat(content)
        .isEqualTo(
            json(
                "{\"state\": \"read\", \"sourceMetadata\": {\"connector\": \"postgresql\","
                    + " \"document\": {\"table\": {\"name\": \"orders\", \"comment\": \"Orders\"}}}}"));
    // It is kept whole in the version's derived record instead.
    var derived = store.derivedRecord(s.domain, TABLE, 1).orElseThrow();
    assertThat(derived.at("/tableMetadata")).isEqualTo(orders(DECIMAL, ""));
    assertThat(derived.at("/complete").asBoolean()).isTrue();
    assertThat(derived.at("/retrieval/connector/name").asText()).isEqualTo("postgresql");
    assertThat(derived.at("/contentDigest").asText()).isEqualTo("digest-1");
    assertThat(derived.at("/readAt").asText()).isEqualTo("2026-11-15T00:00:00Z");
  }

  @Test
  void aVersionNoNewerThanTheLatestRecordsNothing() {
    var s = scenario();
    store.registerTableMetadata(s.domain, "app", observation(2, orders(DECIMAL, "")));
    var commit = s.lastCommit();

    // A retrieval that arrives late, even with changed columns, cannot roll the table back.
    var late =
        store.registerTableMetadata(
            s.domain,
            "app",
            observation(
                1, orders(DECIMAL, ", {\"name\": \"note\", \"ordinal\": 3, \"nullable\": true}")));
    var again = store.registerTableMetadata(s.domain, "app", observation(2, orders(DECIMAL, "")));

    assertThat(late).isEqualTo(new NotNewer(2));
    assertThat(again).isEqualTo(new NotNewer(2));
    assertThat(s.lastCommit()).isEqualTo(commit);
    assertThat(store.derivedRecord(s.domain, TABLE, 1)).isEmpty();
  }

  @Test
  void aChangeOnlyInTheTableMetadataRecordsOnlyADerivedRecord() {
    var s = scenario();
    var first =
        (Observed)
            store.registerTableMetadata(s.domain, "app", observation(1, orders(DECIMAL, "")));
    var commit = s.lastCommit();

    // A connector update maps the column to another Analytics type; the source metadata is
    // unchanged.
    var registration =
        store.registerTableMetadata(
            s.domain, "app", observation(2, orders("{\"kind\": \"double\"}", "")));

    assertThat(registration)
        .isEqualTo(new DerivedRecordOnly(first.inputResource(), first.inputObservation()));
    assertThat(s.lastCommit()).isEqualTo(commit);
    assertThat(
            store
                .derivedRecord(s.domain, TABLE, 2)
                .orElseThrow()
                .at("/tableMetadata/columns/1/analyticsType"))
        .isEqualTo(json("{\"kind\": \"double\"}"));
    // The next version is judged against the latest version received, not the latest observation.
    assertThat(store.registerTableMetadata(s.domain, "app", observation(2, orders(DECIMAL, ""))))
        .isEqualTo(new NotNewer(2));
  }

  /** The schema the postgresql connector publishes, with the given description of a comment. */
  private static SourceMetadataSchema schema(String description) {
    return new SourceMetadataSchema(
        "schema-" + description.length(),
        json(
            "{\"type\": \"object\", \"properties\": {\"comment\": {\"description\": \"%s\"}}}"
                .formatted(description)));
  }

  @Test
  void aChangeOnlyInTheSourceMetadataSchemaRecordsOnlyADerivedRecordNamingTheNewSchema() {
    var s = scenario();
    var described = schema("The table comment.");
    var first =
        (Observed)
            store.registerTableMetadata(
                s.domain,
                "app",
                observation(TABLE, 1, orders(DECIMAL, ""), ordersSource("Orders"), described));
    // Another table the same connector read shares the schema, which the domain keeps once.
    var customers = "shop.public.customers";
    assertThat(
            store.registerTableMetadata(
                s.domain,
                "app",
                observation(
                    customers,
                    1,
                    json("{\"table\": [\"shop\", \"public\", \"customers\"], \"columns\": []}"),
                    ordersSource("Customers"),
                    described)))
        .isInstanceOf(Observed.class);
    var commit = s.lastCommit();

    // A library update describes the same document more fully; the source did not change.
    var redescribed = schema("The table's comment, from obj_description.");
    var registration =
        store.registerTableMetadata(
            s.domain,
            "app",
            observation(TABLE, 2, orders(DECIMAL, ""), ordersSource("Orders"), redescribed));

    assertThat(registration)
        .isEqualTo(new DerivedRecordOnly(first.inputResource(), first.inputObservation()));
    assertThat(s.lastCommit()).isEqualTo(commit);
    assertThat(store.derivedRecord(s.domain, TABLE, 1).orElseThrow().at("/sourceMetadataSchema"))
        .isEqualTo(json("\"" + described.digest() + "\""));
    assertThat(store.derivedRecord(s.domain, TABLE, 2).orElseThrow().at("/sourceMetadataSchema"))
        .isEqualTo(json("\"" + redescribed.digest() + "\""));
    assertThat(
            store.derivedRecord(s.domain, customers, 1).orElseThrow().at("/sourceMetadataSchema"))
        .isEqualTo(json("\"" + described.digest() + "\""));
  }

  @Test
  void changedSourceMetadataIsANewObservationOfTheSameInputResource() {
    var s = scenario();
    var first =
        (Observed)
            store.registerTableMetadata(s.domain, "app", observation(1, orders(DECIMAL, "")));

    var commented =
        (Observed)
            store.registerTableMetadata(
                s.domain,
                "app",
                observation(2, orders(DECIMAL, ""), ordersSource("Customer orders")));
    var dropped =
        (Observed)
            store.registerTableMetadata(
                s.domain,
                "app",
                new TableMetadataObservation(
                    TABLE,
                    Optional.of(Scenario.table(TABLE)),
                    3,
                    new DoesNotExist(),
                    json("{\"connector\": {}}"),
                    "digest-3"));

    assertThat(commented.newInputResource()).isFalse();
    assertThat(commented.inputResource()).isEqualTo(first.inputResource());
    assertThat(commented.inputObservation()).isNotEqualTo(first.inputObservation());
    assertThat(
            store
                .observationContent(s.domain, commented.inputObservation())
                .orElseThrow()
                .at("/sourceMetadata/document/table/comment")
                .asText())
        .isEqualTo("Customer orders");
    assertThat(dropped.inputResource()).isEqualTo(first.inputResource());
    assertThat(store.observationContent(s.domain, dropped.inputObservation()).orElseThrow())
        .isEqualTo(json("{\"state\": \"does-not-exist\"}"));
    assertThat(s.lastCommit()).isEqualTo(dropped.commit());
  }

  @Test
  void tablesAreTrackedSeparately() {
    var s = scenario();
    var orders =
        (Observed)
            store.registerTableMetadata(s.domain, "app", observation(5, orders(DECIMAL, "")));

    var other =
        store.registerTableMetadata(
            s.domain,
            "app",
            new TableMetadataObservation(
                "shop.public.customers",
                Optional.of(Scenario.table("shop.public.customers")),
                1,
                new Read(
                    false,
                    json("{\"table\": [\"shop\", \"public\", \"customers\"], \"columns\": []}"),
                    new SourceMetadata("postgresql", json("{}")),
                    Scenario.SCHEMA,
                    Instant.parse("2026-11-15T00:00:00Z")),
                json("{}"),
                "digest"));

    assertThat(other).isInstanceOf(Observed.class);
    assertThat(((Observed) other).newInputResource()).isTrue();
    assertThat(((Observed) other).inputResource()).isNotEqualTo(orders.inputResource());
  }

  @Test
  void concurrentRegistrationsOfOneTableRunOneAtATime() throws Exception {
    var s = scenario();
    try (var executor = Executors.newFixedThreadPool(4)) {
      var results = new ArrayList<Future<TableMetadataRegistration>>();
      for (var version = 1; version <= 8; version++) {
        var v = version;
        results.add(
            executor.submit(
                () ->
                    store.registerTableMetadata(
                        s.domain, "app", observation(v, orders(DECIMAL, "")))));
      }
      var observed = 0;
      for (var result : results) {
        if (result.get() instanceof Observed) {
          observed++;
        }
      }
      // The table is new only once, and its source metadata never changes, so exactly one
      // registration observes it.
      assertThat(observed).isEqualTo(1);
    }
    assertThat(store.registerTableMetadata(s.domain, "app", observation(8, orders(DECIMAL, ""))))
        .isEqualTo(new NotNewer(8));
  }

  private static JsonNode json(String text) {
    try {
      return JSON.readTree(text);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }
}
