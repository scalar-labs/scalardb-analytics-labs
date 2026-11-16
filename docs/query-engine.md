# Query Engine

The Query Engine embeds DuckDB in the application process and presents SQL execution as a
product-owned capability. Its public data-plane contract is an Apache Arrow stream. The boundary
keeps JDBC and DuckDB-specific types out of application use cases while preserving Arrow schemas,
types, and batches across engine integrations.

DuckDB is the first embedded Query Engine and works as an engine within one domain. A Query Engine
integration takes connector Arrow output as engine scans, offers pushdown candidates to connectors
and rewrites its plan by their answer, and maps stale registrations, unsupported types, and source
failures to engine errors; for DuckDB, the connectors' DuckDB extension does this on the connector
read path. **[Planned]** It also binds Physical Catalog tables and Analytics types into the engine
instead of reading them from the connector. The connector repository is authoritative for that
model: see [Plan pushdown with
Query Engine
planners](https://github.com/scalar-labs/scalardb-analytics-connectors/blob/main/docs/plan-pushdown-with-query-engine-planners.md),
[Query Engine
pushdown](https://github.com/scalar-labs/scalardb-analytics-connectors/blob/main/docs/query-engine-pushdown.md),
and the [connector
contract](https://github.com/scalar-labs/scalardb-analytics-connectors/blob/main/docs/connector-contract.md).
This document explains how the integration is built here.

## Boundary and lifecycle

`application` depends on the `QueryEngine` and `QueryResult` interfaces in the `query-engine`
module. `QueryResult` exposes an `ArrowReader` rather than a second product-specific schema and
value model. The `duckdb` module owns the concrete engine, its connection, Arrow allocator, JDBC
resources, and bounded execution controls. JDBC types do not cross either public boundary, and
DuckDB-specific controls do not become requirements for other Query Engine implementations.

An engine instance owns its embedded connection for the application-service lifetime. Query
results stream in bounded Arrow record batches. One execution may be active on an engine instance
at a time. A result owns its Arrow reader and the underlying JDBC resources, so callers must close
it before starting another execution or closing the engine. Statements that do not return a result
are rejected by this query-only contract. Before execution, the product extension uses DuckDB's
parser to require exactly one `SELECT` statement. DDL, DML, malformed SQL, and multiple-statement
input are rejected before any submitted statement can modify engine state.

`DuckDbQueryExecutionOptions` applies optional cumulative row and Arrow-buffer byte limits, a
wall-clock time limit, and a DuckDB plus Arrow memory limit. `DuckDbQueryExecution` provides the
execution ID, completion, and idempotent cancellation lifecycle. These APIs remain in the `duckdb`
module because memory enforcement and interruption semantics are engine-specific. Row and byte
checks happen as record batches are consumed, without materializing the full result. Time limits
and explicit cancellation use DuckDB interruption. A limit or cancellation closes the Arrow,
JDBC, and per-query allocator resources; the resulting typed error retains the execution ID.

## Execution and trust boundary

The application passes SQL into the engine and receives the Arrow schema and record batches. The
CLI is an adapter over that contract: it materializes at most 1,000 rows into a versioned JSON
envelope and reports whether output was truncated. JSON conversion rules, such as Base64 for
binary values, strings for decimal values, and ISO-8601 strings for dates, times, and timestamps,
are CLI concerns and are not part of the Query Engine contract.

Query failures use a sealed `QueryError` hierarchy whose variants are records. This keeps the
engine error contract closed and lets each application adapter exhaustively encode it. The CLI maps
those variants to stable uppercase error codes in its versioned JSON envelope. Query failures only
expose the execution ID and a redacted typed error; DuckDB diagnostics that can contain submitted
SQL or connection details do not cross the public exception boundary.

Runtime extension installation and automatic loading are disabled. Required product and connector
artifacts are version-aligned parts of the distribution rather than network-fetched runtime
dependencies. Catalog administration is explicit: loading an extension or attaching a source does
not implicitly create product catalog state.

## Source attachment and the connector path

`DuckDbQueryEngine` takes the data sources to make queryable as `SourceAttachment` values when it
starts. An attachment has a name and attaches its source; the engine knows nothing else about the
source, so its API does not depend on source types. The engine reports each attached source with
the connectors' DuckDB extension that reads it and the version DuckDB gives for that extension,
which construction-run SQL records with what it read. Each source becomes a DuckDB database named
after the data source, and SQL addresses its tables as `source.schema.table`. The application
resolves the data sources of one Physical Catalog catalog and attaches each of them through its
Connector.

```text
application --resolveConnection--> Physical Catalog
     |
     +-- one attachment per data source --> SourceAttachment --> DuckDbQueryEngine
                                               |
                                               +-- connector path --secret + ATTACH--> connectors extension --> Connector --> PostgreSQL, MySQL
```

Every source type reads through the connector path, and a new source type is added as a Connector
in the connectors distribution.

Attachment happens once, before external access is disabled. DuckDB refuses source attachments
afterwards but keeps attached sources queryable, so a running engine sees exactly the sources it
started with. Initialization fails with a typed error that names only the data source if any source cannot be
attached, including when its name would collide with another source or a DuckDB built-in database
or schema. An attachment reaches the connection only through `AttachSession`, which binds values
such as credentials as statement parameters and reports failures without DuckDB diagnostics.

Query validation rejects any query that calls a core function that runs SQL supplied as a value
(`query`, `query_table`, `json_execute_serialized_sql`) or a function that a packaged extension
declares as denied in its function policy. The check reads function names from DuckDB's serialized
parse tree, so the query is never bound and no connection is opened.

SQL can read anything the source account can read through the attached database, including the
source's own system catalogs. Source accounts should therefore be read-only and limited to the data
meant for analysis.

### Connector path

The connector path reads a source through the DuckDB extension of the connectors distribution
(`scalardb_analytics_connectors`, see [`../connector/README.md`](../connector/README.md)). The
distribution is staged whole once per process, because the extension finds its library and ADBC
drivers relative to itself, and every engine with such a source loads the extension from there. The
extension uses the library already initialized in the process: `ApplicationRuntime` initializes it
before it starts an engine with such a source and shuts it down after the engine closes. An engine
started without that owner, as in this module's tests, has the extension initialize the library,
which then stays initialized until the process exits.

- `ConnectorPath` binds the source's connection settings as DuckDB variables, creates a temporary
  `scalardb_analytics_connectors` secret from them with the Connector as its provider, resets
  the variables, and attaches the source with `ATTACH '' AS <name> (TYPE
  scalardb_analytics_connectors, SECRET ..., READ_ONLY)`. No connection value appears in SQL
  text, and `duckdb_secrets()` shows the password redacted.
- A MySQL source is attached with `tinyint1_as_boolean` set, so `tinyint(1)`, which MySQL's `BOOL`
  and `BOOLEAN` declare, reads as `BOOLEAN`. A `tinyint(1)` value
  other than 0 and 1 then fails the read as a data error rather than being read as some value.
  The MySQL Connector takes no database: it reads the whole server, every database a schema, and
  qualifies each table with its database.
- The attached catalog reads namespaces, tables, and table metadata from the Connector itself, so
  it shows everything the source account can see. A MySQL source registered with a database
  therefore shows every database the account can see, not only that one. **[Planned]** It is to
  read the table metadata the Physical Catalog registered instead.
- Each scan offers its filters and projection to the Connector's pushdown planning and reads the
  remote execution's Arrow stream. Limits, sorts, aggregations, and joins are not offered to
  Connectors yet, so DuckDB applies them. A Connector runs remotely only the comparisons its source
  evaluates the same way, so, for example, MySQL receives a text comparison only on a column under
  a binary collation. The SQL Connectors (PostgreSQL and MySQL) move a scan's filter condition to
  the source only when the whole condition can run there; otherwise the whole condition runs in
  DuckDB. Splitting an AND into the parts that can run remotely is a known gap of their shared
  planner, which the connector contract allows. Columns without an Analytics type, such as MySQL
  `time` and `set`, are not visible, and a table whose shape no longer matches the source fails
  with a stale registration. The extension's
  [README](https://github.com/scalar-labs/scalardb-analytics-connectors/blob/main/adapters/duckdb/README.md)
  describes its catalog, planning, and type mapping.
- The extension's definitions-file pragma and scan function are denied by query validation, so SQL
  reaches sources only through the attached databases.
- The extension's request time limits are the DuckDB settings `scalardb_analytics_connection_timeout`,
  `scalardb_analytics_metadata_timeout`, and `scalardb_analytics_read_timeout`, all unlimited by
  default. A request already sent to the source is not interrupted by a query time limit or
  cancellation; it ends when the source answers.

## Extension model

Product query behavior integrates with DuckDB through loadable extensions. The `duckdb` build
produces a product-owned C++ extension against the same DuckDB release used by the Java binding,
packages it with the module, and loads it when the Query Engine starts. Keeping that boundary allows
the distribution to load the connectors' extension and to add product behavior without fixing every
extension into the application executable. The distribution owns inclusion of the
verified artifacts, and the Query Engine owns loading and using them.

See [`design/duckdb-distribution.md`](design/duckdb-distribution.md) for the packaging decision and
its rationale.

## Catalog host

The product extension does not load or start product components. Its host, the application,
supplies them after loading the extension. The ABI is declared in
`duckdb/src/main/cpp/include/scalardb_analytics_host.h`:

- **Registration.** The host registers one versioned, sized table with
  `scalardb_analytics_register_host`. The table currently carries a catalog provider. Later
  versions can append members, such as connector library handles. The extension copies the table
  and exports only the registration functions besides its DuckDB entry point.
- **Calls.** The catalog provider answers unary gRPC calls in serialized form. Each call runs inside
  a scope that owns the response memory until the extension ends the scope. Failures are
  serialized `google.rpc.Status` values.
- **Threads.** Callbacks may run concurrently on any thread, including DuckDB worker threads, and
  never unwind across the boundary.
- **Unregistration.** `scalardb_analytics_unregister_host` refuses while a scope is open.

`DuckDbQueryEngine` takes an optional `NativeCatalogProvider`. It registers the provider through
FFM upcalls right after loading the extension and unregisters it after closing the connection, so
no DuckDB thread can call the provider outside the engine's lifetime. DuckDB cannot interrupt a
call in progress, so query cancellation and time limits take effect once the call returns. The
provider bounds how long that takes; the Physical Catalog provider uses a per-call deadline.
`scalardb_analytics_catalog_status()` reports the catalog's serving status and is the current SQL
consumer of the provider. The provider's contract on the catalog side is described in
[`physical-catalog.md`](physical-catalog.md).
