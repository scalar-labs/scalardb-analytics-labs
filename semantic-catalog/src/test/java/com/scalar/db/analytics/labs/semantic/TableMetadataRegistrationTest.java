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
import com.scalar.db.analytics.labs.semantic.TableMetadataRegistration.DerivedRecordOnly;
import com.scalar.db.analytics.labs.semantic.TableMetadataRegistration.NotNewer;
import com.scalar.db.analytics.labs.semantic.TableMetadataRegistration.Observed;
import java.time.Instant;
import java.util.ArrayList;
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

  private static TableMetadataObservation observation(long version, JsonNode tableMetadata) {
    return new TableMetadataObservation(
        TABLE,
        version,
        new Read(
            true,
            tableMetadata,
            new SourceMetadata(
                "postgresql", json("{\"table\": {\"name\": \"orders\", \"comment\": \"Orders\"}}")),
            Instant.parse("2026-11-15T00:00:00Z")),
        json("{\"connector\": {\"name\": \"postgresql\", \"version\": \"0.1.0\"}}"),
        "digest-" + version);
  }

  @Test
  void aNewTableBecomesAnInputResourceWithItsSourceDeclarations() {
    var s = scenario();

    var registration =
        store.registerTableMetadata(s.domain, "app", observation(1, orders(DECIMAL, "")));

    assertThat(registration).isInstanceOf(Observed.class);
    var observed = (Observed) registration;
    assertThat(observed.newInputResource()).isTrue();
    assertThat(observed.commit()).isEqualTo(s.lastCommit());
    // The source declarations and the source metadata are the observation; the Analytics types
    // the connector derived are not.
    var content = store.observationContent(s.domain, observed.inputObservation()).orElseThrow();
    assertThat(content.at("/state").asText()).isEqualTo("read");
    assertThat(content.at("/tableMetadata/columns/1/dataSourceType").asText())
        .isEqualTo("numeric(10,2)");
    assertThat(content.at("/tableMetadata/columns/1").has("analyticsType")).isFalse();
    assertThat(content.at("/tableMetadata/constraints/0/name").asText()).isEqualTo("orders_pkey");
    assertThat(content.at("/sourceMetadata/connector").asText()).isEqualTo("postgresql");
    assertThat(content.at("/sourceMetadata/document/table/comment").asText()).isEqualTo("Orders");
    // They are kept in the version's derived record instead.
    var derived = store.derivedRecord(s.domain, TABLE, 1).orElseThrow();
    assertThat(derived.at("/analyticsTypes/1/column").asText()).isEqualTo("amount");
    assertThat(derived.at("/analyticsTypes/1/analyticsType/kind").asText()).isEqualTo("decimal");
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
  void aChangeOnlyInTheConnectorsMappingRecordsOnlyADerivedRecord() {
    var s = scenario();
    var first =
        (Observed)
            store.registerTableMetadata(s.domain, "app", observation(1, orders(DECIMAL, "")));
    var commit = s.lastCommit();

    // A connector update maps the column to another Analytics type; the source declared nothing
    // new.
    var registration =
        store.registerTableMetadata(
            s.domain, "app", observation(2, orders("{\"kind\": \"double\"}", "")));

    assertThat(registration)
        .isEqualTo(new DerivedRecordOnly(first.inputResource(), first.inputObservation()));
    assertThat(s.lastCommit()).isEqualTo(commit);
    assertThat(store.derivedRecord(s.domain, TABLE, 2).orElseThrow().at("/analyticsTypes/1"))
        .isEqualTo(json("{\"column\": \"amount\", \"analyticsType\": {\"kind\": \"double\"}}"));
    // The next version is judged against the latest version received, not the latest observation.
    assertThat(store.registerTableMetadata(s.domain, "app", observation(2, orders(DECIMAL, ""))))
        .isEqualTo(new NotNewer(2));
  }

  @Test
  void aChangedSourceDeclarationIsANewObservationOfTheSameInputResource() {
    var s = scenario();
    var first =
        (Observed)
            store.registerTableMetadata(s.domain, "app", observation(1, orders(DECIMAL, "")));

    var added =
        (Observed)
            store.registerTableMetadata(
                s.domain,
                "app",
                observation(
                    2,
                    orders(
                        DECIMAL,
                        ", {\"name\": \"note\", \"ordinal\": 3, \"nullable\": true,"
                            + " \"dataSourceType\": \"text\","
                            + " \"analyticsType\": {\"kind\": \"text\"}}")));
    var dropped =
        (Observed)
            store.registerTableMetadata(
                s.domain,
                "app",
                new TableMetadataObservation(
                    TABLE, 3, new DoesNotExist(), json("{\"connector\": {}}"), "digest-3"));

    assertThat(added.newInputResource()).isFalse();
    assertThat(added.inputResource()).isEqualTo(first.inputResource());
    assertThat(added.inputObservation()).isNotEqualTo(first.inputObservation());
    assertThat(
            store
                .observationContent(s.domain, added.inputObservation())
                .orElseThrow()
                .at("/tableMetadata/columns/2/name")
                .asText())
        .isEqualTo("note");
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
                1,
                new Read(
                    false,
                    json("{\"table\": [\"shop\", \"public\", \"customers\"], \"columns\": []}"),
                    new SourceMetadata("postgresql", json("{}")),
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
      // The table is new only once, and its source declarations never change, so exactly one
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
