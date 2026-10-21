/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.analytics.labs.application.ApplicationFailure;
import com.scalar.db.analytics.labs.application.workspace.Workspace;
import com.scalar.db.analytics.labs.physical.DataSourceConnection;
import com.scalar.db.analytics.labs.physical.Secret;
import com.scalar.db.analytics.labs.semantic.BuildTask;
import com.scalar.db.analytics.labs.semantic.SnapshotReads.Request;
import com.scalar.db.analytics.labs.semantic.model.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Json;
import com.scalar.db.analytics.labs.semantic.model.Predicates;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Builds the Semantic Catalog of a workspace from real PostgreSQL and MySQL sources, with no
 * language model: declared constraints become confirmed knowledge, and data checks run through the
 * Query Engine.
 */
@Testcontainers
class SemanticSessionIntegrationTest {
  private static final String MYSQL_PASSWORD = "mysql-" + UUID.randomUUID();
  private static final String READER_PASSWORD = "reader-" + UUID.randomUUID();

  @Container
  private static final PostgreSQLContainer<?> STORE =
      new PostgreSQLContainer<>("postgres:16.4").withPassword("store-" + UUID.randomUUID());

  @Container
  private static final PostgreSQLContainer<?> SHOP =
      new PostgreSQLContainer<>("postgres:16.4").withPassword("shop-" + UUID.randomUUID());

  @Container
  private static final GenericContainer<?> MYSQL =
      new GenericContainer<>("mysql:8.4")
          .withEnv("MYSQL_ROOT_PASSWORD", "root-" + UUID.randomUUID())
          .withEnv("MYSQL_DATABASE", "crm")
          .withEnv("MYSQL_USER", "reader")
          .withEnv("MYSQL_PASSWORD", MYSQL_PASSWORD)
          .withExposedPorts(3306)
          .waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306 .*", 1));

  @TempDir Path directory;

  @BeforeAll
  static void prepareSources() throws SQLException {
    try (var connection =
            DriverManager.getConnection(SHOP.getJdbcUrl(), SHOP.getUsername(), SHOP.getPassword());
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE customers (id INT PRIMARY KEY, email VARCHAR(100) NOT NULL UNIQUE)");
      statement.execute(
          "CREATE TABLE orders (id INT PRIMARY KEY, customer_id INT NOT NULL REFERENCES customers(id), amount INT NOT NULL, ordered_on DATE NOT NULL)");
      statement.execute(
          "INSERT INTO customers VALUES (1, 'a@example.com'), (2, 'b@example.com'), (3, 'c@example.com')");
      statement.execute(
          "INSERT INTO orders VALUES (10, 1, 100, DATE '2026-09-01'), (11, 1, 50, DATE '2026-09-02'), (12, 2, 70, DATE '2026-09-03')");
      // A read-only account, as the runbook grants the CLI.
      statement.execute("CREATE ROLE reader LOGIN PASSWORD '" + READER_PASSWORD + "'");
      statement.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO reader");
    }
    try (var connection =
            DriverManager.getConnection(
                "jdbc:mariadb://"
                    + MYSQL.getHost()
                    + ":"
                    + MYSQL.getMappedPort(3306)
                    + "/crm?sslMode=trust",
                "reader",
                MYSQL_PASSWORD);
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE members (member_id INT PRIMARY KEY, email VARCHAR(100) NOT NULL, `rank` VARCHAR(10) NOT NULL)");
      statement.execute(
          "INSERT INTO members VALUES (100, 'a@example.com', 'gold'), (101, 'z@example.com', 'silver')");
    }
  }

  private Workspace workspace() {
    var environment = new HashMap<String, String>();
    environment.put("STORE_PASSWORD", STORE.getPassword());
    environment.put("SCALARDB_ANALYTICS_LLM", "none");
    var catalog = "c" + UUID.randomUUID().toString().replace("-", "");
    var workspace =
        Workspace.initialize(
            directory.resolve("workspace"),
            catalog,
            STORE.getJdbcUrl(),
            STORE.getUsername(),
            "STORE_PASSWORD",
            List.of("owner"),
            environment);
    try (var runtime = workspace.openRuntime()) {
      runtime
          .physicalCatalog()
          .registerDataSource(
              catalog,
              "shop",
              new DataSourceConnection.PostgreSql(
                  SHOP.getHost(),
                  SHOP.getMappedPort(5432),
                  SHOP.getDatabaseName(),
                  "reader",
                  Secret.of(READER_PASSWORD)));
      runtime
          .physicalCatalog()
          .registerDataSource(
              catalog,
              "crm",
              new DataSourceConnection.MySql(
                  MYSQL.getHost(),
                  MYSQL.getMappedPort(3306),
                  Optional.of("crm"),
                  "reader",
                  Secret.of(MYSQL_PASSWORD)));
    }
    return Workspace.load(workspace.directory(), environment);
  }

  @Test
  void declaredConstraintsAndDataChecksBecomeConfirmedKnowledge() throws Exception {
    var workspace = workspace();
    assertThat(
            PosixFilePermissions.toString(
                Files.getPosixFilePermissions(workspace.semanticCatalogFile())))
        .isEqualTo("rw-------");

    var runs =
        SemanticSession.execute(
            workspace,
            session -> {
              var registered = session.refetch("owner", Optional.empty());
              assertThat(registered).hasSize(3);
              return session.runCreated(100);
            });

    // Without a language model the LLM Generators fail and can be resumed later; the rest succeed.
    assertThat(runs)
        .filteredOn(run -> run.state() == BuildTask.State.FAILED)
        .allSatisfy(run -> assertThat(run.failureReason()).contains("No language model"));
    assertThat(runs).filteredOn(run -> run.state() == BuildTask.State.SUCCEEDED).isNotEmpty();

    SemanticSession.execute(
        workspace,
        session -> {
          var reads = session.catalog().reads();
          var orders = Urns.dataset(workspace.catalogName(), "shop.public.orders");
          var customers = Urns.dataset(workspace.catalogName(), "shop.public.customers");
          assertThat(reads.findElements(null, ElementKind.DATASET, null, null).elements())
              .hasSize(3);
          var joins = reads.findElements(null, ElementKind.JOIN, null, null).elements();
          assertThat(joins).hasSize(1);
          assertThat(joins.getFirst().from()).isEqualTo(orders);
          assertThat(joins.getFirst().to()).isEqualTo(customers);
          var knowledge =
              reads.requestKnowledge(
                  null,
                  List.of(
                      new Request(
                          customers, null, null, null, Predicates.uniqueness(List.of("email"))),
                      new Request(
                          joins.getFirst().element(), null, null, null, Predicates.MULTIPLICITY),
                      new Request(
                          Urns.dataset(workspace.catalogName(), "crm.crm.members"),
                          null,
                          null,
                          null,
                          Predicates.KEY)),
                  null);
          var uniqueness =
              Objects.requireNonNull(
                  knowledge.responses().get(0).addresses().getFirst().selected());
          assertThat(uniqueness.checks())
              .extracting(check -> Json.tree(check.method()).asText())
              .contains("declared-enforced", "data-check");
          var multiplicity =
              Objects.requireNonNull(
                  knowledge.responses().get(1).addresses().getFirst().selected());
          assertThat(multiplicity.value().path("multiplicity").asText()).isEqualTo("many-to-one");
          assertThat(knowledge.responses().get(2).addresses().getFirst().selected()).isNotNull();
          assertThat(session.catalog().verify().consistent()).isTrue();
          return true;
        });
  }

  @Test
  void onlyASemanticsOwnerIsAuthorized() {
    var workspace = workspace();
    SemanticSession.execute(
        workspace,
        session -> {
          assertThat(session.authorize("owner")).startsWith("authz:owner:");
          assertThatThrownBy(() -> session.authorize("someone"))
              .isInstanceOf(ApplicationFailure.class)
              .extracting(e -> ((ApplicationFailure) e).code())
              .isEqualTo("NO_AUTHORITY");
          return true;
        });
  }
}
