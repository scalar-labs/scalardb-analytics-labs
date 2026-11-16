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
   * Returns the attachment that reads a data source through its Connector. Every source type the
   * Physical Catalog registers has a Connector in the connectors package.
   *
   * @param name the data source name, under which SQL addresses the source
   * @param connection the source's connection settings as resolved from the Physical Catalog
   */
  public static SourceAttachment attachment(String name, DataSourceConnection connection) {
    return new ConnectorAttachment(name, connection);
  }

  private record ConnectorAttachment(String name, DataSourceConnection connection)
      implements SourceAttachment {
    @Override
    public void attach(AttachSession session) throws SourceAttachException {
      var secret = "scalardb_analytics_source_" + UUID.randomUUID().toString().replace("-", "");
      try {
        var providerAndOptions = bindSettings(session);
        // The Connector is the secret's provider, and its declared options are the parameters.
        session.execute(
            "CREATE TEMPORARY SECRET "
                + secret
                + " (TYPE scalardb_analytics_connectors, PROVIDER "
                + providerAndOptions
                + ")");
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

    /**
     * Binds the connection settings as variables and returns the secret's provider, the Connector,
     * followed by the options that read them.
     */
    private String bindSettings(AttachSession session) throws SourceAttachException {
      return switch (connection) {
        case DataSourceConnection.PostgreSql postgreSql -> {
          session.setVariable(HOST_VARIABLE, postgreSql.host());
          session.setVariable(PORT_VARIABLE, postgreSql.port());
          session.setVariable(DATABASE_VARIABLE, postgreSql.database());
          session.setVariable(USER_VARIABLE, postgreSql.username());
          session.setVariable(PASSWORD_VARIABLE, postgreSql.password().reveal());
          yield String.join(
              ", ",
              "postgresql",
              variableOption("host", HOST_VARIABLE),
              variableOption("port", PORT_VARIABLE),
              variableOption("database", DATABASE_VARIABLE),
              variableOption("user", USER_VARIABLE),
              variableOption("password", PASSWORD_VARIABLE));
        }
        // The MySQL Connector reads the whole server and qualifies every table with its database,
        // so it takes no database, and a registered one does not narrow the attached catalog.
        case DataSourceConnection.MySql mySql -> {
          session.setVariable(HOST_VARIABLE, mySql.host());
          session.setVariable(PORT_VARIABLE, mySql.port());
          session.setVariable(USER_VARIABLE, mySql.username());
          session.setVariable(PASSWORD_VARIABLE, mySql.password().reveal());
          yield String.join(
              ", ",
              "mysql",
              variableOption("host", HOST_VARIABLE),
              variableOption("port", PORT_VARIABLE),
              variableOption("user", USER_VARIABLE),
              variableOption("password", PASSWORD_VARIABLE),
              // MySQL declares BOOL and BOOLEAN as tinyint(1), which therefore reads as BOOLEAN.
              "tinyint1_as_boolean true");
        }
      };
    }

    @Override
    public String toString() {
      // The connection holds the credential.
      return "ConnectorAttachment[name=" + name + "]";
    }

    private static String variableOption(String option, String variable) {
      return option + " getvariable('" + variable + "')";
    }

    private static String quoteIdentifier(String identifier) {
      return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
  }
}
