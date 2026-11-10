# Physical Catalog

The Physical Catalog records which source databases are registered as logical data sources and what
their schemas contain, so that a Query Engine can bind names and types without first connecting to
each source. It holds only what query planning, execution, and browsing need, plus what it needs to
manage the versions they use. ScalarDB Analytics does not reimplement it: the application embeds the
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
  PostgreSQL or MySQL data source, resolve registered data sources, and list their recorded
  namespaces, tables, and columns. Registration connects to the source to resolve its schema, so it
  fails when the source is unreachable.
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
catalog, so the extension can never call a stopped catalog. Every state-bearing CLI invocation
loads one workspace configuration, starts this runtime against its catalog store, and exits after
the requested operation.

## Catalog model

The catalog provides catalog and data-source registration, listing, lookup, and unregistration;
retrieval of the table metadata of registered tables; versioned persistence of the parts of that
metadata used for planning; a resource identifier for each table; name resolution for Query Engines,
including from a resource identifier to the current table name; and change notifications from
registration and re-retrieval. The application drives retrieval and hands the same metadata to the
Semantic Catalog; see [`application.md`](application.md#table-metadata-retrieval).

The catalog's model is owned by the upstream repository, so changes to it are made there, not in
this repository. In the target composition, registration and re-retrieval obtain table metadata
through the connector's metadata retrieval rather than the upstream `schema-resolver`. The connector
reads the source's own catalog and returns source types and Analytics types, and the catalog stores
the parts used for planning and execution without reinterpreting them. `DataType` and `ColumnSchema`
become able to hold structured types (`ARRAY`, `MAP`, `STRUCT`) and extension-encoded types, and the
gRPC `ColumnSchema.type` becomes a structured message. Until a source's connector is complete, the
interim scanner path produces table metadata of the same shape; see
[`query-engine.md`](query-engine.md#interim-scanner-path).

Table metadata is divided between the two catalogs so that no source declaration is copied where
nothing uses it:

| Item | Physical Catalog | Semantic Catalog |
| --- | --- | --- |
| Catalog, namespace, table name, and table kind | Persisted; used for name resolution | Part of its input |
| Column name, ordinal, nullability, and source type | Persisted; used for planning and for showing unsupported columns | Part of its input |
| Column Analytics type, or unsupported | Persisted; used for planning | Kept as a derived record |
| Constraints (primary keys, unique constraints and indexes, foreign keys, CHECK constraints) | Not persisted | Part of its input |
| Source-side identifier | Not persisted | Part of its input |
| Source schema version | Not persisted | Not recorded; it changes on non-schema events, so it cannot detect a change |
| Completeness, missing items, and "does not exist" | Persisted; used to avoid inferring deletions from missing information | Part of its input |
| "Not retrieved" | Not made a version; reported in the change notification | Not received |
| Retrieval information and content digest | Persisted with the version; used for version management | Kept as a derived record |

Query Engines do not use constraints, so their absence from the Physical Catalog changes neither
planning nor execution. Users and Query Agents who need keys and constraints get them from the
Semantic Catalog. Collation, character set, and comments are needed by the Semantic Catalog to judge
joins and propose meanings, but they are not yet items of table metadata.

A **resource identifier** points to one registered table. The catalog issues one per combination of
data-source registration, namespace, and table name, and keeps it until an administrator removes
that table's or source's registration. A column is the pair of a resource identifier and a column
name. The catalog never judges table identity; whether a table was renamed or replaced is a Semantic
Catalog Fact.
[`design/physical-catalog-resource-identifier.md`](design/physical-catalog-resource-identifier.md)
records why identity is by name.

| Change at the source | In the Physical Catalog |
| --- | --- |
| Table renamed | The old name's identifier gets a "does not exist" version. The new name gets a new identifier when a user registers it |
| Dropped and re-created under the same name | Same identifier. A source that returns source-side identifiers produces a new version; one that does not produces none if columns and constraints are also unchanged |
| Dropped | A "does not exist" version. A table later created under the name is a new version of the same identifier |

A **table metadata version** is a monotonically increasing generation per resource identifier. A new
version is created when the content digest differs from the current version's, or when the
connector, its version, the library version, the ADBC driver, its version, or the fixed settings in
the retrieval information differ, because any of those can change Analytics types for the same
source. A change of retrieval time alone updates the time of last confirmation. Digests are compared
only between metadata from the same library MAJOR.MINOR. A different library version always makes a
new version, so a library or connector update passes every table to the Semantic Catalog once, and
the Semantic Catalog records only a derived record where source declarations did not change. "Not
retrieved" metadata is never a version, so missing privileges or a transient failure cannot remove
columns used for planning. Incomplete metadata is a version, and the catalog infers no deletion from
a missing item.

A **change notification**, returned per table by each retrieval, carries the resource identifier,
the source, namespace, and table name, the previous and new versions, and a state of new, changed,
does not exist, not retrieved, or unchanged.

Analytics types are persisted in a normalized relational form in the ScalarDB store, with the type
category, nesting, sharing, and references visible in the schema;
[`design/physical-catalog-type-persistence.md`](design/physical-catalog-type-persistence.md) records
the shape. The structure of Analytics types themselves is defined by the connectors' [Analytics type
model](https://github.com/scalar-labs/scalardb-analytics-connectors/blob/main/docs/analytics-types.md).

## Ownership

The catalog is authoritative for the physical schemas and Analytics types used for planning and
execution and does not depend on the Semantic Catalog. A new version becomes new Semantic Catalog
input, never a direct change to a confirmed Fact. Creating a catalog or data source is an explicit
administrative operation; startup, extension loading, and `ATTACH` never create one. Administrative
operations and query-time name resolution use the same catalog use cases.

The catalog does not own:

- business meaning, Facts, candidates, validation results, provenance, or human decisions;
- persistence of source declarations that planning does not use, such as constraints and source-side
  identifiers;
- judging table identity;
- Query Agent discovery or SQL generation;
- reading data rows or executing queries;
- pushdown decisions, which connectors make against current source and session facts rather than the
  catalog's older observation;
- access-policy evaluation or enforcement for a principal; or
- new per-source Java implementations of source-specific behavior.

## Source boundary

The catalog source is the pinned `upstream/scalardb-analytics` submodule. The root build includes
its Gradle build and depends on the catalog modules as projects. The build records the source
revision, the resolved ScalarDB builds, and their licenses in
`META-INF/scalardb-analytics-labs/physical-catalog.properties`.

The decisions behind this shape are recorded in:

- [`design/physical-catalog-hosting.md`](design/physical-catalog-hosting.md)
- [`design/physical-catalog-source-boundary.md`](design/physical-catalog-source-boundary.md)
