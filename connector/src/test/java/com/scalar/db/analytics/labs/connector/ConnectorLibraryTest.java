/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ConnectorLibraryTest {
  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  @Container
  private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4");

  private static ConnectorLibrary library;

  @BeforeAll
  static void setUp() throws Exception {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE customers (id integer PRIMARY KEY, email text UNIQUE)");
      statement.execute(
          "CREATE TABLE orders ("
              + "id bigint PRIMARY KEY,"
              + " customer_id integer NOT NULL REFERENCES customers (id),"
              + " amount numeric(10, 2) CHECK (amount >= 0),"
              + " tags text[],"
              + " location point)");
    }
    library = ConnectorLibrary.open();
  }

  @AfterAll
  static void tearDown() throws Exception {
    library.close();
  }

  private static DataSource open(String password) throws ConnectorException {
    return library.openDataSource(
        "postgresql",
        Map.of(
            "host", POSTGRES.getHost(),
            "port", Integer.toString(POSTGRES.getMappedPort(5432)),
            "database", POSTGRES.getDatabaseName(),
            "user", POSTGRES.getUsername(),
            "password", password),
        TIMEOUT);
  }

  private static List<String> table(String name) {
    return List.of("public", name);
  }

  @Test
  void tableMetadataCarriesColumnsConstraintsAndSourceMetadata() throws Exception {
    try (var dataSource = open(POSTGRES.getPassword())) {
      var result = dataSource.tableMetadata(List.of(table("orders")), TIMEOUT);

      assertThat(result.retrieval().connector().name()).isEqualTo("postgresql");
      assertThat(result.retrieval().adbcDriver()).isPresent();
      var record = (TableRecord.Read) result.records().getFirst();
      assertThat(record.table()).isEqualTo(table("orders"));
      var metadata = record.metadata();
      assertThat(metadata.kind()).isEqualTo(new TableKind.Table());
      assertThat(metadata.columns())
          .extracting(Column::name)
          .containsExactly("id", "customer_id", "amount", "tags", "location");
      assertThat(metadata.columns().get(0).type())
          .isEqualTo(
              new Column.Type.Mapped(
                  "bigint", new AnalyticsType.Scalar(AnalyticsType.ScalarKind.BIG_INT)));
      assertThat(metadata.columns().get(1).nullable()).isFalse();
      assertThat(metadata.columns().get(2).type())
          .isEqualTo(new Column.Type.Mapped("numeric(10,2)", new AnalyticsType.Decimal(10, 2)));
      // The PostgreSQL Connector maps neither arrays nor geometric types yet.
      assertThat(metadata.columns().get(3).type())
          .isEqualTo(new Column.Type.Unsupported("text[]", Column.UnsupportedReason.NOT_MAPPED));
      assertThat(metadata.columns().get(4).type()).isInstanceOf(Column.Type.Unsupported.class);

      var constraints = ((Reported.Value<List<Constraint>>) metadata.constraints()).value();
      assertThat(constraints)
          .anySatisfy(
              constraint ->
                  assertThat(constraint)
                      .isInstanceOfSatisfying(
                          Constraint.PrimaryKey.class,
                          key -> assertThat(key.columns()).containsExactly("id")))
          .anySatisfy(
              constraint ->
                  assertThat(constraint)
                      .isInstanceOfSatisfying(
                          Constraint.ForeignKey.class,
                          key -> {
                            assertThat(key.columns()).containsExactly("customer_id");
                            assertThat(key.referencedTable()).isEqualTo(table("customers"));
                          }))
          .anySatisfy(constraint -> assertThat(constraint).isInstanceOf(Constraint.Check.class));

      assertThat(record.sourceMetadata().connector()).isEqualTo("postgresql");
      assertThat(record.sourceMetadata().document()).startsWith("{");
    }
  }

  @Test
  void jsonFormSeparatesWhatTheConnectorDerived() throws Exception {
    try (var dataSource = open(POSTGRES.getPassword())) {
      var record =
          (TableRecord.Read)
              dataSource.tableMetadata(List.of(table("orders")), TIMEOUT).records().getFirst();

      var json = TableMetadataJson.of(record.metadata());

      var amount = json.get("columns").get(2);
      assertThat(amount.get("dataSourceType").asText()).isEqualTo("numeric(10,2)");
      assertThat(amount.get("analyticsType").get("kind").asText()).isEqualTo("decimal");
      assertThat(json.get("columns").get(4).get("analyticsType").get("unsupported").asText())
          .isNotEmpty();
      assertThat(json.get("constraints").get("state").asText()).isEqualTo("value");
    }
  }

  @Test
  void missingTableIsReportedAsNotExisting() throws Exception {
    try (var dataSource = open(POSTGRES.getPassword())) {
      var result = dataSource.tableMetadata(List.of(table("customers"), table("missing")), TIMEOUT);

      assertThat(result.records()).hasSize(2);
      assertThat(result.records().get(0)).isInstanceOf(TableRecord.Read.class);
      assertThat(result.records().get(1)).isEqualTo(new TableRecord.DoesNotExist(table("missing")));
    }
  }

  @Test
  void listsNamespacesAndTables() throws Exception {
    try (var dataSource = open(POSTGRES.getPassword())) {
      var namespace = List.of("public");

      assertThat(dataSource.namespaces(TIMEOUT)).contains(new ListedNamespace.Namespace(namespace));
      assertThat(dataSource.tables(List.of(namespace), TIMEOUT))
          .extracting(entry -> ((ListedTable.Table) entry).names())
          .containsExactlyInAnyOrder(table("customers"), table("orders"));
    }
  }

  @Test
  void failedConnectionKeepsTheCredentialOutOfTheMessage() {
    assertThatThrownBy(
            () -> {
              try (var dataSource = open("not-the-password")) {
                dataSource.namespaces(TIMEOUT);
              }
            })
        .isInstanceOfSatisfying(
            ConnectorException.class,
            e -> {
              assertThat(e.getMessage()).doesNotContain("not-the-password");
            });
  }

  @Test
  void staticInformationNamesThePackagedConnector() {
    assertThat(library.staticInformation()).contains("\"postgresql\"");
  }
}
