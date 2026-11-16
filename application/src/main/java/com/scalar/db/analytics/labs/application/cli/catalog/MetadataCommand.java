/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.cli.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.scalar.db.analytics.labs.application.ApplicationFailure;
import com.scalar.db.analytics.labs.application.cli.CliOutput;
import com.scalar.db.analytics.labs.application.metadata.TableMetadataRetrieval;
import com.scalar.db.analytics.labs.physical.PhysicalNamespace;
import com.scalar.db.analytics.labs.physical.PhysicalTable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "metadata", description = "Reads Physical Catalog metadata.")
public final class MetadataCommand implements Runnable {
  private MetadataCommand() {}

  public static CommandLine create(Map<String, String> environment) {
    var command = new CommandLine(new MetadataCommand());
    command.addSubcommand("list", new MetadataListCommand(environment));
    command.addSubcommand("retrieve", new MetadataRetrieveCommand(environment));
    return command;
  }

  @Override
  public void run() {
    throw new picocli.CommandLine.ParameterException(
        new picocli.CommandLine(this), "A metadata command is required.");
  }
}

@Command(name = "list", description = "Lists namespaces, tables, and columns for a data source.")
final class MetadataListCommand extends WorkspaceCatalogCommand implements Callable<Integer> {
  @Option(names = "--source", required = true, paramLabel = "NAME")
  private @Nullable String sourceName;

  MetadataListCommand(Map<String, String> environment) {
    super(environment);
  }

  @Override
  public Integer call() throws JsonProcessingException {
    try {
      var source = Objects.requireNonNull(sourceName, "sourceName");
      var metadata =
          CatalogOperations.execute(
              workspaceDirectory(),
              environment,
              (catalog, catalogName) -> {
                if (catalog.findDataSource(catalogName, source).isEmpty()) {
                  throw new ApplicationFailure(
                      "SOURCE_NOT_FOUND", "The data source was not found.");
                }
                return new Metadata(
                    catalog.listNamespaces(catalogName, source),
                    catalog.listTables(catalogName, source));
              });
      CliOutput.write(
          commandSpec().commandLine().getOut(),
          new MetadataOutput(
              "scalardb-analytics/physical-metadata",
              "1",
              source,
              metadata.namespaces().stream().map(PhysicalNamespace::names).toList(),
              metadata.tables().stream().map(MetadataListCommand::output).toList()));
      return CliOutput.SUCCESS;
    } catch (ApplicationFailure e) {
      return failure(e);
    }
  }

  private record Metadata(List<PhysicalNamespace> namespaces, List<PhysicalTable> tables) {}

  private record MetadataOutput(
      String schema,
      String schemaVersion,
      String source,
      List<List<String>> namespaces,
      List<TableOutput> tables) {}

  private static TableOutput output(PhysicalTable table) {
    return new TableOutput(
        table.dataSourceName(),
        table.namespaceNames(),
        table.name(),
        table.columns().stream().map(MetadataListCommand::output).toList());
  }

  private static ColumnOutput output(PhysicalTable.Column column) {
    return new ColumnOutput(
        column.name(), column.type(), column.ordinalPosition(), column.nullable());
  }

  private record TableOutput(
      String dataSourceName,
      List<String> namespaceNames,
      String name,
      List<ColumnOutput> columns) {}

  private record ColumnOutput(String name, String type, int ordinalPosition, boolean nullable) {}
}

@Command(
    name = "retrieve",
    description =
        "Retrieves the table metadata of a data source's registered tables again and passes it to"
            + " the Semantic Catalog.")
final class MetadataRetrieveCommand extends WorkspaceCatalogCommand implements Callable<Integer> {
  @Option(names = "--source", required = true, paramLabel = "NAME")
  private @Nullable String sourceName;

  MetadataRetrieveCommand(Map<String, String> environment) {
    super(environment);
  }

  @Override
  public Integer call() throws JsonProcessingException {
    try {
      var source = Objects.requireNonNull(sourceName, "sourceName");
      var outcome =
          CatalogOperations.executeInWorkspace(
              workspaceDirectory(),
              environment,
              (workspace, runtime) -> TableMetadataRetrieval.retrieve(workspace, runtime, source));
      CliOutput.write(
          commandSpec().commandLine().getOut(),
          new RetrievalOutput(
              "scalardb-analytics/table-metadata-retrieval",
              "1",
              source,
              TableMetadataOutput.of(outcome)));
      return CliOutput.SUCCESS;
    } catch (ApplicationFailure e) {
      return failure(e);
    }
  }

  private record RetrievalOutput(
      String schema, String schemaVersion, String source, TableMetadataOutput tableMetadata) {}
}
