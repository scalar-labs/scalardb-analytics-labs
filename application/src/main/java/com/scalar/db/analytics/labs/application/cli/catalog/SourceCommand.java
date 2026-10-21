/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.cli.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.scalar.db.analytics.labs.application.ApplicationFailure;
import com.scalar.db.analytics.labs.application.cli.CliOutput;
import com.scalar.db.analytics.labs.physical.DataSourceConnection;
import com.scalar.db.analytics.labs.physical.DataSourceDescriptor;
import com.scalar.db.analytics.labs.physical.Secret;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "source", description = "Manages registered data sources.")
public final class SourceCommand implements Runnable {
  private SourceCommand() {}

  public static CommandLine create(InputStream standardInput, Map<String, String> environment) {
    var command = new CommandLine(new SourceCommand());
    command.addSubcommand("register", new SourceRegisterCommand(standardInput, environment));
    command.addSubcommand("list", new SourceListCommand(environment));
    command.addSubcommand("show", new SourceShowCommand(environment));
    return command;
  }

  @Override
  public void run() {
    throw new picocli.CommandLine.ParameterException(
        new picocli.CommandLine(this), "A source command is required.");
  }

  static SourceOutput output(DataSourceDescriptor source) {
    return new SourceOutput(
        source.name(),
        source.type().name().toLowerCase(Locale.ROOT),
        source.host(),
        source.port(),
        source.database().orElse(null),
        source.username());
  }

  record SourceOutput(
      String name,
      String type,
      String host,
      int port,
      @Nullable String database,
      String username) {}
}

@Command(name = "register", description = "Registers a PostgreSQL or MySQL data source.")
final class SourceRegisterCommand extends WorkspaceCatalogCommand implements Callable<Integer> {
  private final InputStream standardInput;

  @Option(names = "--name", required = true)
  private @Nullable String name;

  @Option(names = "--type", required = true, description = "postgresql or mysql")
  private @Nullable String type;

  @Option(names = "--host", required = true)
  private @Nullable String host;

  @Option(names = "--port", required = true)
  private int port;

  @Option(names = "--database")
  private @Nullable String database;

  @Option(names = "--user", required = true)
  private @Nullable String username;

  @ArgGroup(exclusive = true, multiplicity = "1")
  private @Nullable PasswordInput passwordInput;

  SourceRegisterCommand(InputStream standardInput, Map<String, String> environment) {
    super(environment);
    this.standardInput = standardInput;
  }

  @Override
  public Integer call() throws JsonProcessingException {
    try {
      var connection = connection(readPassword());
      var source =
          CatalogOperations.execute(
              workspaceDirectory(),
              environment,
              (catalog, catalogName) ->
                  catalog.registerDataSource(
                      catalogName, Objects.requireNonNull(name, "name"), connection));
      CliOutput.write(
          commandSpec().commandLine().getOut(),
          new SourceEnvelope("scalardb-analytics/source", "1", SourceCommand.output(source)));
      return CliOutput.SUCCESS;
    } catch (ApplicationFailure e) {
      return failure(e);
    } catch (IllegalArgumentException e) {
      return failure(new ApplicationFailure("INVALID_REQUEST", "The source settings are invalid."));
    }
  }

  private DataSourceConnection connection(Secret password) {
    var normalizedType = Objects.requireNonNull(type, "type").toLowerCase(Locale.ROOT);
    var sourceHost = Objects.requireNonNull(host, "host");
    var sourceUser = Objects.requireNonNull(username, "username");
    return switch (normalizedType) {
      case "postgresql" -> {
        if (database == null || database.isBlank()) {
          throw new ApplicationFailure(
              "INVALID_REQUEST", "A PostgreSQL source requires --database.");
        }
        yield new DataSourceConnection.PostgreSql(sourceHost, port, database, sourceUser, password);
      }
      case "mysql" ->
          new DataSourceConnection.MySql(
              sourceHost, port, Optional.ofNullable(database), sourceUser, password);
      default ->
          throw new ApplicationFailure(
              "INVALID_REQUEST", "The source type must be postgresql or mysql.");
    };
  }

  private Secret readPassword() {
    var input = Objects.requireNonNull(passwordInput, "passwordInput");
    final String password;
    if (input.passwordEnv != null) {
      password = environment.get(input.passwordEnv);
      if (password == null) {
        throw new ApplicationFailure(
            "MISSING_CREDENTIAL", "The data source password environment variable is not set.");
      }
    } else {
      try {
        password =
            stripLineEnding(new String(standardInput.readAllBytes(), StandardCharsets.UTF_8));
      } catch (IOException e) {
        throw new ApplicationFailure("INVALID_REQUEST", "Could not read the data source password.");
      }
    }
    if (password.isEmpty()) {
      throw new ApplicationFailure(
          "INVALID_REQUEST", "The data source password must not be empty.");
    }
    return Secret.of(password);
  }

  private static String stripLineEnding(String value) {
    if (value.endsWith("\r\n")) {
      return value.substring(0, value.length() - 2);
    }
    return value.endsWith("\n") ? value.substring(0, value.length() - 1) : value;
  }

  private static final class PasswordInput {
    @Option(names = "--password-stdin", description = "Read the password from standard input.")
    private boolean passwordStdin;

    @Option(names = "--password-env", paramLabel = "NAME")
    private @Nullable String passwordEnv;
  }

  private record SourceEnvelope(
      String schema, String schemaVersion, SourceCommand.SourceOutput source) {}
}

@Command(name = "list", description = "Lists registered data sources.")
final class SourceListCommand extends WorkspaceCatalogCommand implements Callable<Integer> {
  SourceListCommand(Map<String, String> environment) {
    super(environment);
  }

  @Override
  public Integer call() throws JsonProcessingException {
    try {
      List<SourceCommand.SourceOutput> sources =
          CatalogOperations.execute(
                  workspaceDirectory(),
                  environment,
                  (catalog, name) -> catalog.listDataSources(name))
              .stream()
              .map(SourceCommand::output)
              .sorted(Comparator.comparing(SourceCommand.SourceOutput::name))
              .toList();
      CliOutput.write(
          commandSpec().commandLine().getOut(),
          new SourcesOutput("scalardb-analytics/sources", "1", sources));
      return CliOutput.SUCCESS;
    } catch (ApplicationFailure e) {
      return failure(e);
    }
  }

  private record SourcesOutput(
      String schema, String schemaVersion, List<SourceCommand.SourceOutput> sources) {}
}

@Command(name = "show", description = "Shows one registered data source.")
final class SourceShowCommand extends WorkspaceCatalogCommand implements Callable<Integer> {
  @Parameters(index = "0", paramLabel = "NAME")
  private @Nullable String name;

  SourceShowCommand(Map<String, String> environment) {
    super(environment);
  }

  @Override
  public Integer call() throws JsonProcessingException {
    try {
      var source =
          CatalogOperations.execute(
                  workspaceDirectory(),
                  environment,
                  (catalog, catalogName) ->
                      catalog.findDataSource(catalogName, Objects.requireNonNull(name)))
              .orElseThrow(
                  () ->
                      new ApplicationFailure("SOURCE_NOT_FOUND", "The data source was not found."));
      CliOutput.write(
          commandSpec().commandLine().getOut(),
          new SourceOutputEnvelope("scalardb-analytics/source", "1", SourceCommand.output(source)));
      return CliOutput.SUCCESS;
    } catch (ApplicationFailure e) {
      return failure(e);
    }
  }

  private record SourceOutputEnvelope(
      String schema, String schemaVersion, SourceCommand.SourceOutput source) {}
}
