/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.duckdb;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Attaches {@link SourceAttachment}s to the engine connection while the engine starts.
 *
 * <p>Attaching must happen before external access is disabled, because DuckDB refuses source
 * attachments afterwards. Sources attached earlier stay queryable.
 */
final class SourceAttachments {
  /**
   * DuckDB's built-in databases, and the catalog-wide schema names that would become ambiguous for
   * every source if a database shared them.
   */
  private static final List<String> RESERVED_NAMES =
      List.of("memory", "system", "temp", "main", "information_schema", "pg_catalog");

  /** A source could not be attached. The message names the source and nothing else. */
  static final class AttachFailedException extends Exception {
    private static final long serialVersionUID = 1L;

    AttachFailedException(String message) {
      super(message);
    }
  }

  private SourceAttachments() {}

  /** Rejects sources that cannot be distinct databases, before anything connects. */
  static void validate(List<SourceAttachment> attachments) throws AttachFailedException {
    var names = new HashSet<String>();
    for (var attachment : attachments) {
      // DuckDB database names are case-insensitive.
      var name = attachment.name().toLowerCase(Locale.ROOT);
      if (attachment.name().isBlank()) {
        throw new AttachFailedException("A data source name must not be blank.");
      }
      if (RESERVED_NAMES.contains(name)) {
        throw new AttachFailedException(
            "The data source name " + attachment.name() + " is reserved by the Query Engine.");
      }
      if (!names.add(name)) {
        throw new AttachFailedException(
            "The data source names must differ in more than letter case: "
                + attachment.name()
                + ".");
      }
    }
  }

  static void attach(Connection connection, List<SourceAttachment> attachments)
      throws AttachFailedException {
    var session = new ConnectionSession(connection);
    for (var attachment : attachments) {
      try {
        attachment.attach(session);
      } catch (SourceAttachException | RuntimeException e) {
        // A read path's diagnostics can quote connection settings, so the cause is not retained.
        throw new AttachFailedException(
            "Could not attach the data source " + attachment.name() + ".");
      }
    }
  }

  /** An {@link AttachSession} over the engine connection. */
  private record ConnectionSession(Connection connection) implements AttachSession {
    private static final Pattern VARIABLE_NAME = Pattern.compile("[a-z_][a-z0-9_]*");

    @Override
    public void setVariable(String name, String value) throws SourceAttachException {
      try (var statement = connection.prepareStatement(setVariableSql(name))) {
        statement.setString(1, value);
        statement.execute();
      } catch (SQLException e) {
        throw new SourceAttachException();
      }
    }

    @Override
    public void setVariable(String name, int value) throws SourceAttachException {
      try (var statement = connection.prepareStatement(setVariableSql(name))) {
        statement.setInt(1, value);
        statement.execute();
      } catch (SQLException e) {
        throw new SourceAttachException();
      }
    }

    @Override
    public void resetVariable(String name) throws SourceAttachException {
      execute("RESET VARIABLE " + requireVariableName(name));
    }

    private static String setVariableSql(String name) {
      return "SET VARIABLE " + requireVariableName(name) + " = ?";
    }

    private static String requireVariableName(String name) {
      if (!VARIABLE_NAME.matcher(name).matches()) {
        throw new IllegalArgumentException("Invalid variable name: " + name);
      }
      return name;
    }

    @Override
    public void execute(String sql) throws SourceAttachException {
      try (var statement = connection.createStatement()) {
        statement.execute(sql);
      } catch (SQLException e) {
        throw new SourceAttachException();
      }
    }
  }
}
