/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.duckdb.connector;

import com.scalar.db.analytics.labs.duckdb.AttachSession;
import com.scalar.db.analytics.labs.duckdb.SourceAttachException;
import com.scalar.db.analytics.labs.duckdb.SourceAttachment;
import com.scalar.db.analytics.labs.physical.DataSourceConnection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates {@link SourceAttachment}s that read a registered data source through its Connector, by
 * the DuckDB extension of the connectors distribution.
 *
 * <p>A source is attached read-only. Its connection settings, including the password, reach DuckDB
 * only as bound variables. The extension reads them through a temporary secret, and the variables
 * are reset right after, so no connection value is written into SQL text. The attached catalog
 * reads its namespaces and tables from the Connector, and every scan offers its filters and
 * projection to the Connector's pushdown planning.
 */
public final class ConnectorPath {
  private static final String HOST_VARIABLE = "scalardb_analytics_source_host";
  private static final String PORT_VARIABLE = "scalardb_analytics_source_port";
  private static final String DATABASE_VARIABLE = "scalardb_analytics_source_database";
  private static final String USER_VARIABLE = "scalardb_analytics_source_user";
  private static final String PASSWORD_VARIABLE = "scalardb_analytics_source_password";
  private static final List<String> VARIABLES =
      List.of(HOST_VARIABLE, PORT_VARIABLE, DATABASE_VARIABLE, USER_VARIABLE, PASSWORD_VARIABLE);

  private ConnectorPath() {}

  /**
   * Returns the attachment of a data source whose source type has a Connector in the connectors
   * package, or empty when it has none and stays on another read path.
   *
   * @param name the data source name, under which SQL addresses the source
   * @param connection the source's connection settings as resolved from the Physical Catalog
   */
  public static Optional<SourceAttachment> attachment(
      String name, DataSourceConnection connection) {
    return switch (connection) {
      case DataSourceConnection.PostgreSql postgreSql ->
          Optional.of(new PostgreSqlAttachment(name, postgreSql));
      case DataSourceConnection.MySql ignored -> Optional.empty();
    };
  }

  private record PostgreSqlAttachment(String name, DataSourceConnection.PostgreSql connection)
      implements SourceAttachment {
    @Override
    public boolean usesConnectorsExtension() {
      return true;
    }

    @Override
    public void attach(AttachSession session) throws SourceAttachException {
      var secret = "scalardb_analytics_source_" + UUID.randomUUID().toString().replace("-", "");
      try {
        session.setVariable(HOST_VARIABLE, connection.host());
        session.setVariable(PORT_VARIABLE, connection.port());
        session.setVariable(DATABASE_VARIABLE, connection.database());
        session.setVariable(USER_VARIABLE, connection.username());
        session.setVariable(PASSWORD_VARIABLE, connection.password().reveal());
        // The Connector is the secret's provider, and its declared options are the parameters.
        session.execute(
            "CREATE TEMPORARY SECRET "
                + secret
                + " (TYPE scalardb_analytics_connectors, PROVIDER postgresql, host getvariable('"
                + HOST_VARIABLE
                + "'), port getvariable('"
                + PORT_VARIABLE
                + "'), database getvariable('"
                + DATABASE_VARIABLE
                + "'), user getvariable('"
                + USER_VARIABLE
                + "'), password getvariable('"
                + PASSWORD_VARIABLE
                + "'))");
        // An ATTACH through a secret takes its Connector and options from the secret alone.
        session.execute(
            "ATTACH '' AS "
                + quoteIdentifier(name)
                + " (TYPE scalardb_analytics_connectors, SECRET "
                + secret
                + ", READ_ONLY)");
      } finally {
        for (var variable : VARIABLES) {
          session.resetVariable(variable);
        }
      }
    }

    @Override
    public String toString() {
      // The connection holds the credential.
      return "PostgreSqlAttachment[name=" + name + "]";
    }

    private static String quoteIdentifier(String identifier) {
      return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
  }
}
