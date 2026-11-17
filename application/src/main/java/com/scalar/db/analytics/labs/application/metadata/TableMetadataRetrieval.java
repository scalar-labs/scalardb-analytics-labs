/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.metadata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.scalar.db.analytics.labs.application.ApplicationFailure;
import com.scalar.db.analytics.labs.application.ApplicationRuntime;
import com.scalar.db.analytics.labs.application.SemanticCatalogDomains;
import com.scalar.db.analytics.labs.application.construction.QueryEngineSqlParser;
import com.scalar.db.analytics.labs.application.workspace.Workspace;
import com.scalar.db.analytics.labs.connector.ConnectorException;
import com.scalar.db.analytics.labs.connector.ConnectorLibrary;
import com.scalar.db.analytics.labs.connector.TableMetadataJson;
import com.scalar.db.analytics.labs.connector.TableMetadataResult;
import com.scalar.db.analytics.labs.connector.TableRecord;
import com.scalar.db.analytics.labs.physical.DataSourceConnection;
import com.scalar.db.analytics.labs.physical.PhysicalCatalog;
import com.scalar.db.analytics.labs.semantic.SemanticCatalogStore;
import com.scalar.db.analytics.labs.semantic.TableMetadataObservation;
import com.scalar.db.analytics.labs.semantic.TableMetadataRegistration;
import com.scalar.db.analytics.labs.semantic.model.PhysicalCatalogReference;
import com.scalar.db.analytics.labs.semantic.model.RecordingLanguageChangedException;
import com.scalar.db.analytics.labs.semantic.model.SemanticCatalogException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The retrieval operation (see {@code docs/application.md}, "Table metadata retrieval"): retrieves
 * the table metadata of a data source's registered tables through its Connector and registers each
 * table in the Semantic Catalog.
 *
 * <p>Until the Physical Catalog retrieves and versions connector metadata itself, the application
 * calls the Connector directly with the registration's connection, and the table identifier and
 * version are interim: the identifier is built from the table's names, and the version is the time
 * the Connector read the table, in microseconds since the epoch.
 */
public final class TableMetadataRetrieval {
  /** The principal recorded on the commits a retrieval writes. */
  public static final String AUTHOR = "scalardb-analytics";

  static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(30);
  static final Duration METADATA_TIMEOUT = Duration.ofSeconds(60);

  private static final ObjectMapper JSON = new ObjectMapper();
  // The digest is taken over one canonical text of the metadata, whatever order produced it.
  private static final ObjectMapper CANONICAL_JSON =
      JsonMapper.builder()
          .enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .build();

  private TableMetadataRetrieval() {}

  /**
   * What one retrieval did for a data source: one outcome per registered table, in listed order.
   */
  public record Outcome(List<TableOutcome> tables) {
    public Outcome {
      tables = List.copyOf(tables);
    }
  }

  /** What one retrieval did for one registered table. */
  public sealed interface TableOutcome {
    List<String> table();

    /** The table was retrieved and passed to the Semantic Catalog, which recorded the result. */
    record Registered(
        List<String> table,
        String tableIdentifier,
        long version,
        TableMetadataRegistration registration)
        implements TableOutcome {}

    /**
     * The Connector could not read the table. Nothing is passed, so a missing privilege or a
     * transient failure cannot look like a change.
     */
    record NotRetrieved(List<String> table, String reason) implements TableOutcome {}
  }

  /**
   * Runs the retrieval operation for one registered data source of the workspace's catalog.
   *
   * <p>The Semantic Catalog store is open only for the operation. The connector API library is the
   * runtime's, which stays initialized until the runtime closes.
   *
   * @throws ApplicationFailure classified when the Connector or the Semantic Catalog fails; the
   *     operation is then run again rather than resent from a stored copy
   */
  public static Outcome retrieve(
      Workspace workspace, ApplicationRuntime runtime, String sourceName) {
    var catalog = runtime.physicalCatalog();
    var connection =
        catalog
            .resolveConnection(workspace.catalogName(), sourceName)
            .orElseThrow(
                () -> new ApplicationFailure("SOURCE_NOT_FOUND", "The data source was not found."));
    var connector =
        switch (connection) {
          case DataSourceConnection.PostgreSql postgreSql -> "postgresql";
          case DataSourceConnection.MySql mySql -> "mysql";
        };
    var options = connectorOptions(connection);
    var domain = workspace.semanticCatalogDomain();
    try (var semanticCatalog =
        SemanticCatalogStore.open(
            workspace.semanticCatalogSettings(),
            QueryEngineSqlParser.INSTANCE,
            ignored -> workspace.semanticCatalogLanguages())) {
      return retrieve(
          catalog,
          workspace.catalogName(),
          sourceName,
          library(runtime),
          semanticCatalog,
          domain,
          connector,
          options);
    } catch (RecordingLanguageChangedException e) {
      throw new ApplicationFailure(
          "RECORDING_LANGUAGE_CHANGED", Objects.requireNonNull(e.getMessage()));
    } catch (SemanticCatalogException e) {
      throw registrationFailed();
    }
  }

  private static ConnectorLibrary library(ApplicationRuntime runtime) {
    try {
      return runtime.connectorLibrary();
    } catch (ConnectorException | IOException e) {
      throw new ApplicationFailure(
          "TABLE_METADATA_RETRIEVAL_FAILED", "The connector API library could not be loaded.");
    }
  }

  private static Outcome retrieve(
      PhysicalCatalog catalog,
      String catalogName,
      String sourceName,
      ConnectorLibrary library,
      SemanticCatalogStore semanticCatalog,
      String domain,
      String connector,
      Map<String, String> options) {
    var tables =
        catalog.listTables(catalogName, sourceName).stream()
            .map(
                table -> {
                  var path = new ArrayList<>(table.namespaceNames());
                  path.add(table.name());
                  return List.copyOf(path);
                })
            .toList();
    final TableMetadataResult result;
    try (var dataSource = library.openDataSource(connector, options, CONNECTION_TIMEOUT)) {
      result = dataSource.tableMetadata(tables, METADATA_TIMEOUT);
    } catch (ConnectorException e) {
      throw new ApplicationFailure(
          "TABLE_METADATA_RETRIEVAL_FAILED",
          "The table metadata of the data source could not be retrieved through its connector ("
              + e.kind().name().toLowerCase(Locale.ROOT)
              + ").");
    }
    var retrieval = (JsonNode) TableMetadataJson.of(result.retrieval());
    var schemas = new HashMap<String, TableMetadataObservation.SourceMetadataSchema>();
    SemanticCatalogDomains.ensure(semanticCatalog, domain);
    var outcomes = new ArrayList<TableOutcome>();
    for (var record : result.records()) {
      var identifier = tableIdentifier(sourceName, record.table());
      var reference = PhysicalCatalogReference.Table.of(sourceName, record.table());
      final TableMetadataObservation observation;
      switch (record) {
        // Neither says whether the table exists, so neither may look like a change.
        case TableRecord.PermissionDenied denied -> {
          outcomes.add(new TableOutcome.NotRetrieved(denied.table(), denied.reason()));
          continue;
        }
        case TableRecord.NotFound notFound -> {
          outcomes.add(new TableOutcome.NotRetrieved(notFound.table(), notFound.reason()));
          continue;
        }
        case TableRecord.Read read -> {
          var tableMetadata = TableMetadataJson.of(read.metadata());
          var document = parse(read.sourceMetadata().document());
          var content = JsonNodeFactory.instance.objectNode();
          content.set("tableMetadata", tableMetadata);
          content.put("connector", read.sourceMetadata().connector());
          content.set("sourceMetadata", document);
          observation =
              new TableMetadataObservation(
                  identifier,
                  reference,
                  ChronoUnit.MICROS.between(Instant.EPOCH, read.readAt()),
                  new TableMetadataObservation.Read(
                      read.complete(),
                      tableMetadata,
                      new TableMetadataObservation.SourceMetadata(
                          read.sourceMetadata().connector(), document),
                      schemas.computeIfAbsent(
                          read.sourceMetadata().connector(),
                          name -> sourceMetadataSchema(library, name)),
                      read.readAt()),
                  retrieval,
                  digest(content));
        }
        case TableRecord.DoesNotExist ignored ->
            observation =
                new TableMetadataObservation(
                    identifier,
                    reference,
                    ChronoUnit.MICROS.between(Instant.EPOCH, Instant.now()),
                    new TableMetadataObservation.DoesNotExist(),
                    retrieval,
                    digest(JsonNodeFactory.instance.objectNode().put("state", "does-not-exist")));
      }
      try {
        outcomes.add(
            new TableOutcome.Registered(
                record.table(),
                identifier,
                observation.version(),
                semanticCatalog.registerTableMetadata(domain, AUTHOR, observation)));
      } catch (RecordingLanguageChangedException e) {
        throw e;
      } catch (SemanticCatalogException e) {
        throw registrationFailed();
      }
    }
    return new Outcome(outcomes);
  }

  /**
   * The interim identifier of a registered table: the data source name, the namespace names, and
   * the table name joined with dots. A name that is empty or contains a dot or a double quote is
   * double-quoted, with each double quote doubled, so distinct name paths never share an
   * identifier.
   */
  public static String tableIdentifier(String sourceName, List<String> table) {
    var parts = new ArrayList<String>();
    parts.add(quote(sourceName));
    table.forEach(name -> parts.add(quote(name)));
    return String.join(".", parts);
  }

  private static String quote(String name) {
    if (!name.isEmpty() && name.indexOf('.') < 0 && name.indexOf('"') < 0) {
      return name;
    }
    return "\"" + name.replace("\"", "\"\"") + "\"";
  }

  /**
   * The Connector's connection options, as the connector path's attachment gives them, so that the
   * retrieved table metadata matches the types queries read.
   */
  private static Map<String, String> connectorOptions(DataSourceConnection connection) {
    var options = new LinkedHashMap<String, String>();
    switch (connection) {
      case DataSourceConnection.PostgreSql postgreSql -> {
        options.put("host", postgreSql.host());
        options.put("port", Integer.toString(postgreSql.port()));
        options.put("database", postgreSql.database());
        options.put("user", postgreSql.username());
        options.put("password", postgreSql.password().reveal());
      }
      // The MySQL Connector takes no database: the registered tables name theirs.
      case DataSourceConnection.MySql mySql -> {
        options.put("host", mySql.host());
        options.put("port", Integer.toString(mySql.port()));
        options.put("user", mySql.username());
        options.put("password", mySql.password().reveal());
        options.put("tinyint1_as_boolean", "true");
      }
    }
    return options;
  }

  private static ApplicationFailure registrationFailed() {
    return new ApplicationFailure(
        "SEMANTIC_CATALOG_REGISTRATION_FAILED",
        "The table metadata did not reach the Semantic Catalog. Run `metadata retrieve` for the"
            + " data source to retry.");
  }

  private static JsonNode parse(String document) {
    try {
      return JSON.readTree(document);
    } catch (IOException e) {
      throw new ApplicationFailure(
          "TABLE_METADATA_RETRIEVAL_FAILED",
          "The connector returned source metadata that is not a JSON document.");
    }
  }

  /** The source metadata schema the library publishes for a connector, with its digest. */
  private static TableMetadataObservation.SourceMetadataSchema sourceMetadataSchema(
      ConnectorLibrary library, String connector) {
    var schema = library.sourceMetadataSchemas().get(connector);
    if (schema == null) {
      throw new IllegalStateException(
          "The connector library publishes no source metadata schema for " + connector);
    }
    return new TableMetadataObservation.SourceMetadataSchema(digest(schema), schema);
  }

  private static String digest(JsonNode content) {
    try {
      var bytes = CANONICAL_JSON.writeValueAsString(content).getBytes(StandardCharsets.UTF_8);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (JsonProcessingException | NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
