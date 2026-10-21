/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.duckdb.scanner;

import com.scalar.db.analytics.labs.duckdb.AttachSession;
import com.scalar.db.analytics.labs.duckdb.SourceAttachException;
import com.scalar.db.analytics.labs.duckdb.SourceAttachment;
import com.scalar.db.analytics.labs.physical.DataSourceConnection;
import java.util.List;
import java.util.UUID;

/**
 * Creates {@link SourceAttachment}s that read a registered data source through DuckDB's packaged
 * PostgreSQL or MySQL scanner.
 *
 * <p>A source is attached read-only. Its connection settings, including the password, reach DuckDB
 * only as bound variables. The scanner reads them through a temporary secret, and the variables are
 * reset right after, so no connection value is written into SQL text.
 */
public final class ScannerPath {
  private static final String HOST_VARIABLE = "scalardb_analytics_source_host";
  private static final String PORT_VARIABLE = "scalardb_analytics_source_port";
  private static final String DATABASE_VARIABLE = "scalardb_analytics_source_database";
  private static final String USER_VARIABLE = "scalardb_analytics_source_user";
  private static final String PASSWORD_VARIABLE = "scalardb_analytics_source_password";
  private static final List<String> VARIABLES =
      List.of(HOST_VARIABLE, PORT_VARIABLE, DATABASE_VARIABLE, USER_VARIABLE, PASSWORD_VARIABLE);

  private ScannerPath() {}

  /**
   * Returns the attachment that reads a data source through its scanner.
   *
   * @param name the data source name, under which SQL addresses the source
   * @param connection the source's connection settings as resolved from the Physical Catalog
   */
  public static SourceAttachment attachment(String name, DataSourceConnection connection) {
    return new ScannerAttachment(name, connection);
  }

  private record ScannerAttachment(String name, DataSourceConnection connection)
      implements SourceAttachment {
    @Override
    public void attach(AttachSession session) throws SourceAttachException {
      var secret = "scalardb_analytics_source_" + UUID.randomUUID().toString().replace("-", "");
      try {
        var options = bindSettings(session);
        session.execute("CREATE TEMPORARY SECRET " + secret + " (" + options + ")");
        session.execute(
            "ATTACH '' AS "
                + quoteIdentifier(name)
                + " (TYPE "
                + scannerType()
                + ", SECRET "
                + secret
                + ", READ_ONLY)");
      } finally {
        for (var variable : VARIABLES) {
          session.resetVariable(variable);
        }
      }
    }

    /** Binds the settings as variables and returns the secret options that read them. */
    private String bindSettings(AttachSession session) throws SourceAttachException {
      return switch (connection) {
        case DataSourceConnection.PostgreSql postgreSql -> {
          session.setVariable(HOST_VARIABLE, postgreSql.host());
          session.setVariable(PORT_VARIABLE, postgreSql.port());
          session.setVariable(DATABASE_VARIABLE, postgreSql.database());
          session.setVariable(USER_VARIABLE, postgreSql.username());
          session.setVariable(PASSWORD_VARIABLE, postgreSql.password().reveal());
          yield secretOptions(true);
        }
        case DataSourceConnection.MySql mySql -> {
          session.setVariable(HOST_VARIABLE, mySql.host());
          session.setVariable(PORT_VARIABLE, mySql.port());
          if (mySql.database().isPresent()) {
            session.setVariable(DATABASE_VARIABLE, mySql.database().get());
          }
          session.setVariable(USER_VARIABLE, mySql.username());
          session.setVariable(PASSWORD_VARIABLE, mySql.password().reveal());
          yield secretOptions(mySql.database().isPresent());
        }
      };
    }

    private String secretOptions(boolean hasDatabase) {
      return "TYPE "
          + scannerType()
          + ", HOST getvariable('"
          + HOST_VARIABLE
          + "'), PORT getvariable('"
          + PORT_VARIABLE
          + "'), "
          + (hasDatabase ? "DATABASE getvariable('" + DATABASE_VARIABLE + "'), " : "")
          + "USER getvariable('"
          + USER_VARIABLE
          + "'), PASSWORD getvariable('"
          + PASSWORD_VARIABLE
          + "')";
    }

    private String scannerType() {
      return switch (connection) {
        case DataSourceConnection.PostgreSql ignored -> "postgres";
        case DataSourceConnection.MySql ignored -> "mysql";
      };
    }

    private static String quoteIdentifier(String identifier) {
      return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
  }
}
