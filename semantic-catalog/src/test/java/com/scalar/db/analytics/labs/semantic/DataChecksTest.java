/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scalar.db.analytics.labs.semantic.TableMetadataObservation.Read;
import com.scalar.db.analytics.labs.semantic.TableMetadataObservation.SourceMetadata;
import com.scalar.db.analytics.labs.semantic.model.BuildTask;
import com.scalar.db.analytics.labs.semantic.model.Check;
import com.scalar.db.analytics.labs.semantic.model.Dependency;
import com.scalar.db.analytics.labs.semantic.model.FactRevision;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.CheckResult;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ConfirmationPath;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.DataFinding;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ValidationMethod;
import com.scalar.db.analytics.labs.semantic.policy.PolicyEvaluationModel;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Data checks: once schema interpretation confirms the structure a source declares, the data-check
 * Generators read the data through construction-run SQL and record what they read as data
 * observations (see {@code docs/semantic-catalog/generators.md}, "Data checks").
 *
 * <p>The data lives in an in-memory DuckDB, the Query Engine's dialect, under the same
 * registration, namespace, and table names the table metadata gives. Data refutes but does not
 * establish: a counterexample is adopted inline, while finding none confirms only what a
 * declaration also supports and is otherwise left to the policy evaluation model, here one that
 * asks for consent.
 */
class DataChecksTest extends StoreScenarioTest {
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final AtomicLong VERSIONS = new AtomicLong();

  private final List<DuckDbConstructionSql> engines = new ArrayList<>();

  @AfterEach
  void closeEngines() throws SQLException {
    for (var engine : engines) {
      engine.close();
    }
  }

  private DuckDbConstructionSql engine(long sampleSize) {
    var engine =
        new DuckDbConstructionSql(sampleSize)
            .run("ATTACH ':memory:' AS shop", "CREATE SCHEMA shop.public");
    engines.add(engine);
    return engine;
  }

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
      return new Decision.RequestConsent("not settled by deterministic checks", JSON.objectNode());
    }
  }

  // Table metadata in the connectors' JSON form

  private static ObjectNode column(String name, String analyticsKind) {
    return JSON.objectNode()
        .put("name", name)
        .put("nullable", false)
        .put("dataSourceType", analyticsKind)
        .set("analyticsType", JSON.objectNode().put("kind", analyticsKind));
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

  private static ObjectNode primaryKey(String name, boolean enforced, String... columns) {
    return constraint("primary-key", name, enforced, columns);
  }

  private static ObjectNode unique(String name, boolean enforced, String... columns) {
    return constraint("unique", name, enforced, columns).put("uniqueKind", "constraint");
  }

  private static ObjectNode foreignKey(String name, String column, String table, String to) {
    var key = constraint("foreign-key", name, true, column);
    key.putArray("referencedTable").add("public").add(table);
    key.putArray("referencedColumns").add(to);
    return key;
  }

  private static ObjectNode table(String name, List<ObjectNode> columns, List<ObjectNode> keys) {
    var node = JSON.objectNode();
    node.putArray("table").add("public").add(name);
    node.put("kind", "table");
    var columnArray = node.putArray("columns");
    for (var index = 0; index < columns.size(); index++) {
      columnArray.add(columns.get(index).deepCopy().put("ordinal", index + 1));
    }
    node.put("columnsMayBeHidden", false);
    var constraints = node.putObject("constraints").put("state", "value");
    constraints.putArray("value").addAll(keys);
    return node;
  }

  /** A warehouse table whose constraints are informational: an unenforced key and unique code. */
  private static ObjectNode products() {
    return table(
        "products",
        List.of(column("id", "int"), column("code", "text")),
        List.of(
            primaryKey("products_pkey", false, "id"), unique("products_code_key", false, "code")));
  }

  private static ObjectNode customers() {
    return table(
        "customers",
        List.of(column("id", "int"), column("name", "text")),
        List.of(primaryKey("customers_pkey", true, "id")));
  }

  private static ObjectNode orders() {
    return table(
        "orders",
        List.of(column("id", "int"), column("customer_id", "int")),
        List.of(
            primaryKey("orders_pkey", true, "id"),
            foreignKey("orders_customer_id_fkey", "customer_id", "customers", "id")));
  }

  /** A table that declares no constraint at all, as a log of events often does. */
  private static ObjectNode events() {
    return table(
        "events",
        List.of(column("event_id", "int"), column("customer_ref", "int"), column("kind", "text")),
        List.of());
  }

  /** Registers one table's metadata, with source metadata that differs at every call. */
  private static void register(Scenario s, String table, JsonNode tableMetadata) {
    var identifier = "shop.public." + table;
    var version = VERSIONS.incrementAndGet();
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
                new SourceMetadata(
                    "postgresql",
                    JSON.objectNode().put("version", version).set("table", tableMetadata)),
                Instant.parse("2026-11-16T00:00:00Z")),
            JSON.objectNode(),
            "digest-" + version));
  }

  private static BuildRun.Summary drain(
      Scenario s, PolicyEvaluationModel model, ConstructionSql sql) {
    var summary =
        store.runBuildTasks(
            s.domain, new BuildRun.Environment(model, sql), BuildRun.drain(), task -> {});
    assertThat(summary.finished())
        .allSatisfy(
            task -> assertThat(task.status()).isInstanceOf(BuildTask.Status.Succeeded.class));
    return summary;
  }

  // Reading what was recorded

  private static String dataset(Scenario s, String table) {
    return Urns.element(s.domain, ElementKind.DATASET, "shop.public." + table);
  }

  private static String field(Scenario s, String column) {
    return Urns.element(s.domain, ElementKind.FIELD, "shop.public." + column);
  }

  private static String uniqueness(Scenario s, String... columns) {
    return Vocabulary.UNIQUENESS_PREFIX
        + String.join(
            ",",
            java.util.Arrays.stream(columns).map(column -> field(s, column)).sorted().toList());
  }

  private static String correspondence(Scenario s, String from, String to) {
    return Urns.edge(
        s.domain,
        ElementKind.IDENTIFIER_CORRESPONDENCE,
        List.of(field(s, from)),
        List.of(field(s, to)));
  }

  /** The latest revision of the Fact with a value at an element's predicate. */
  private static FactRevision fact(Scenario s, String element, String predicate, String value) {
    return find(s, element, predicate, value)
        .orElseThrow(() -> new AssertionError("No " + value + " at " + element + " " + predicate));
  }

  private static Optional<FactRevision> find(
      Scenario s, String element, String predicate, String value) {
    var address = Urns.factAddress(element, predicate);
    for (var number = 1; number <= 10; number++) {
      var urn = Urns.fact(address, number);
      var revision = s.latest(snapshot -> snapshot.latestRevision(urn));
      if (revision.isEmpty()) {
        break;
      }
      if (revision.get().value().asText().equals(value)) {
        return revision;
      }
    }
    return Optional.empty();
  }

  private static List<Check> dataChecks(FactRevision revision) {
    return revision.checks().stream()
        .filter(check -> check.method() == ValidationMethod.DATA_CHECK)
        .toList();
  }

  /** The content of the data observation a check references. */
  private static JsonNode observation(Scenario s, Check check) throws Exception {
    var position = Urns.position(java.util.Objects.requireNonNull(check.reference()).urnValue());
    try (var connection = TestStore.connect();
        var statement =
            connection.prepareStatement(
                "SELECT content::text FROM semantic_catalog.log_records"
                    + " WHERE domain = ? AND commit_number = ? AND ordinal = ?")) {
      statement.setString(1, s.domain);
      statement.setLong(2, position.commit());
      statement.setInt(3, position.ordinal());
      try (var result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        return MAPPER.readTree(result.getString(1)).path("content");
      }
    }
  }

  /** The retirement proposals of a Fact, as stored. */
  private static List<JsonNode> retirementProposals(Scenario s, String fact) throws Exception {
    var proposals = new ArrayList<JsonNode>();
    try (var connection = TestStore.connect();
        var statement =
            connection.prepareStatement(
                "SELECT content::text FROM semantic_catalog.log_records"
                    + " WHERE domain = ? AND target_fact = ? AND kind = 'propose-retire'"
                    + " ORDER BY commit_number, ordinal")) {
      statement.setString(1, s.domain);
      statement.setString(2, fact);
      try (var result = statement.executeQuery()) {
        while (result.next()) {
          proposals.add(MAPPER.readTree(result.getString(1)));
        }
      }
    }
    return proposals;
  }

  // Uniqueness

  @Test
  void uniquenessAnUnenforcedDeclarationStatesIsConfirmedOnceTheDataShowsNoRepetition() {
    var s = scenario();
    var sql =
        engine(1000)
            .run(
                "CREATE TABLE shop.public.products (id INTEGER, code VARCHAR)",
                "INSERT INTO shop.public.products VALUES (1, 'a'), (2, 'b'), (3, 'c')");
    register(s, "products", products());

    drain(s, new AskingConsent(), sql);

    var code = fact(s, dataset(s, "products"), uniqueness(s, "products.code"), "does-not-repeat");
    assertThat(code.state()).isEqualTo(FactState.CONFIRMED);
    assertThat(code.confirmationPath()).isEqualTo(ConfirmationPath.MACHINE_JUDGEMENT);
    // The data check's acceptance restates the declaration's check alongside its own.
    assertThat(code.checks())
        .extracting(Check::method)
        .contains(ValidationMethod.DECLARED_UNENFORCED, ValidationMethod.DATA_CHECK);
    assertThat(dataChecks(code))
        .singleElement()
        .satisfies(
            check -> {
              assertThat(check.result()).isEqualTo(CheckResult.SUPPORTED);
              assertThat(check.finding()).isEqualTo(DataFinding.NO_COUNTEREXAMPLE_FOUND);
            });
    // The key from the unenforced primary key still waits: no repetition does not show that a set
    // is a business identifier.
    var key =
        s.latest(
            snapshot ->
                snapshot
                    .latestRevision(
                        Urns.fact(Urns.factAddress(dataset(s, "products"), Vocabulary.KEY), 1))
                    .orElseThrow());
    assertThat(key.state()).isEqualTo(FactState.CANDIDATE);
  }

  @Test
  void aRepeatingValueIsAdoptedInlineAndRefutesTheDeclaredUniqueness() {
    var s = scenario();
    var sql =
        engine(1000)
            .run(
                "CREATE TABLE shop.public.products (id INTEGER, code VARCHAR)",
                "INSERT INTO shop.public.products VALUES (1, 'a'), (2, 'a'), (3, 'c')");
    register(s, "products", products());

    drain(s, new AskingConsent(), sql);

    var element = dataset(s, "products");
    var predicate = uniqueness(s, "products.code");
    var repeats = fact(s, element, predicate, "repeats");
    assertThat(repeats.state()).isEqualTo(FactState.CONFIRMED);
    assertThat(repeats.confirmationPath()).isEqualTo(ConfirmationPath.MACHINE_JUDGEMENT);
    assertThat(dataChecks(repeats))
        .singleElement()
        .satisfies(
            check -> assertThat(check.finding()).isEqualTo(DataFinding.COUNTEREXAMPLE_FOUND));
    var declared = fact(s, element, predicate, "does-not-repeat");
    assertThat(declared.state()).isEqualTo(FactState.CANDIDATE);
    assertThat(dataChecks(declared))
        .singleElement()
        .satisfies(
            check -> {
              assertThat(check.result()).isEqualTo(CheckResult.REFUTED);
              assertThat(check.finding()).isEqualTo(DataFinding.COUNTEREXAMPLE_FOUND);
            });
  }

  @Test
  void aRepetitionFoundLaterRetiresTheConfirmedUniquenessAsAWrongConfirmation() throws Exception {
    var s = scenario();
    var sql =
        engine(1000)
            .run(
                "CREATE TABLE shop.public.products (id INTEGER, code VARCHAR)",
                "INSERT INTO shop.public.products VALUES (1, 'a'), (2, 'b')");
    register(s, "products", products());
    drain(s, new AskingConsent(), sql);
    var element = dataset(s, "products");
    var predicate = uniqueness(s, "products.code");
    var confirmed = fact(s, element, predicate, "does-not-repeat");
    assertThat(confirmed.state()).isEqualTo(FactState.CONFIRMED);

    // The data changes and a new physical schema observation of the table examines it again.
    sql.run("INSERT INTO shop.public.products VALUES (3, 'b')");
    register(s, "products", products());
    drain(s, new AskingConsent(), sql);

    assertThat(s.state(confirmed.fact())).contains(FactState.RETIRED);
    assertThat(retirementProposals(s, confirmed.fact()))
        .singleElement()
        .satisfies(
            proposal ->
                assertThat(proposal.path("reason").asText()).isEqualTo("CONFIRMATION_WAS_WRONG"));
    assertThat(fact(s, element, predicate, "repeats").state()).isNotEqualTo(FactState.REJECTED);
  }

  @Test
  void aReadEqualToThePreviousObservationIsNotRecordedAgain() throws Exception {
    var s = scenario();
    var sql =
        engine(1000)
            .run(
                "CREATE TABLE shop.public.products (id INTEGER, code VARCHAR)",
                "INSERT INTO shop.public.products VALUES (1, 'a'), (2, 'b')");
    register(s, "products", products());
    drain(s, new AskingConsent(), sql);
    var commit = s.lastCommit();
    var reads = sql.requests.size();
    var existence = Urns.fact(Urns.factAddress(field(s, "products.code"), Vocabulary.EXISTENCE), 1);

    // The same Field's confirmation calls for the check again; it reads again and records nothing.
    store.requestGeneratorRun(s.domain, "uniqueness-check", confirmation(s, existence));
    drain(s, new AskingConsent(), sql);

    assertThat(sql.requests.size()).isGreaterThan(reads);
    assertThat(s.lastCommit()).isEqualTo(commit);
  }

  /** The log record of a Fact's confirmation. */
  private static String confirmation(Scenario s, String fact) throws Exception {
    try (var connection = TestStore.connect();
        var statement =
            connection.prepareStatement(
                "SELECT commit_number, ordinal FROM semantic_catalog.log_records"
                    + " WHERE domain = ? AND target_fact = ? AND kind = 'confirm'")) {
      statement.setString(1, s.domain);
      statement.setString(2, fact);
      try (var result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        return Urns.logRecord(
            s.domain,
            new com.scalar.db.analytics.labs.semantic.model.LogPosition(
                result.getLong(1), result.getInt(2)));
      }
    }
  }

  @Test
  void aQueryOverALimitLeavesTheItemsUndecidable() throws Exception {
    var s = scenario();
    var sql = engine(1000).run("CREATE TABLE shop.public.products (id INTEGER, code VARCHAR)");
    sql.exceedLimits();
    register(s, "products", products());

    drain(s, new AskingConsent(), sql);

    var code = fact(s, dataset(s, "products"), uniqueness(s, "products.code"), "does-not-repeat");
    assertThat(code.state()).isEqualTo(FactState.CANDIDATE);
    var check = dataChecks(code).getFirst();
    assertThat(check.result()).isEqualTo(CheckResult.UNDECIDABLE);
    var observed = observation(s, check);
    assertThat(observed.path("check").asText()).isEqualTo("uniqueness-check");
    assertThat(observed.path("statements").get(0).path("outcome").path("state").asText())
        .isEqualTo("limit-exceeded");
  }

  // Set relationships

  @Test
  void anEnforcedForeignKeyWithUnreferencedRowsYieldsSubsetByMachineJudgement() throws Exception {
    var s = scenario();
    var sql =
        engine(2)
            .run(
                "CREATE TABLE shop.public.customers (id INTEGER, name VARCHAR)",
                "INSERT INTO shop.public.customers VALUES (1, 'a'), (2, 'b'), (3, 'c')",
                "CREATE TABLE shop.public.orders (id INTEGER, customer_id INTEGER)",
                "INSERT INTO shop.public.orders VALUES (10, 1), (11, 1), (12, 2)");
    register(s, "customers", customers());
    register(s, "orders", orders());

    drain(s, new AskingConsent(), sql);

    var edge = correspondence(s, "orders.customer_id", "customers.id");
    var subset = fact(s, edge, Vocabulary.SET_RELATIONSHIP, "subset");
    assertThat(subset.state()).isEqualTo(FactState.CONFIRMED);
    assertThat(subset.confirmationPath()).isEqualTo(ConfirmationPath.MACHINE_JUDGEMENT);
    // The key supports the containment of the referencing side; the data shows the rest.
    assertThat(subset.checks())
        .anySatisfy(
            check -> {
              assertThat(check.method()).isEqualTo(ValidationMethod.DECLARED_ENFORCED);
              assertThat(check.items().path("every-value-matched").asText()).isEqualTo("from");
            });
    // How many rows match across the correspondence: a customer has several orders.
    var repeats = fact(s, dataset(s, "orders"), uniqueness(s, "orders.customer_id"), "repeats");
    assertThat(repeats.state()).isEqualTo(FactState.CONFIRMED);

    // Each direction samples only the side it checks and looks values up in the whole other side.
    var observed = observation(s, dataChecks(subset).getFirst());
    var fromInputs = observed.path("statements").get(0).path("inputs");
    assertThat(fromInputs.get(0).path("table").asText()).isEqualTo("shop.public.orders");
    assertThat(fromInputs.get(0).path("reading").asText()).isEqualTo("sampled");
    assertThat(fromInputs.get(1).path("reading").asText()).isEqualTo("whole");
    var ranges = observed.path("statements").get(0).path("outcome").path("ranges");
    assertThat(ranges.get(0).path("extent").asText()).isEqualTo("sample");
    assertThat(ranges.get(0).path("rowsRead").asLong()).isEqualTo(2);
    assertThat(ranges.get(0).path("readAt").asText()).isNotBlank();
    assertThat(ranges.get(1).path("extent").asText()).isEqualTo("whole-table");
    assertThat(ranges.get(1).path("rowsRead").asLong()).isEqualTo(3);
    // Data checks run aggregate queries only.
    assertThat(sql.requests)
        .allSatisfy(request -> assertThat(request.sql()).startsWith("SELECT CAST(coalesce("));
  }

  @Test
  void aNewObservationOfTheReferencedTableExaminesTheSetRelationshipAgain() {
    var s = scenario();
    var sql =
        engine(1000)
            .run(
                "CREATE TABLE shop.public.customers (id INTEGER, name VARCHAR)",
                "INSERT INTO shop.public.customers VALUES (1, 'a'), (2, 'b'), (3, 'c')",
                "CREATE TABLE shop.public.orders (id INTEGER, customer_id INTEGER)",
                "INSERT INTO shop.public.orders VALUES (10, 1), (11, 2)");
    register(s, "customers", customers());
    register(s, "orders", orders());
    drain(s, new AskingConsent(), sql);
    var edge = correspondence(s, "orders.customer_id", "customers.id");
    assertThat(fact(s, edge, Vocabulary.SET_RELATIONSHIP, "subset").state())
        .isEqualTo(FactState.CONFIRMED);

    // Customer 3 leaves, and only the referenced table is observed again.
    sql.run("DELETE FROM shop.public.customers WHERE id = 3");
    register(s, "customers", customers());
    drain(s, new AskingConsent(), sql);

    assertThat(find(s, edge, Vocabulary.SET_RELATIONSHIP, "equal")).isPresent();
  }

  @Test
  void aFailedQueryLeavesItsTargetUnreadAndTheOthersRecorded() {
    var s = scenario();
    // The orders table is registered but missing from the engine, so every query on it fails.
    var sql =
        engine(1000)
            .run(
                "CREATE TABLE shop.public.customers (id INTEGER, name VARCHAR)",
                "INSERT INTO shop.public.customers VALUES (1, 'a'), (2, 'b')");
    register(s, "customers", customers());
    register(s, "orders", orders());

    var summary = drain(s, new AskingConsent(), sql);

    assertThat(summary.finished())
        .anySatisfy(
            task ->
                assertThat(
                        ((BuildTask.Result.Generated)
                                ((BuildTask.Status.Succeeded) task.status()).result())
                            .missingInputs())
                    .anySatisfy(missing -> assertThat(missing).contains("which failed")));
    var customerId =
        fact(s, dataset(s, "customers"), uniqueness(s, "customers.id"), "does-not-repeat");
    assertThat(dataChecks(customerId)).isNotEmpty();
  }

  @Test
  void aTableWhoseConstraintsAreNotReportedIsNotExploredForKeys() {
    var s = scenario();
    var sql =
        engine(1000)
            .run(
                "CREATE TABLE shop.public.events"
                    + " (event_id INTEGER, customer_ref INTEGER, kind VARCHAR)",
                "INSERT INTO shop.public.events VALUES (100, 1, 'x'), (101, 2, 'x')");
    var metadata = events();
    metadata.putObject("constraints").put("state", "missing");
    register(s, "events", metadata);

    drain(s, new AskingConsent(), sql);

    assertThat(find(s, dataset(s, "events"), uniqueness(s, "events.event_id"), "does-not-repeat"))
        .isEmpty();
    assertThat(sql.requests).isEmpty();
  }

  // Discovery

  @Test
  void discoveredKeysAndCorrespondencesWaitForThePolicyEvaluationModel() throws Exception {
    var s = scenario();
    var sql =
        engine(1000)
            .run(
                "CREATE TABLE shop.public.customers (id INTEGER, name VARCHAR)",
                "INSERT INTO shop.public.customers VALUES (1, 'a'), (2, 'b'), (3, 'c')",
                "CREATE TABLE shop.public.events"
                    + " (event_id INTEGER, customer_ref INTEGER, kind VARCHAR)",
                "INSERT INTO shop.public.events VALUES (100, 1, 'x'), (101, 2, 'x'), (102, 1, 'y')");
    register(s, "customers", customers());
    register(s, "events", events());
    var model = new AskingConsent();

    drain(s, model, sql);

    var events = dataset(s, "events");
    // Candidate key discovery: a single unique column, and a pair of columns neither of which is
    // unique alone.
    var single = fact(s, events, uniqueness(s, "events.event_id"), "does-not-repeat");
    assertThat(single.state()).isEqualTo(FactState.CANDIDATE);
    assertThat(dataChecks(single))
        .singleElement()
        .satisfies(
            check -> assertThat(check.finding()).isEqualTo(DataFinding.NO_COUNTEREXAMPLE_FOUND));
    var pair =
        fact(s, events, uniqueness(s, "events.customer_ref", "events.kind"), "does-not-repeat");
    assertThat(pair.state()).isEqualTo(FactState.CANDIDATE);
    var key =
        s.latest(
            snapshot ->
                snapshot
                    .latestRevision(Urns.fact(Urns.factAddress(events, Vocabulary.KEY), 1))
                    .orElseThrow());
    assertThat(key.value())
        .isEqualTo(
            JSON.objectNode().set("fields", JSON.arrayNode().add(field(s, "events.event_id"))));
    assertThat(key.state()).isEqualTo(FactState.CANDIDATE);
    assertThat(key.checks())
        .singleElement()
        .satisfies(check -> assertThat(check.method()).isEqualTo(ValidationMethod.INFERENCE));

    // Inclusion discovery: every customer reference is a customer ID.
    var edge = correspondence(s, "events.customer_ref", "customers.id");
    var existence = fact(s, edge, Vocabulary.EXISTENCE, "exists");
    assertThat(existence.state()).isEqualTo(FactState.CANDIDATE);
    assertThat(existence.checks())
        .singleElement()
        .satisfies(
            check -> {
              assertThat(check.method()).isEqualTo(ValidationMethod.INFERENCE);
              assertThat(check.reference()).isNull();
            });
    // The inclusion observation is a dependency and an input read, not a check reference.
    assertThat(existence.dependencies())
        .anySatisfy(dependency -> assertThat(dependency).isInstanceOf(Dependency.OnInput.class));
    assertThat(model.asked).contains(single.fact(), key.fact(), existence.fact());
    assertThat(
            find(
                s,
                correspondence(s, "events.event_id", "customers.id"),
                Vocabulary.EXISTENCE,
                "exists"))
        .isEmpty();
  }
}
