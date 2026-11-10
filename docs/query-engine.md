# Query Engine

The Query Engine embeds DuckDB in the application process and presents SQL execution as a
product-owned capability. Its public data-plane contract is an Apache Arrow stream. The boundary
keeps JDBC and DuckDB-specific types out of application use cases while preserving Arrow schemas,
types, and batches across engine integrations.

DuckDB is the first embedded Query Engine and works as an engine within one domain. **[Planned]** A
Query Engine integration binds Physical Catalog tables and Analytics types into the engine, takes
connector Arrow output as engine scans, offers pushdown candidates to connectors and rewrites its
plan by their answer, and maps stale registrations, unsupported types, and source failures to
product errors. The connector repository is authoritative for that model: see [Plan pushdown with
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
binary values and strings for decimal values, are CLI concerns and are not part of the Query Engine
contract.

Query failures use a sealed `QueryError` hierarchy whose variants are records. This keeps the
engine error contract closed and lets each application adapter exhaustively encode it. The CLI maps
those variants to stable uppercase error codes in its versioned JSON envelope. Query failures only
expose the execution ID and a redacted typed error; DuckDB diagnostics that can contain submitted
SQL or connection details do not cross the public exception boundary.

Runtime extension installation and automatic loading are disabled. Required product, scanner, and
connector artifacts are version-aligned parts of the distribution rather than network-fetched
runtime dependencies. Catalog administration is explicit: loading an extension or attaching a
source does not implicitly create product catalog state.

Scanner connections are both query paths and metadata boundaries. Source schema discovery may use
the same scanner connection used for reads, but scanner-normalized types must not be represented as
source-native metadata without an explicit mapping owned by the relevant catalog integration.

## Source attachment and read paths

`DuckDbQueryEngine` takes the data sources to make queryable as `SourceAttachment` values when it
starts. An attachment has a name and attaches its source through one read path; the engine knows
nothing else about the source, so its API does not depend on source types. Each source becomes a
DuckDB database named after the data source, and SQL addresses its tables as
`source.schema.table` whichever read path serves it. The application resolves the data sources of
one Physical Catalog catalog and chooses the read path for each of them.

```text
application --resolveConnection--> Physical Catalog
     |
     +-- read path per data source --> SourceAttachment --> DuckDbQueryEngine
                                           |
                                           +-- interim scanner path --ATTACH (READ_ONLY)--> PostgreSQL / MySQL
```

Attachment happens once, before external access is disabled. DuckDB refuses source attachments
afterwards but keeps attached sources queryable, so a running engine sees exactly the sources it
started with. Moving a data source to another read path therefore takes a new engine.
Initialization fails with a typed error that names only the data source if any source cannot be
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

### Interim scanner path

Until a connector serves a source type, PostgreSQL and MySQL sources are read through DuckDB's
packaged scanners. Every source-type-specific decision of this path lives in the
`com.scalar.db.analytics.labs.duckdb.scanner` package, which is removed once connectors replace it:

- `ScannerPath` turns a Physical Catalog `DataSourceConnection` into a read-only scanner
  attachment. Connection settings reach DuckDB as bound session variables; a temporary DuckDB
  secret is created from them and the variables are reset, so no connection value appears in SQL
  text. `duckdb_secrets()` shows the password as redacted.
- The scanners also provide functions that connect to arbitrary connection strings, pass SQL
  through to a source, or reset scanner state, and some of them work even with external access
  disabled. Their function policies deny every `postgres_*` and `mysql_*` function and
  `pg_clear_cache`.
- The scanners cache source schemas, and resetting that cache is not allowed from SQL, so schema
  changes need a new engine. Schema and table listings through `information_schema` or
  `duckdb_tables()` come from the scanner connections and show scanner-normalized types. A MySQL
  source registered without a database exposes every database on the server.
- The scanners push projections, filters, and limits into the SQL that each source receives, which
  the scanner tests assert from the source servers' statement logs. Row, byte, time, and memory
  limits and cancellation apply as to any other query, but DuckDB cannot interrupt a scanner while
  it waits for the source, so a time limit or cancellation takes effect once the source returns
  data.

The scanner path does not yet derive table metadata from its own connections, and the Physical
Catalog still resolves source schemas when a data source is registered.

**[Planned]** The scanner path is to keep the rule that one party interprets each source: the
scanner both reads rows and, over the same connection, produces table metadata in the shape the
connector contract defines. A source belongs to exactly one path at a time, and only DuckDB reads
through the scanner path. The path is held to the connector path's results:

- A column the scanner path types must have the same Analytics type and values as on the connector
  path, using only value-preserving conversions; MySQL `INT UNSIGNED` arrives as `UINTEGER` and can
  be widened to `BIGINT`. Any other column is unsupported, with the same reason a connector gives.
- Scanner settings that change types or values, such as reading MySQL `TINYINT(1)` as boolean or
  the MySQL session time zone, are fixed as part of the path.
- Values are verified against the connector conformance fixtures by a separate test runner.
- Its retrieval information names the scanner path implementation and the DuckDB extension and
  version, with no ADBC driver. It returns no read provenance and performs no schema check; DuckDB's
  type comparison stands in for the latter.

**[Planned]** A source moves to the connector path once its connector passes the metadata and
data-read conformance tests, covers at least the scanner path's columns, and returns at least the
same items, such as constraints and source-side identifiers. Switching then changes only the
retrieval information, turns some unsupported columns into typed ones, starts read provenance and
the schema check, and leaves every typed column's type and values unchanged. The application
switches one source as one administrative operation:

1. Retrieve the table metadata of every registered table of the source through the connector. If
   this fails, nothing switches.
2. Stop accepting new queries on the source and wait for running ones.
3. Store the new table metadata in the Physical Catalog and switch reading to the connector. If this
   fails, restore the previous metadata and scanner reading.
4. Resume accepting queries.

The changed retrieval information makes a new version, which reaches the Semantic Catalog like any
other retrieval. PostgreSQL and MySQL start on the scanner path with DuckDB's official extensions.
**[Planned]** SQLite, Parquet, Iceberg, and Delta Lake are to start there too; Oracle has no such
extension and is connector-only.

## Extension model

Product query behavior integrates with DuckDB through loadable extensions. The `duckdb` build
produces a product-owned C++ extension against the same DuckDB release used by the Java binding,
packages it with the module, and loads it when the Query Engine starts. Keeping that boundary allows
the distribution to use DuckDB's published scanner extensions and to add product behavior without
fixing every extension into the application executable. The distribution owns inclusion of the
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
