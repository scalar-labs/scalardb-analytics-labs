/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.analytics.labs.physical.DataSourceConnection;
import com.scalar.db.analytics.labs.physical.PhysicalCatalogSettings;
import com.scalar.db.analytics.labs.physical.Secret;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ApplicationRuntimeTest {
  private static final String SOURCE_PASSWORD = "source-" + UUID.randomUUID();

  @Container
  private static final PostgreSQLContainer<?> STORE = new PostgreSQLContainer<>("postgres:16.4");

  @Container
  private static final PostgreSQLContainer<?> SOURCE =
      new PostgreSQLContainer<>("postgres:16.4").withPassword(SOURCE_PASSWORD);

  @Test
  void registersSourcesAndServesTheCatalogToTheQueryEngine() throws Exception {
    var settings =
        new PhysicalCatalogSettings(
            STORE.getJdbcUrl(), Optional.of(STORE.getUsername()), Secret.of(STORE.getPassword()));

    try (var runtime = ApplicationRuntime.start(settings)) {
      var catalog = runtime.physicalCatalog();
      catalog.createCatalog("analytics");
      var registered =
          catalog.registerDataSource(
              "analytics",
              "warehouse",
              new DataSourceConnection.PostgreSql(
                  SOURCE.getHost(),
                  SOURCE.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                  SOURCE.getDatabaseName(),
                  SOURCE.getUsername(),
                  Secret.of(SOURCE_PASSWORD)));

      assertThat(catalog.findDataSource("analytics", "warehouse")).contains(registered);
      try (var result =
          runtime.queryEngine().execute("SELECT scalardb_analytics_catalog_status() AS status")) {
        assertThat(result.reader().loadNextBatch()).isTrue();
        assertThat(result.reader().getVectorSchemaRoot().getVector("status").getObject(0))
            .hasToString("SERVING");
      }
    }
  }
}
