# Physical Catalog

The Physical Catalog records which source databases are registered as logical data sources and
what their schemas contain. ScalarDB Analytics does not reimplement it: the application embeds the
existing catalog from the public `scalardb-analytics` repository and owns only the boundary around
it. That boundary decides who may call the catalog, keeps source credentials inside it, and ties its
lifetime to the application process.

## Embedding

The `physical-catalog` module starts the upstream catalog inside the application process. It
assembles the upstream store, use-case, and gRPC service implementations without the upstream
server application, so the catalog opens no network listener. The service implementations are
bound to an in-process gRPC server. Every caller in the process uses the upstream service contracts
unchanged, so labs does not duplicate the catalog data model.

Catalog state lives in a ScalarDB store, which is the source of truth. The embedded catalog applies
the upstream schema migration when it starts and keeps no state of its own. Another process
attached to the same store therefore sees the same catalog. The application supplies the store's
JDBC settings through `PhysicalCatalogSettings`; files such as `application.properties` in the
classpath or working directory are ignored.

The embedded catalog runs with upstream authentication disabled and the upstream no-op
authorization. Every call carries one fixed application identity, because only code in this process
can reach the catalog.

## Callers

Two kinds of caller reach the catalog, and they get different surfaces:

```text
application use cases --typed operations--> PhysicalCatalog --in-process gRPC--> upstream services
DuckDB extension --host ABI--> NativeCatalogProvider --callSerialized--> (read-only methods only)
```

- **Application code** uses the typed `PhysicalCatalog` operations: create a catalog, register a
  PostgreSQL or MySQL data source, and resolve registered data sources. Registration connects to
  the source to resolve its schema, so it fails when the source is unreachable.
- **The DuckDB extension** reaches the catalog through the host ABI described in
  [`query-engine.md`](query-engine.md#catalog-host). The application registers the catalog's
  `callSerialized` entry point as the extension's catalog provider. That entry point accepts only
  read-only methods: health, catalog, data source, namespace, and table lookups. Administrative
  operations therefore stay unreachable from SQL.

## Credentials

The upstream catalog stores each provider payload, including the source password, and returns the
payload in every data source, namespace, and table message. Upstream error messages and logs can
also quote it. The boundary handles this in one place, at the edge of the in-process server:

- Every response leaves with the credential fields removed from every provider payload, at any
  nesting depth.
- Every failure leaves with only its gRPC code and upstream error code. Descriptions and error
  metadata are dropped.
- Catalog logging is disabled.
- `Secret` never renders its value, and `PhysicalCatalogException` carries fixed messages and no
  upstream cause.

Only `PhysicalCatalog.resolveConnection` returns a registered data source's credentials. It reads
the catalog through the upstream use case rather than the in-process gRPC server, so no serialized
call can reach it. `ApplicationRuntime` resolves every data source of a catalog there when it starts
the Query Engine, and hands each connection to the read path it chooses for that data source. The
read path passes the secret to DuckDB as a bound parameter and never writes it into SQL text; see
[`query-engine.md`](query-engine.md#source-attachment-and-read-paths).

## Failure and lifecycle

`PhysicalCatalog.start` fails with a typed initialization error if the store cannot be opened or
migrated. Typed operations report closed `PhysicalCatalogError` variants. `callSerialized` never
throws for call failures; every failure becomes a serialized `google.rpc.Status`.

Catalog work runs on the catalog's own threads:

- A serialized call returns `DEADLINE_EXCEEDED` to its caller once
  `PhysicalCatalogSettings.serializedCallTimeout` elapses (10 seconds by default), even if the store
  has not answered. A DuckDB thread therefore never waits longer than that for one catalog call.
- Typed operations wait for the store without a labs deadline.
- Closing the catalog fails later calls as unavailable.

`ApplicationRuntime` starts the catalog first. It starts at most one Query Engine, for one catalog
and with the data sources registered in it at that moment, and closes the engine before the
catalog, so the extension can never call a stopped catalog. The `query` CLI command does not compose the
catalog yet. It will once the CLI has application configuration for the catalog store.

## Source boundary

The catalog source is the pinned `upstream/scalardb-analytics` submodule. The root build includes
its Gradle build and depends on the catalog modules as projects. The build records the source
revision, the resolved ScalarDB builds, and their licenses in
`META-INF/scalardb-analytics-labs/physical-catalog.properties`.

The decisions behind this shape are recorded in:

- [`design/physical-catalog-hosting.md`](design/physical-catalog-hosting.md)
- [`design/physical-catalog-source-boundary.md`](design/physical-catalog-source-boundary.md)
