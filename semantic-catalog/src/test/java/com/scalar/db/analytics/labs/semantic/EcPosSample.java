/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scalar.db.analytics.labs.semantic.TableMetadataObservation.Read;
import com.scalar.db.analytics.labs.semantic.TableMetadataObservation.SourceMetadata;
import com.scalar.db.analytics.labs.semantic.model.BuildTask;
import com.scalar.db.analytics.labs.semantic.model.Document;
import com.scalar.db.analytics.labs.semantic.model.FactRevision;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.policy.PolicyEvaluationModel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The EC/POS sample the meaning-inference scenarios share: an e-commerce source (customers and
 * their orders) and a point-of-sale source (members and their store sales), whose e-mail addresses
 * differ in letter case, held in an in-memory DuckDB, with shorthands for registering it and
 * reading what was recorded.
 */
final class EcPosSample {
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
  private static final AtomicLong VERSIONS = new AtomicLong();

  private EcPosSample() {}

  /** The sample's data; the caller closes it. */
  static DuckDbConstructionSql data() {
    return new DuckDbConstructionSql(1000)
        .run(
            "ATTACH ':memory:' AS shop",
            "CREATE SCHEMA shop.public",
            "CREATE TABLE shop.public.customers (id INT, email VARCHAR)",
            "INSERT INTO shop.public.customers VALUES (1, 'sato@example.com'),"
                + " (2, 'ito@example.com')",
            "CREATE TABLE shop.public.orders (id INT, customer_id INT, amount INT)",
            "INSERT INTO shop.public.orders VALUES (1001, 1, 4000), (1002, 1, 1200),"
                + " (1003, 2, 3500)",
            "ATTACH ':memory:' AS pos",
            "CREATE SCHEMA pos.public",
            "CREATE TABLE pos.public.members (member_id INT, email VARCHAR)",
            "INSERT INTO pos.public.members VALUES (501, 'SATO@example.com'),"
                + " (503, 'Ito@Example.com')",
            "CREATE TABLE pos.public.store_sales (sale_id INT, member_id INT, amount INT)",
            "INSERT INTO pos.public.store_sales VALUES (9001, 501, 5000), (9003, NULL, 800)");
  }

  private static ObjectNode column(String name, String kind) {
    return JSON.objectNode()
        .put("name", name)
        .put("nullable", false)
        .put("dataSourceType", kind)
        .set("analyticsType", JSON.objectNode().put("kind", kind));
  }

  /**
   * Registers one table's metadata, with its primary key on one column; e-mail columns are text.
   */
  static void register(Scenario s, String identifier, String key, String... columns) {
    var parts = identifier.split("\\.");
    var metadata = JSON.objectNode();
    metadata.putArray("table").add(parts[1]).add(parts[2]);
    metadata.put("kind", "table");
    var array = metadata.putArray("columns");
    for (var index = 0; index < columns.length; index++) {
      var kind = columns[index].equals("email") ? "text" : "int";
      array.add(column(columns[index], kind).put("ordinal", index + 1));
    }
    metadata.put("columnsMayBeHidden", false);
    var primaryKey =
        metadata
            .putObject("constraints")
            .put("state", "value")
            .putArray("value")
            .addObject()
            .put("kind", "primary-key")
            .put("name", parts[2] + "_pkey")
            .put("enforced", true);
    primaryKey.putArray("columns").add(key);
    var version = VERSIONS.incrementAndGet();
    StoreScenarioTest.store.registerTableMetadata(
        s.domain,
        "app",
        new TableMetadataObservation(
            identifier,
            Optional.of(Scenario.table(identifier)),
            version,
            new Read(
                true,
                metadata,
                new SourceMetadata("postgresql", JSON.objectNode().set("table", metadata)),
                Scenario.SCHEMA,
                Instant.parse("2026-11-17T00:00:00Z")),
            JSON.objectNode(),
            "digest-" + version));
  }

  static void registerShop(Scenario s) {
    register(s, "shop.public.customers", "id", "id", "email");
    register(s, "shop.public.orders", "id", "id", "customer_id", "amount");
  }

  static void registerPos(Scenario s) {
    register(s, "pos.public.members", "member_id", "member_id", "email");
    register(s, "pos.public.store_sales", "sale_id", "sale_id", "member_id", "amount");
  }

  /** Registers a document and returns its Input Observation. */
  static String registerDocument(Scenario s, String name, String text) {
    return ((DocumentRegistration.Observed)
            StoreScenarioTest.store.registerDocument(
                s.domain, "owner", null, new Document(name, text)))
        .inputObservation();
  }

  /** A policy evaluation model that adopts every candidate and records what it was asked about. */
  static final class Adopting implements PolicyEvaluationModel {
    final Set<String> asked = new HashSet<>();

    @Override
    public JsonNode version() {
      return JSON.objectNode().put("model", "adopting");
    }

    @Override
    public synchronized Decision evaluate(Subject subject) {
      asked.add(subject.fact().revision().fact());
      return new Decision.Adopt("the inputs support it");
    }
  }

  /** Runs every build task to the end, each of which must succeed. */
  static BuildRun.Summary drain(
      Scenario s, PolicyEvaluationModel policy, ConstructionSql sql, ScriptedModel model) {
    var summary =
        StoreScenarioTest.store.runBuildTasks(
            s.domain,
            new BuildRun.Environment(policy, sql, model, ScriptedModel.NO_DATA),
            BuildRun.drain(),
            task -> {});
    assertThat(summary.finished())
        .allSatisfy(
            task -> assertThat(task.status()).isInstanceOf(BuildTask.Status.Succeeded.class));
    return summary;
  }

  /** The missing input the finished runs of one Generator recorded. */
  static List<String> missingInputs(BuildRun.Summary summary, String generator) {
    return summary.finished().stream()
        .filter(
            task ->
                task.work() instanceof BuildTask.Work.GeneratorRun run
                    && run.generator().equals(generator))
        .flatMap(
            task ->
                ((BuildTask.Result.Generated) ((BuildTask.Status.Succeeded) task.status()).result())
                    .missingInputs().stream())
        .toList();
  }

  static String element(Scenario s, ElementKind kind, String identifier) {
    return Urns.element(s.domain, kind, identifier);
  }

  /** The latest revision of each Fact at an element's empty-scope Fact Address of a predicate. */
  static List<FactRevision> facts(Scenario s, String element, String predicate) {
    var address = Urns.factAddress(element, predicate);
    var found = new ArrayList<FactRevision>();
    for (var number = 1; number <= 10; number++) {
      var urn = Urns.fact(address, number);
      var revision = s.latest(snapshot -> snapshot.latestRevision(urn));
      if (revision.isEmpty()) {
        break;
      }
      found.add(revision.get());
    }
    return found;
  }

  static FactRevision only(Scenario s, String element, String predicate) {
    var found = facts(s, element, predicate);
    assertThat(found).as(predicate + " of " + element).hasSize(1);
    return found.getFirst();
  }
}
