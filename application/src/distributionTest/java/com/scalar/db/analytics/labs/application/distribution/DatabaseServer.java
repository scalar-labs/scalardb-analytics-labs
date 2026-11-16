/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * A database server the distribution reaches, with administrative access through the server's own
 * client inside its container, so the tests need no JDBC driver of their own.
 */
sealed interface DatabaseServer {
  GenericContainer<?> container();

  /** The administrator's password. */
  String password();

  /** The host at which the distribution reaches the server. */
  String host();

  /** The port at which the distribution reaches the server. */
  int port();

  /** The port the server listens on inside its container network. */
  int internalPort();

  /** Runs SQL as the administrator and fails if it fails. */
  void execute(@Nullable String database, String sql) throws IOException, InterruptedException;

  /** Runs a query as the administrator and returns its rows as tab-separated lines. */
  List<String> query(@Nullable String database, String sql)
      throws IOException, InterruptedException;

  /** The statements the server received that contain the marker. */
  List<String> statementsContaining(String marker) throws IOException, InterruptedException;

  /** The number of sessions the given user has open on the server. */
  default int sessions(String user) throws IOException, InterruptedException {
    return Integer.parseInt(query(null, sessionCountSql(user)).getFirst());
  }

  String sessionCountSql(String user);

  static String password(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  /** A PostgreSQL server that logs every statement it receives. */
  record PostgreSql(GenericContainer<?> container, String password, String host, int port)
      implements DatabaseServer {
    static final String IMAGE = "postgres:16.4";
    static final int PORT = 5432;

    static GenericContainer<?> container(String password) {
      return new GenericContainer<>(IMAGE)
          .withEnv("POSTGRES_PASSWORD", password)
          .withCommand("postgres", "-c", "fsync=off", "-c", "log_statement=all")
          .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2));
    }

    String jdbcUrl() {
      return "jdbc:postgresql://" + host + ":" + port + "/postgres";
    }

    @Override
    public int internalPort() {
      return PORT;
    }

    @Override
    public void execute(@Nullable String database, String sql)
        throws IOException, InterruptedException {
      psql(database, sql, false);
    }

    @Override
    public List<String> query(@Nullable String database, String sql)
        throws IOException, InterruptedException {
      return psql(database, sql, true).lines().toList();
    }

    private static final Pattern EXTENDED_EXECUTE = Pattern.compile("LOG:  execute [^:]*: ");

    @Override
    public List<String> statementsContaining(String marker) {
      return container
          .getLogs()
          .lines()
          // Statements the tests run through psql are simple queries ("statement: "), and the
          // connector path's ADBC driver sends extended-protocol ones ("execute <name>: ").
          .filter(
              line ->
                  (line.contains("statement: ") || EXTENDED_EXECUTE.matcher(line).find())
                      && line.contains(marker))
          .toList();
    }

    @Override
    public String sessionCountSql(String user) {
      return "SELECT count(*) FROM pg_stat_activity WHERE usename = '" + user + "'";
    }

    private String psql(@Nullable String database, String sql, boolean unaligned)
        throws IOException, InterruptedException {
      var result =
          container.execInContainer(
              "psql",
              "-v",
              "ON_ERROR_STOP=1",
              "-U",
              "postgres",
              "-d",
              database == null ? "postgres" : database,
              unaligned ? "-AtF\t" : "-q",
              "-c",
              sql);
      assertThat(result.getExitCode()).as(result.getStderr()).isZero();
      return result.getStdout();
    }
  }

  /** A MySQL server that records every statement it receives in {@code mysql.general_log}. */
  record MySql(GenericContainer<?> container, String password, String host, int port)
      implements DatabaseServer {
    static final String IMAGE = "mysql:8.4";
    static final int PORT = 3306;

    static GenericContainer<?> container(String password) {
      return new GenericContainer<>(IMAGE)
          .withEnv("MYSQL_ROOT_PASSWORD", password)
          .withCommand("--general-log=1", "--log-output=TABLE")
          .waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306 .*", 1));
    }

    @Override
    public int internalPort() {
      return PORT;
    }

    @Override
    public void execute(@Nullable String database, String sql)
        throws IOException, InterruptedException {
      mysql(database, sql);
    }

    @Override
    public List<String> query(@Nullable String database, String sql)
        throws IOException, InterruptedException {
      return mysql(database, sql).lines().toList();
    }

    @Override
    public List<String> statementsContaining(String marker)
        throws IOException, InterruptedException {
      return query(
          null,
          "SELECT CONVERT(argument USING utf8mb4) FROM mysql.general_log "
              + "WHERE command_type IN ('Query', 'Execute') "
              + "AND CONVERT(argument USING utf8mb4) LIKE '%"
              + marker
              + "%' AND CONVERT(argument USING utf8mb4) NOT LIKE '%mysql.general_log%'");
    }

    @Override
    public String sessionCountSql(String user) {
      return "SELECT count(*) FROM information_schema.processlist WHERE user = '" + user + "'";
    }

    private String mysql(@Nullable String database, String sql)
        throws IOException, InterruptedException {
      var command =
          new ArrayList<>(
              List.of("mysql", "-uroot", "--password=" + password, "-N", "-B", "-e", sql));
      if (database != null) {
        command.add(database);
      }
      var result = container.execInContainer(command.toArray(String[]::new));
      assertThat(result.getExitCode()).as(result.getStderr()).isZero();
      return result.getStdout();
    }
  }
}
