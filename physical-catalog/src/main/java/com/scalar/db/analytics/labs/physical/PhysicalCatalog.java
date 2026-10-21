/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.physical;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.scalar.db.analytics.api.error.AnalyticsErrorCode;
import com.scalar.db.analytics.api.error.AnalyticsException;
import com.scalar.db.analytics.api.model.datasource.DataSourceProvider;
import com.scalar.db.analytics.api.model.datasource.provider.rdbms.MySql;
import com.scalar.db.analytics.api.model.datasource.provider.rdbms.PostgreSql;
import com.scalar.db.analytics.grpc.generated.catalog.v1.CatalogServiceGrpc;
import com.scalar.db.analytics.grpc.generated.catalog.v1.CreateCatalogRequest;
import com.scalar.db.analytics.grpc.generated.datasource.v1.DataSource;
import com.scalar.db.analytics.grpc.generated.datasource.v1.DataSourceServiceGrpc;
import com.scalar.db.analytics.grpc.generated.datasource.v1.FindDataSourceRequest;
import com.scalar.db.analytics.grpc.generated.datasource.v1.ListDataSourcesRequest;
import com.scalar.db.analytics.grpc.generated.datasource.v1.RegisterRequest;
import com.scalar.db.analytics.grpc.generated.error.v1.AnalyticsError;
import com.scalar.db.analytics.grpc.generated.namespace.v1.ListNamespacesRequest;
import com.scalar.db.analytics.grpc.generated.namespace.v1.NamespaceServiceGrpc;
import com.scalar.db.analytics.grpc.generated.table.v1.DescribeTableRequest;
import com.scalar.db.analytics.grpc.generated.table.v1.ListTablesRequest;
import com.scalar.db.analytics.grpc.generated.table.v1.TableServiceGrpc;
import com.scalar.db.analytics.server.grpc.CatalogServiceImpl;
import com.scalar.db.analytics.server.grpc.DataSourceServiceImpl;
import com.scalar.db.analytics.server.grpc.ExceptionHandlingInterceptor;
import com.scalar.db.analytics.server.grpc.HealthService;
import com.scalar.db.analytics.server.grpc.NamespaceServiceImpl;
import com.scalar.db.analytics.server.grpc.TableServiceImpl;
import com.scalar.db.analytics.usecase.DataSourceUseCase;
import io.grpc.BindableService;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.ClientCalls;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The upstream Physical Catalog embedded in the application process.
 *
 * <p>The catalog keeps its state in the configured ScalarDB store, which is the source of truth;
 * this object holds no catalog state of its own. Its upstream gRPC service implementations are
 * reachable only through an in-process server. Application code uses the typed operations, and
 * native consumers such as the DuckDB extension use {@link #callSerialized(String, byte[])}, which
 * admits only read-only methods. Every response and failure leaving the catalog has its credentials
 * removed. Only {@link #resolveConnection(String, String)} returns credentials, and only to
 * application code.
 *
 * <p>An instance is safe for concurrent use. Catalog work runs on the catalog's own threads. A
 * serialized call returns {@code DEADLINE_EXCEEDED} to its caller once the configured timeout
 * elapses, even if the store has not answered. Typed operations wait for the store without a labs
 * deadline. Calls that start after {@link #close()} fail as unavailable.
 */
public final class PhysicalCatalog implements AutoCloseable {
  private static final String SERVER_NAME_PREFIX = "scalardb-analytics-physical-catalog-";
  private static final long SHUTDOWN_TIMEOUT_SECONDS = 10;

  /** Methods a native consumer may call. None of them modifies catalog state. */
  private static final Set<String> SERIALIZED_READ_METHODS =
      Set.of(
          HealthGrpc.getCheckMethod().getFullMethodName(),
          CatalogServiceGrpc.getFindCatalogMethod().getFullMethodName(),
          CatalogServiceGrpc.getFindCatalogByIdMethod().getFullMethodName(),
          CatalogServiceGrpc.getListAllCatalogsMethod().getFullMethodName(),
          DataSourceServiceGrpc.getFindDataSourceMethod().getFullMethodName(),
          DataSourceServiceGrpc.getFindDataSourceByIdMethod().getFullMethodName(),
          DataSourceServiceGrpc.getListDataSourcesMethod().getFullMethodName(),
          NamespaceServiceGrpc.getListNamespacesMethod().getFullMethodName(),
          NamespaceServiceGrpc.getDescribeNamespaceMethod().getFullMethodName(),
          NamespaceServiceGrpc.getDescribeNamespaceByIdMethod().getFullMethodName(),
          TableServiceGrpc.getListTablesMethod().getFullMethodName(),
          TableServiceGrpc.getListTablesByNamespaceMethod().getFullMethodName(),
          TableServiceGrpc.getDescribeTableMethod().getFullMethodName(),
          TableServiceGrpc.getDescribeTableByIdMethod().getFullMethodName());

  private final ConfigurableApplicationContext context;
  private final ExecutorService serverExecutor;
  private final Server server;
  private final ManagedChannel channel;
  private final Duration serializedCallTimeout;
  private final CatalogServiceGrpc.CatalogServiceBlockingStub catalogs;
  private final DataSourceServiceGrpc.DataSourceServiceBlockingStub dataSources;
  private final NamespaceServiceGrpc.NamespaceServiceBlockingStub namespaces;
  private final TableServiceGrpc.TableServiceBlockingStub tables;
  private final DataSourceUseCase dataSourceUseCase;
  private final Map<String, MethodDescriptor<byte[], byte[]>> serializedMethods =
      new ConcurrentHashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean();

  private PhysicalCatalog(
      ConfigurableApplicationContext context,
      ExecutorService serverExecutor,
      Server server,
      ManagedChannel channel,
      Duration serializedCallTimeout) {
    this.context = context;
    this.serverExecutor = serverExecutor;
    this.server = server;
    this.channel = channel;
    this.serializedCallTimeout = serializedCallTimeout;
    catalogs = CatalogServiceGrpc.newBlockingStub(channel);
    dataSources = DataSourceServiceGrpc.newBlockingStub(channel);
    namespaces = NamespaceServiceGrpc.newBlockingStub(channel);
    tables = TableServiceGrpc.newBlockingStub(channel);
    dataSourceUseCase = context.getBean(DataSourceUseCase.class);
  }

  /**
   * Opens the configured store, applies the upstream schema migration, and starts the catalog.
   *
   * @throws PhysicalCatalogException if the store cannot be opened or initialized
   */
  public static PhysicalCatalog start(PhysicalCatalogSettings settings) {
    ConfigurableApplicationContext context = null;
    // Catalog work runs on these threads rather than the caller's, so a deadline can release a
    // caller, such as a DuckDB worker thread, while the store is still answering.
    var serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
    Server server = null;
    try {
      context =
          new SpringApplicationBuilder(EmbeddedCatalogConfiguration.class)
              .environment(environment(settings))
              .web(WebApplicationType.NONE)
              .bannerMode(Banner.Mode.OFF)
              .logStartupInfo(false)
              .registerShutdownHook(false)
              .run();

      var serverName = SERVER_NAME_PREFIX + UUID.randomUUID();
      var serverBuilder = InProcessServerBuilder.forName(serverName).executor(serverExecutor);
      for (BindableService service : services(context)) {
        // The last interceptor sees a call first. Redaction therefore wraps every response and
        // every failure, including failures produced by upstream exception handling.
        serverBuilder.addService(
            ServerInterceptors.intercept(
                service,
                new EmbeddedIdentityInterceptor(),
                new ExceptionHandlingInterceptor(),
                new CredentialRedactingInterceptor()));
      }
      server = serverBuilder.build().start();
      var channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
      return new PhysicalCatalog(
          context, serverExecutor, server, channel, settings.serializedCallTimeout());
    } catch (IOException | RuntimeException e) {
      if (server != null) {
        server.shutdownNow();
      }
      serverExecutor.shutdownNow();
      if (context != null) {
        try {
          context.close();
        } catch (RuntimeException ignored) {
          // The initialization failure is the error reported to the caller.
        }
      }
      throw new PhysicalCatalogException(
          new PhysicalCatalogError.InitializationFailed(
              "Could not start the Physical Catalog with the configured store."));
    }
  }

  /** Creates an empty catalog that data sources can be registered into. */
  public void createCatalog(String catalogName) {
    requireName(catalogName, "catalogName");
    call(
        () ->
            catalogs.createCatalog(
                CreateCatalogRequest.newBuilder().setCatalogName(catalogName).build()));
  }

  /**
   * Registers a source database as a logical data source.
   *
   * <p>The catalog connects to the source to resolve its schema before recording it, so
   * registration fails if the source cannot be reached with the given settings.
   */
  public DataSourceDescriptor registerDataSource(
      String catalogName, String dataSourceName, DataSourceConnection connection) {
    requireName(catalogName, "catalogName");
    requireName(dataSourceName, "dataSourceName");
    var request =
        RegisterRequest.newBuilder()
            .setCatalogName(catalogName)
            .setDataSourceName(dataSourceName)
            .setProviderPayloadJson(ProviderPayloads.toPayload(connection))
            .build();
    var response = call(() -> dataSources.register(request));
    return toDescriptor(catalogName, response.getDataSource());
  }

  /** Resolves a registered logical data source by name. */
  public Optional<DataSourceDescriptor> findDataSource(String catalogName, String dataSourceName) {
    requireName(catalogName, "catalogName");
    requireName(dataSourceName, "dataSourceName");
    var response =
        call(
            () ->
                dataSources.findDataSource(
                    FindDataSourceRequest.newBuilder()
                        .setCatalogName(catalogName)
                        .setDataSourceName(dataSourceName)
                        .build()));
    return response.hasDataSource()
        ? Optional.of(toDescriptor(catalogName, response.getDataSource()))
        : Optional.empty();
  }

  /** Lists the logical data sources registered in a catalog. */
  public List<DataSourceDescriptor> listDataSources(String catalogName) {
    requireName(catalogName, "catalogName");
    var response =
        call(
            () ->
                dataSources.listDataSources(
                    ListDataSourcesRequest.newBuilder().setCatalogName(catalogName).build()));
    return response.getDataSourcesList().stream()
        .map(dataSource -> toDescriptor(catalogName, dataSource))
        .toList();
  }

  /** Lists the namespaces recorded for one logical data source. */
  public List<PhysicalNamespace> listNamespaces(String catalogName, String dataSourceName) {
    requireName(catalogName, "catalogName");
    requireName(dataSourceName, "dataSourceName");
    var response =
        call(
            () ->
                namespaces.listNamespaces(
                    ListNamespacesRequest.newBuilder().setCatalogName(catalogName).build()));
    return response.getNamespacesList().stream()
        .filter(namespace -> namespace.getDataSource().getName().equals(dataSourceName))
        .map(
            namespace ->
                new PhysicalNamespace(dataSourceName, namespace.getNamespace().getNamesList()))
        .sorted(Comparator.comparing(namespace -> String.join("\u0000", namespace.names())))
        .toList();
  }

  /** Lists the tables and columns recorded for one logical data source. */
  public List<PhysicalTable> listTables(String catalogName, String dataSourceName) {
    requireName(catalogName, "catalogName");
    requireName(dataSourceName, "dataSourceName");
    var response =
        call(
            () ->
                tables.listTables(
                    ListTablesRequest.newBuilder().setCatalogName(catalogName).build()));
    return response.getTablesList().stream()
        .filter(table -> table.getDataSource().getName().equals(dataSourceName))
        .map(
            table -> {
              var namespaceNames = table.getNamespace().getNamesList();
              var tableName = table.getTable().getInfo().getName();
              var detail =
                  call(
                      () ->
                          tables.describeTable(
                              DescribeTableRequest.newBuilder()
                                  .setCatalogName(catalogName)
                                  .setDataSourceName(dataSourceName)
                                  .addAllNamespaceNames(namespaceNames)
                                  .setTableName(tableName)
                                  .build()));
              if (!detail.hasTable()) {
                throw new PhysicalCatalogException(
                    new PhysicalCatalogError.OperationFailed(
                        "A table was removed while its metadata was read."));
              }
              var columns =
                  detail.getTable().getTableDetail().getColumnsList().stream()
                      .map(
                          column ->
                              new PhysicalTable.Column(
                                  column.getName(),
                                  column.getType(),
                                  column.getOrdinalPosition(),
                                  column.getNullable()))
                      .sorted(Comparator.comparingInt(PhysicalTable.Column::ordinalPosition))
                      .toList();
              return new PhysicalTable(dataSourceName, namespaceNames, tableName, columns);
            })
        .sorted(
            Comparator.comparing(
                    (PhysicalTable table) -> String.join("\u0000", table.namespaceNames()))
                .thenComparing(PhysicalTable::name))
        .toList();
  }

  /**
   * Resolves the connection settings of a registered data source, including its credentials.
   *
   * <p>This operation is for application code that must connect to the source itself. It reads the
   * catalog through the upstream use case rather than the in-process gRPC server, so it is
   * unreachable through {@link #callSerialized(String, byte[])}. Callers must not pass the returned
   * secret into SQL text.
   */
  public Optional<DataSourceConnection> resolveConnection(
      String catalogName, String dataSourceName) {
    requireName(catalogName, "catalogName");
    requireName(dataSourceName, "dataSourceName");
    if (closed.get()) {
      throw new PhysicalCatalogException(
          new PhysicalCatalogError.Unavailable("The Physical Catalog is unavailable."));
    }
    Optional<com.scalar.db.analytics.api.model.DataSource> dataSource;
    try {
      dataSource =
          dataSourceUseCase.findDataSource(
              EmbeddedIdentityInterceptor.APPLICATION_USER_ID, catalogName, dataSourceName);
    } catch (AnalyticsException e) {
      throw new PhysicalCatalogException(toError(e.getErrorCode()));
    } catch (RuntimeException e) {
      throw new PhysicalCatalogException(
          new PhysicalCatalogError.OperationFailed(
              "The Physical Catalog operation could not be completed."));
    }
    return dataSource.map(source -> toConnection(source.getProvider()));
  }

  /**
   * Performs one read-only unary call in serialized gRPC form.
   *
   * <p>This is the entry point for native consumers. The method name is the fully qualified gRPC
   * method name. Methods that could modify catalog state are refused with {@code
   * PERMISSION_DENIED}. A call that does not finish within the configured timeout returns {@code
   * DEADLINE_EXCEEDED}. This method never throws for call failures: every failure, including a
   * closed catalog, is returned as a serialized status.
   */
  public SerializedCallResult callSerialized(String fullMethodName, byte[] request) {
    if (!SERIALIZED_READ_METHODS.contains(fullMethodName)) {
      return failure(Status.Code.PERMISSION_DENIED);
    }
    try {
      var method = serializedMethods.computeIfAbsent(fullMethodName, PhysicalCatalog::byteMethod);
      var options =
          CallOptions.DEFAULT.withDeadlineAfter(
              serializedCallTimeout.toNanos(), TimeUnit.NANOSECONDS);
      return new SerializedCallResult.Response(
          ClientCalls.blockingUnaryCall(channel, method, options, request));
    } catch (StatusRuntimeException e) {
      // The redacting interceptor already reduced server failures to code and error code. Client
      // side failures, such as a closed channel, carry only a gRPC code and description.
      var status = StatusProto.fromThrowable(e);
      return new SerializedCallResult.Failure(
          status != null
              ? status.toByteArray()
              : statusOf(e.getStatus().getCode(), "").toByteArray());
    } catch (RuntimeException e) {
      return failure(Status.Code.INTERNAL);
    }
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    var failed = false;
    channel.shutdownNow();
    server.shutdownNow();
    serverExecutor.shutdownNow();
    try {
      if (!server.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
          || !channel.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        failed = true;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      failed = true;
    }
    try {
      context.close();
    } catch (RuntimeException e) {
      failed = true;
    }
    if (failed) {
      throw new PhysicalCatalogException(
          new PhysicalCatalogError.ShutdownFailed("Could not close the Physical Catalog."));
    }
  }

  private static List<BindableService> services(ConfigurableApplicationContext context) {
    return List.of(
        context.getBean(CatalogServiceImpl.class),
        context.getBean(DataSourceServiceImpl.class),
        context.getBean(NamespaceServiceImpl.class),
        context.getBean(TableServiceImpl.class),
        context.getBean(HealthService.class));
  }

  private static StandardEnvironment environment(PhysicalCatalogSettings settings) {
    Map<String, Object> properties = new HashMap<>();
    // Ignore application.properties files from the classpath and the working directory: the
    // embedded catalog is configured only by the application.
    properties.put(
        "spring.config.location", "optional:classpath:/scalardb-analytics-labs/physical-catalog/");
    properties.put("spring.main.banner-mode", "off");
    properties.put("spring.main.allow-bean-definition-overriding", "false");
    properties.put("logging.level.root", "OFF");
    properties.put("scalar.db.analytics.server.db.contact-points", settings.storeJdbcUrl());
    settings
        .storeUsername()
        .ifPresent(username -> properties.put("scalar.db.analytics.server.db.username", username));
    if (!settings.storePassword().isEmpty()) {
      properties.put("scalar.db.analytics.server.db.password", settings.storePassword().reveal());
    }
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(new MapPropertySource("scalardbAnalyticsLabsPhysicalCatalog", properties));
    return environment;
  }

  private <T> T call(java.util.function.Supplier<T> operation) {
    try {
      return operation.get();
    } catch (StatusRuntimeException e) {
      throw new PhysicalCatalogException(toError(e));
    }
  }

  private static PhysicalCatalogError toError(StatusRuntimeException exception) {
    var errorCode = analyticsErrorCode(exception);
    if (errorCode == null) {
      return exception.getStatus().getCode() == Status.Code.UNAVAILABLE
          ? new PhysicalCatalogError.Unavailable("The Physical Catalog is unavailable.")
          : new PhysicalCatalogError.OperationFailed(
              "The Physical Catalog operation could not be completed.");
    }
    return toError(errorCode);
  }

  private static PhysicalCatalogError toError(AnalyticsErrorCode errorCode) {
    return switch (errorCode) {
      case CATALOG_ALREADY_EXISTS, DATA_SOURCE_ALREADY_EXISTS ->
          new PhysicalCatalogError.AlreadyExists("The catalog or data source already exists.");
      case DATA_SOURCE_UNREACHABLE, DATA_SOURCE_AUTHENTICATION_FAILED ->
          new PhysicalCatalogError.DataSourceUnreachable(
              "The data source could not be reached with the given connection settings.");
      default -> {
        if (errorCode.isRetryable()) {
          yield new PhysicalCatalogError.Unavailable("The Physical Catalog store is unavailable.");
        }
        if (errorCode.isUserError()) {
          yield new PhysicalCatalogError.InvalidRequest(
              "The Physical Catalog rejected the request.");
        }
        yield new PhysicalCatalogError.OperationFailed(
            "The Physical Catalog operation could not be completed.");
      }
    };
  }

  private static @Nullable AnalyticsErrorCode analyticsErrorCode(StatusRuntimeException exception) {
    var status = StatusProto.fromThrowable(exception);
    if (status == null) {
      return null;
    }
    for (Any detail : status.getDetailsList()) {
      if (detail.is(AnalyticsError.class)) {
        try {
          var code = detail.unpack(AnalyticsError.class).getErrorCode();
          for (var candidate : AnalyticsErrorCode.values()) {
            if (candidate.getCode().equals(code)) {
              return candidate;
            }
          }
        } catch (InvalidProtocolBufferException e) {
          return null;
        }
      }
    }
    return null;
  }

  private static DataSourceDescriptor toDescriptor(String catalogName, DataSource dataSource) {
    // Responses reach this point through the redacting interceptor and carry no credentials.
    return ProviderPayloads.toDescriptor(
            catalogName, dataSource.getName(), dataSource.getProviderPayloadJson())
        .orElseThrow(
            () ->
                new PhysicalCatalogException(
                    new PhysicalCatalogError.OperationFailed(
                        "The Physical Catalog returned an unsupported data source.")));
  }

  private static DataSourceConnection toConnection(DataSourceProvider provider) {
    return switch (provider) {
      case PostgreSql postgreSql ->
          new DataSourceConnection.PostgreSql(
              postgreSql.getHost(),
              postgreSql.getPort(),
              postgreSql.getDatabase(),
              postgreSql.getUsername(),
              Secret.of(postgreSql.getPassword()));
      case MySql mySql ->
          new DataSourceConnection.MySql(
              mySql.getHost(),
              mySql.getPort(),
              Optional.ofNullable(mySql.getDatabase()),
              mySql.getUsername(),
              Secret.of(mySql.getPassword()));
      default ->
          throw new PhysicalCatalogException(
              new PhysicalCatalogError.OperationFailed(
                  "The Physical Catalog returned an unsupported data source."));
    };
  }

  private static void requireName(String value, String name) {
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }

  private static SerializedCallResult failure(Status.Code code) {
    return new SerializedCallResult.Failure(statusOf(code, "").toByteArray());
  }

  private static com.google.rpc.Status statusOf(Status.Code code, String message) {
    return com.google.rpc.Status.newBuilder()
        .setCode(code.value())
        .setMessage(message.isEmpty() ? code.name() : message)
        .build();
  }

  private static MethodDescriptor<byte[], byte[]> byteMethod(String fullMethodName) {
    return MethodDescriptor.<byte[], byte[]>newBuilder()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName(fullMethodName)
        .setRequestMarshaller(ByteMarshaller.INSTANCE)
        .setResponseMarshaller(ByteMarshaller.INSTANCE)
        .build();
  }

  private enum ByteMarshaller implements MethodDescriptor.Marshaller<byte[]> {
    INSTANCE;

    @Override
    public InputStream stream(byte[] value) {
      return new ByteArrayInputStream(value);
    }

    @Override
    public byte[] parse(InputStream stream) {
      try {
        return stream.readAllBytes();
      } catch (IOException e) {
        throw Status.INTERNAL.asRuntimeException();
      }
    }
  }
}
