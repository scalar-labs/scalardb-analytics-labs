/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scalar.db.analytics.labs.semantic.TableMetadataObservation.DoesNotExist;
import com.scalar.db.analytics.labs.semantic.TableMetadataObservation.Read;
import com.scalar.db.analytics.labs.semantic.TableMetadataObservation.SourceMetadata;
import com.scalar.db.analytics.labs.semantic.TableMetadataRegistration.Observed;
import com.scalar.db.analytics.labs.semantic.generator.Generators;
import com.scalar.db.analytics.labs.semantic.model.BuildTask;
import com.scalar.db.analytics.labs.semantic.model.Knowledge;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.CheckResult;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ValidationMethod;
import com.scalar.db.analytics.labs.semantic.policy.PolicyEvaluationModel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Schema interpretation: registering a data source's tables runs the schema-interpretation
 * Generators through build tasks, and under the default policy the structure that declarations
 * settle is confirmed without review (see {@code docs/semantic-catalog/generators.md}, "Schema
 * interpretation" and "Column changes").
 *
 * <p>The table metadata is the connectors' source-independent form, the same for every connector;
 * the scenarios use the shapes a PostgreSQL source (enforced constraints) and a warehouse whose
 * constraints are informational (unenforced) declare.
 */
class SchemaInterpretationTest extends StoreScenarioTest {
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
  private static final AtomicLong VERSIONS = new AtomicLong();

  /** A policy evaluation model that must not be called: declarations alone settle everything. */
  private static final PolicyEvaluationModel NO_MODEL =
      new PolicyEvaluationModel() {
        @Override
        public JsonNode version() {
          return JSON.objectNode().put("model", "none");
        }

        @Override
        public Decision evaluate(Subject subject) {
          throw new AssertionError(
              "The policy evaluation model was asked about " + subject.fact().revision().fact());
        }
      };

  /** A policy evaluation model that asks for consent and records what it was asked about. */
  private static final class AskingConsent implements PolicyEvaluationModel {
    final List<String> asked = new ArrayList<>();

    @Override
    public JsonNode version() {
      return JSON.objectNode().put("model", "asking-consent");
    }

    @Override
    public synchronized Decision evaluate(Subject subject) {
      asked.add(subject.fact().revision().fact());
      return new Decision.RequestConsent("not settled by declarations", JSON.objectNode());
    }
  }

  // Table metadata in the connectors' JSON form

  private static ObjectNode column(String name, boolean nullable, String analyticsKind) {
    return JSON.objectNode()
        .put("name", name)
        .put("nullable", nullable)
        .put("dataSourceType", analyticsKind)
        .set("analyticsType", JSON.objectNode().put("kind", analyticsKind));
  }

  private static ObjectNode primaryKey(String name, boolean enforced, String... columns) {
    return constraint("primary-key", name, enforced, columns);
  }

  private static ObjectNode unique(String name, String uniqueKind, String... columns) {
    return constraint("unique", name, true, columns).put("uniqueKind", uniqueKind);
  }

  private static ObjectNode uniqueIndex(String name, boolean enforced, String... columns) {
    return constraint("unique", name, enforced, columns).put("uniqueKind", "index");
  }

  /** A column whose type the connector could not read. */
  private static ObjectNode unreadColumn(String name) {
    return JSON.objectNode()
        .put("name", name)
        .put("nullable", false)
        .put("unreadReason", "the type could not be read");
  }

  /** A column whose source type maps to no Analytics type. */
  private static ObjectNode unmappedColumn(String name, String sourceType) {
    return JSON.objectNode()
        .put("name", name)
        .put("nullable", false)
        .put("dataSourceType", sourceType)
        .set("analyticsType", JSON.objectNode().put("unsupported", "not-mapped"));
  }

  private static ObjectNode foreignKey(
      String name, boolean enforced, String column, String table, String referenced) {
    var key = constraint("foreign-key", name, enforced, column);
    key.putArray("referencedTable").add("public").add(table);
    key.putArray("referencedColumns").add(referenced);
    return key;
  }

  private static ObjectNode constraint(
      String kind, String name, boolean enforced, String... columns) {
    var node = JSON.objectNode().put("kind", kind).put("name", name).put("enforced", enforced);
    var array = node.putArray("columns");
    for (var column : columns) {
      array.add(column);
    }
    return node;
  }

  private static ObjectNode table(String name, List<ObjectNode> columns, List<ObjectNode> keys) {
    var node = JSON.objectNode();
    node.putArray("table").add("public").add(name);
    node.put("kind", "table");
    ArrayNode columnArray = node.putArray("columns");
    for (var index = 0; index < columns.size(); index++) {
      columnArray.add(columns.get(index).deepCopy().put("ordinal", index + 1));
    }
    node.put("columnsMayBeHidden", false);
    var constraints = node.putObject("constraints").put("state", "value");
    constraints.putArray("value").addAll(keys);
    return node;
  }

  private static ObjectNode customers() {
    return table(
        "customers",
        List.of(
            column("id", false, "int"),
            column("email", false, "text"),
            column("nickname", true, "text"),
            column("active", false, "boolean"),
            column("created_at", false, "timestamp-tz")),
        List.of(
            primaryKey("customers_pkey", true, "id"),
            unique("customers_email_key", "constraint", "email"),
            unique("customers_nickname_key", "constraint", "nickname")));
  }

  private static ObjectNode orders() {
    return table(
        "orders",
        List.of(
            column("id", false, "int"),
            column("customer_id", false, "int"),
            column("ordered_on", false, "date"),
            column("note", true, "text")),
        List.of(
            primaryKey("orders_pkey", true, "id"),
            foreignKey("orders_customer_id_fkey", true, "customer_id", "customers", "id")));
  }

  /** Registers one table's metadata, with source metadata that differs at every call. */
  private static Observed register(Scenario s, String table, JsonNode tableMetadata) {
    return register(s, "postgresql", table, tableMetadata);
  }

  /** Registers one table's metadata as a connector returned it. */
  private static Observed register(
      Scenario s, String connector, String table, JsonNode tableMetadata) {
    var identifier = "shop.public." + table;
    var version = VERSIONS.incrementAndGet();
    return (Observed)
        store.registerTableMetadata(
            s.domain,
            "app",
            new TableMetadataObservation(
                identifier,
                Optional.of(Scenario.table(identifier)),
                version,
                new Read(
                    true,
                    tableMetadata,
                    new SourceMetadata(connector, JSON.objectNode().set("table", tableMetadata)),
                    Instant.parse("2026-11-16T00:00:00Z")),
                JSON.objectNode(),
                "digest-" + version));
  }

  private static BuildRun.Summary drain(Scenario s, PolicyEvaluationModel model) {
    return store.runBuildTasks(
        s.domain,
        new BuildRun.Environment(model, BuildTasksTest.NO_CONSTRUCTION_SQL),
        BuildRun.drain(),
        task -> {});
  }

  // Reading what was confirmed

  private static String dataset(Scenario s, String table) {
    return Urns.element(s.domain, ElementKind.DATASET, "shop.public." + table);
  }

  private static String field(Scenario s, String column) {
    return Urns.element(s.domain, ElementKind.FIELD, "shop.public." + column);
  }

  /** The uniqueness predicate of the one-Field set of a column. */
  private static String uniqueness(Scenario s, String column) {
    return Vocabulary.UNIQUENESS_PREFIX + field(s, column);
  }

  private static String correspondence(Scenario s, String from, String to) {
    return Urns.edge(
        s.domain,
        ElementKind.IDENTIFIER_CORRESPONDENCE,
        List.of(field(s, from)),
        List.of(field(s, to)));
  }

  /** The value of the Fact selected for an element's predicate at the latest commit. */
  private static JsonNode selected(Scenario s, String element, String predicate) {
    var knowledge = s.only(s.lastCommit(), element, predicate);
    assertThat(knowledge).as(element + " " + predicate).isInstanceOf(Knowledge.Selected.class);
    return ((Knowledge.Selected) knowledge).fact().revision().value();
  }

  /** The only Fact at an element's predicate, whatever its state. */
  private static String onlyFact(Scenario s, String element, String predicate) {
    return Urns.fact(Urns.factAddress(element, predicate), 1);
  }

  private static FactState state(Scenario s, String element, String predicate) {
    return s.state(onlyFact(s, element, predicate)).orElseThrow();
  }

  @Test
  void aRegisteredSourceIsConfirmedWithoutReview() {
    var s = scenario();
    var registered = register(s, "customers", customers());
    register(s, "orders", orders());

    var summary = drain(s, NO_MODEL);

    // Registration created a run of every schema-interpretation Generator in its own commit.
    assertThat(registered.buildTasks()).hasSize(Generators.all().size());
    assertThat(summary.finished())
        .allSatisfy(
            task -> assertThat(task.status()).isInstanceOf(BuildTask.Status.Succeeded.class));
    // Existence and Physical Catalog references of the Datasets and Fields.
    assertThat(selected(s, dataset(s, "orders"), Vocabulary.EXISTENCE).asText())
        .isEqualTo("exists");
    assertThat(selected(s, dataset(s, "orders"), Vocabulary.PHYSICAL_CATALOG_REFERENCE).asText())
        .isEqualTo("shop.public.orders");
    assertThat(selected(s, field(s, "orders.note"), Vocabulary.EXISTENCE).asText())
        .isEqualTo("exists");
    assertThat(selected(s, field(s, "orders.note"), Vocabulary.PHYSICAL_CATALOG_REFERENCE).asText())
        .isEqualTo("shop.public.orders.note");
    // A key from the enforced primary key.
    assertThat(selected(s, dataset(s, "orders"), "key"))
        .isEqualTo(JSON.objectNode().set("fields", JSON.arrayNode().add(field(s, "orders.id"))));
    // Uniqueness from the primary key and a unique constraint on a non-null column, none from one
    // on a nullable column.
    assertThat(selected(s, dataset(s, "orders"), uniqueness(s, "orders.id")).asText())
        .isEqualTo("does-not-repeat");
    assertThat(selected(s, dataset(s, "customers"), uniqueness(s, "customers.email")).asText())
        .isEqualTo("does-not-repeat");
    assertThat(
            s.knowledge(
                s.lastCommit(), dataset(s, "customers"), uniqueness(s, "customers.nickname")))
        .allSatisfy(
            address ->
                assertThat(address.knowledge()).isInstanceOf(Knowledge.MissingKnowledge.class));
    // Value kinds from date, timestamp, and boolean types; none from a text type.
    assertThat(selected(s, field(s, "orders.ordered_on"), "value-kind").asText())
        .isEqualTo("time-or-date");
    assertThat(selected(s, field(s, "customers.created_at"), "value-kind").asText())
        .isEqualTo("time-or-date");
    assertThat(selected(s, field(s, "customers.active"), "value-kind").asText())
        .isEqualTo("boolean");
    assertThat(s.knowledge(s.lastCommit(), field(s, "orders.note"), "value-kind"))
        .allSatisfy(
            address ->
                assertThat(address.knowledge()).isInstanceOf(Knowledge.MissingKnowledge.class));
    // The identifier correspondence an enforced foreign key on integer columns states, with its
    // business definition.
    var edge = correspondence(s, "orders.customer_id", "customers.id");
    assertThat(selected(s, edge, Vocabulary.EXISTENCE).asText()).isEqualTo("exists");
    assertThat(selected(s, edge, "business-definition").asText())
        .contains("shop.public.orders")
        .contains("shop.public.customers");
    // Each Fact records the Generator that produced it and depends on the observation.
    var key = s.revision(onlyFact(s, dataset(s, "orders"), "key"));
    assertThat(key.provenance().path("generator").asText()).isEqualTo("key-from-primary-key");
    assertThat(key.checks())
        .singleElement()
        .satisfies(
            check -> {
              assertThat(check.method()).isEqualTo(ValidationMethod.DECLARED_ENFORCED);
              assertThat(check.result()).isEqualTo(CheckResult.SUPPORTED);
            });
  }

  @Test
  void runningAgainOnTheSameInputRecordsNothing() {
    var s = scenario();
    var customers = register(s, "customers", customers());
    register(s, "orders", orders());
    drain(s, NO_MODEL);
    var commit = s.lastCommit();

    var trigger = Urns.logRecord(s.domain, Urns.position(customers.inputObservation()));
    for (var generator : Generators.all()) {
      store.requestGeneratorRun(s.domain, generator.name(), trigger);
    }
    var summary = drain(s, NO_MODEL);

    assertThat(summary.finished()).hasSize(Generators.all().size());
    assertThat(summary.finished())
        .extracting(task -> ((BuildTask.Status.Succeeded) task.status()).result())
        .allSatisfy(result -> assertThat(((BuildTask.Result.Generated) result).commit()).isNull());
    assertThat(s.lastCommit()).isEqualTo(commit);
  }

  @Test
  void keysAndCorrespondencesFromUnenforcedDeclarationsGoToThePolicyEvaluationModel() {
    var s = scenario();
    register(
        s,
        "members",
        table(
            "members",
            List.of(column("member_id", false, "int")),
            List.of(primaryKey("members_pk", false, "member_id"))));
    register(
        s,
        "visits",
        table(
            "visits",
            List.of(column("visit_id", false, "int"), column("member_id", false, "int")),
            List.of(
                primaryKey("visits_pk", false, "visit_id"),
                foreignKey("visits_member_fk", false, "member_id", "members", "member_id"))));
    var model = new AskingConsent();

    drain(s, model);

    // Existence comes from what the source enforces, so it is still confirmed inline.
    assertThat(state(s, dataset(s, "visits"), Vocabulary.EXISTENCE)).isEqualTo(FactState.CONFIRMED);
    var key = onlyFact(s, dataset(s, "visits"), "key");
    var correspondence =
        onlyFact(
            s, correspondence(s, "visits.member_id", "members.member_id"), Vocabulary.EXISTENCE);
    assertThat(model.asked).contains(key, correspondence);
    assertThat(s.state(key)).contains(FactState.CANDIDATE);
    assertThat(s.revision(key).checks())
        .extracting(check -> check.method())
        .containsExactly(ValidationMethod.DECLARED_UNENFORCED);
  }

  @Test
  void aForeignKeyOnTextColumnsIsNotAdoptedInline() {
    var s = scenario();
    register(s, "customers", customers());
    register(
        s,
        "invoices",
        table(
            "invoices",
            List.of(column("invoice_id", false, "int"), column("email", false, "text")),
            List.of(
                primaryKey("invoices_pkey", true, "invoice_id"),
                foreignKey("invoices_email_fkey", true, "email", "customers", "email"))));
    var model = new AskingConsent();

    drain(s, model);

    var existence =
        onlyFact(s, correspondence(s, "invoices.email", "customers.email"), Vocabulary.EXISTENCE);
    assertThat(model.asked).contains(existence);
    assertThat(s.state(existence)).contains(FactState.CANDIDATE);
    assertThat(s.revision(existence).checks())
        .extracting(check -> check.result())
        .containsExactly(CheckResult.UNDECIDABLE);
  }

  @Test
  void aDisappearedColumnOrDeclarationIsRetiredInline() {
    var s = scenario();
    register(s, "customers", customers());
    register(s, "orders", orders());
    drain(s, NO_MODEL);

    // The note and ordered_on columns and the unique constraint on email disappear.
    var orders = orders();
    var columns = (ArrayNode) orders.get("columns");
    columns.remove(3);
    columns.remove(2);
    register(s, "orders", orders);
    var customers = customers();
    ((ArrayNode) customers.at("/constraints/value")).remove(1);
    register(s, "customers", customers);
    drain(s, NO_MODEL);

    assertThat(state(s, field(s, "orders.note"), Vocabulary.EXISTENCE))
        .isEqualTo(FactState.RETIRED);
    // The value kind of a retired Field is retired by cascade.
    assertThat(state(s, field(s, "orders.ordered_on"), "value-kind")).isEqualTo(FactState.RETIRED);
    assertThat(state(s, dataset(s, "customers"), uniqueness(s, "customers.email")))
        .isEqualTo(FactState.RETIRED);
    // What is still declared stays confirmed.
    assertThat(state(s, field(s, "orders.id"), Vocabulary.EXISTENCE))
        .isEqualTo(FactState.CONFIRMED);
    assertThat(state(s, dataset(s, "orders"), "key")).isEqualTo(FactState.CONFIRMED);
    assertThat(
            state(s, correspondence(s, "orders.customer_id", "customers.id"), Vocabulary.EXISTENCE))
        .isEqualTo(FactState.CONFIRMED);
  }

  @Test
  void aChangedPrimaryKeyProposesAKeyThatReplacesTheOldOneOnceConsentedTo() {
    var s = scenario();
    register(s, "orders", orders());
    drain(s, NO_MODEL);
    var oldKey = onlyFact(s, dataset(s, "orders"), "key");

    var orders = orders();
    ((ArrayNode) orders.at("/constraints/value"))
        .set(0, primaryKey("orders_pkey", true, "id", "customer_id"));
    var changed = register(s, "orders", orders);
    drain(s, NO_MODEL);

    var newKey = Urns.fact(Urns.factAddress(dataset(s, "orders"), "key"), 2);
    // The old declaration is gone, and the new key replaces the old one once consented to; a
    // second Fact at a Fact Address with a selected one always goes to the deciding party.
    assertThat(s.state(oldKey)).contains(FactState.RETIRED);
    assertThat(s.state(newKey)).contains(FactState.CANDIDATE);
    assertThat(s.revision(newKey).dependencies())
        .anySatisfy(dependency -> assertThat(dependency.toString()).contains(oldKey));
    var commit = s.lastCommit();

    store.requestGeneratorRun(
        s.domain,
        "key-from-primary-key",
        Urns.logRecord(s.domain, Urns.position(changed.inputObservation())));
    drain(s, NO_MODEL);

    assertThat(s.lastCommit()).isEqualTo(commit);
  }

  @Test
  void aColumnTheTableMetadataMayHideHasNotDisappeared() {
    var s = scenario();
    register(s, "orders", orders());
    drain(s, NO_MODEL);

    var orders = orders();
    ((ArrayNode) orders.get("columns")).remove(3);
    orders.put("columnsMayBeHidden", true);
    register(s, "orders", orders);
    drain(s, NO_MODEL);

    assertThat(state(s, field(s, "orders.note"), Vocabulary.EXISTENCE))
        .isEqualTo(FactState.CONFIRMED);
  }

  @Test
  void aTableThatNoLongerExistsRetiresItsDatasetAndEverythingOnIt() {
    var s = scenario();
    register(s, "orders", orders());
    drain(s, NO_MODEL);

    var version = VERSIONS.incrementAndGet();
    store.registerTableMetadata(
        s.domain,
        "app",
        new TableMetadataObservation(
            "shop.public.orders",
            Optional.of(Scenario.table("shop.public.orders")),
            version,
            new DoesNotExist(),
            JSON.objectNode(),
            "digest-" + version));
    drain(s, NO_MODEL);

    assertThat(state(s, dataset(s, "orders"), Vocabulary.EXISTENCE)).isEqualTo(FactState.RETIRED);
    assertThat(state(s, field(s, "orders.id"), Vocabulary.EXISTENCE)).isEqualTo(FactState.RETIRED);
    assertThat(state(s, dataset(s, "orders"), "key")).isEqualTo(FactState.RETIRED);
  }

  @Test
  void aForeignKeyToATableRegisteredLaterYieldsItsCorrespondenceThen() {
    var s = scenario();
    var orders = register(s, "orders", orders());
    var first = drain(s, NO_MODEL);

    var edge = correspondence(s, "orders.customer_id", "customers.id");
    assertThat(s.state(onlyFact(s, edge, Vocabulary.EXISTENCE))).isEmpty();
    assertThat(first.finished())
        .filteredOn(
            task ->
                task.work()
                    .equals(new BuildTask.Work.GeneratorRun("correspondence-from-foreign-key")))
        .extracting(
            task ->
                ((BuildTask.Result.Generated) ((BuildTask.Status.Succeeded) task.status()).result())
                    .missingInputs())
        .anySatisfy(missing -> assertThat(missing).anyMatch(item -> item.contains("customers.id")));
    assertThat(orders.buildTasks()).isNotEmpty();

    register(s, "customers", customers());
    drain(s, NO_MODEL);

    assertThat(selected(s, edge, Vocabulary.EXISTENCE).asText()).isEqualTo("exists");
  }

  @Test
  void aTableWithoutAPhysicalCatalogReferenceIsObservedButNotInterpreted() {
    var s = scenario();
    var version = VERSIONS.incrementAndGet();
    var registered =
        (Observed)
            store.registerTableMetadata(
                s.domain,
                "app",
                new TableMetadataObservation(
                    "lake.a.b.events",
                    Optional.empty(),
                    version,
                    new Read(
                        true,
                        table("events", List.of(column("id", false, "int")), List.of()),
                        new SourceMetadata("lake", JSON.objectNode()),
                        Instant.parse("2026-11-16T00:00:00Z")),
                    JSON.objectNode(),
                    "digest-" + version));

    var summary = drain(s, NO_MODEL);

    assertThat(registered.inputObservation()).isNotBlank();
    assertThat(summary.finished())
        .extracting(
            task ->
                ((BuildTask.Result.Generated)
                    ((BuildTask.Status.Succeeded) task.status()).result()))
        .allSatisfy(
            result -> {
              assertThat(result.commit()).isNull();
              assertThat(result.missingInputs())
                  .containsExactly("the Physical Catalog reference of table lake.a.b.events");
            });
  }

  @Test
  void uniqueIndexesAsAMySqlSourceReportsThemAreUniquenessOnNonNullColumnsOnly() {
    var s = scenario();
    // A MySQL source reports uniqueness as unique indexes; an index is enforced whatever the
    // constraint flag says.
    register(
        s,
        "mysql",
        "members",
        table(
            "members",
            List.of(
                column("member_id", false, "int"),
                column("card_no", false, "text"),
                column("referrer", true, "int")),
            List.of(
                primaryKey("PRIMARY", true, "member_id"),
                uniqueIndex("members_card_no", false, "card_no"),
                uniqueIndex("members_referrer", false, "referrer"))));

    drain(s, NO_MODEL);

    assertThat(selected(s, dataset(s, "members"), uniqueness(s, "members.card_no")).asText())
        .isEqualTo("does-not-repeat");
    assertThat(
            s.revision(onlyFact(s, dataset(s, "members"), uniqueness(s, "members.card_no")))
                .checks())
        .extracting(check -> check.method())
        .containsExactly(ValidationMethod.DECLARED_ENFORCED);
    assertThat(s.state(onlyFact(s, dataset(s, "members"), uniqueness(s, "members.referrer"))))
        .isEmpty();
  }

  @Test
  void constraintsTheTableMetadataReportsMissingHaveNotDisappeared() {
    var s = scenario();
    register(s, "customers", customers());
    register(s, "orders", orders());
    drain(s, NO_MODEL);

    var orders = orders();
    orders.putObject("constraints").put("state", "missing").put("reason", "no privilege");
    register(s, "orders", orders);
    drain(s, NO_MODEL);

    assertThat(state(s, dataset(s, "orders"), "key")).isEqualTo(FactState.CONFIRMED);
    assertThat(state(s, dataset(s, "orders"), uniqueness(s, "orders.id")))
        .isEqualTo(FactState.CONFIRMED);
    assertThat(
            state(s, correspondence(s, "orders.customer_id", "customers.id"), Vocabulary.EXISTENCE))
        .isEqualTo(FactState.CONFIRMED);
  }

  @Test
  void aColumnWhoseTypeCouldNotBeReadKeepsItsValueKind() {
    var s = scenario();
    register(s, "orders", orders());
    drain(s, NO_MODEL);

    var orders = orders();
    ((ArrayNode) orders.get("columns")).set(2, unreadColumn("ordered_on").put("ordinal", 3));
    register(s, "orders", orders);
    drain(s, NO_MODEL);

    assertThat(state(s, field(s, "orders.ordered_on"), "value-kind"))
        .isEqualTo(FactState.CONFIRMED);
  }

  @Test
  void aForeignKeyOnColumnsOfAnUnmappedTypeIsNotAdoptedInline() {
    var s = scenario();
    register(
        s,
        "accounts",
        table(
            "accounts",
            List.of(unmappedColumn("code", "citext")),
            List.of(primaryKey("accounts_pkey", true, "code"))));
    register(
        s,
        "entries",
        table(
            "entries",
            List.of(column("entry_id", false, "int"), unmappedColumn("account", "citext")),
            List.of(
                primaryKey("entries_pkey", true, "entry_id"),
                foreignKey("entries_account_fkey", true, "account", "accounts", "code"))));
    var model = new AskingConsent();

    drain(s, model);

    var existence =
        onlyFact(s, correspondence(s, "entries.account", "accounts.code"), Vocabulary.EXISTENCE);
    assertThat(s.state(existence)).contains(FactState.CANDIDATE);
    assertThat(s.revision(existence).checks())
        .extracting(check -> check.result())
        .containsExactly(CheckResult.UNDECIDABLE);
  }

  @Test
  void foreignKeysStatingOneCorrespondenceSupportOneCandidateAndARenameChangesNothing() {
    var s = scenario();
    register(s, "customers", customers());
    var orders = orders();
    ((ArrayNode) orders.at("/constraints/value"))
        .add(foreignKey("orders_customer_fk2", true, "customer_id", "customers", "id"));
    var registered = register(s, "orders", orders);
    drain(s, NO_MODEL);
    var edge = correspondence(s, "orders.customer_id", "customers.id");
    var definition = onlyFact(s, edge, "business-definition");

    // Both keys support the one correspondence.
    assertThat(s.revision(onlyFact(s, edge, Vocabulary.EXISTENCE)).checks()).hasSize(2);
    assertThat(s.state(Urns.fact(Urns.factAddress(edge, "business-definition"), 2))).isEmpty();
    // Running again records nothing, although the two keys' checks differ.
    var commit = s.lastCommit();
    store.requestGeneratorRun(
        s.domain,
        "correspondence-from-foreign-key",
        Urns.logRecord(s.domain, Urns.position(registered.inputObservation())));
    drain(s, NO_MODEL);
    assertThat(s.lastCommit()).isEqualTo(commit);

    // Renaming a key keeps the same business definition.
    var renamed = orders();
    ((ArrayNode) renamed.at("/constraints/value"))
        .set(1, foreignKey("orders_customer_ref", true, "customer_id", "customers", "id"));
    register(s, "orders", renamed);
    drain(s, NO_MODEL);

    assertThat(s.state(definition)).contains(FactState.CONFIRMED);
    assertThat(selected(s, edge, "business-definition").asText()).doesNotContain("orders_customer");
    assertThat(s.state(Urns.fact(Urns.factAddress(edge, "business-definition"), 2))).isEmpty();
  }
}
