# Query Engine

The Query Engine embeds DuckDB in the application process and presents SQL execution as a
product-owned capability. Its public data-plane contract is an Apache Arrow stream. The boundary
keeps JDBC and DuckDB-specific types out of application use cases while preserving Arrow schemas,
types, and batches across engine integrations.

## Boundary and lifecycle

`application` depends on the `QueryEngine` and `QueryResult` interfaces in the `query-engine`
module. `QueryResult` exposes an `ArrowReader` rather than a second product-specific schema and
value model. The `duckdb` module owns the concrete engine, its connection, Arrow allocator, and
JDBC resources. JDBC and DuckDB-specific types do not cross the public boundary.

An engine instance owns its embedded connection for the application-service lifetime. Query
results stream in bounded Arrow record batches. A result owns its Arrow reader and the underlying
JDBC resources, so callers must close it before closing the engine. Statements that do not return
a result are rejected by this query-only contract.

## Execution and trust boundary

The application passes SQL into the engine and receives the Arrow schema and record batches. The
CLI is an adapter over that contract: it materializes at most 1,000 rows into a versioned JSON
envelope and reports whether output was truncated. JSON conversion rules, such as Base64 for
binary values and strings for decimal values, are CLI concerns and are not part of the Query Engine
contract.

Query failures use a sealed `QueryError` hierarchy whose variants are records. This keeps the
engine error contract closed and lets each application adapter exhaustively encode it. The CLI maps
those variants to stable uppercase error codes in its versioned JSON envelope; exceptions only
carry the execution ID, typed error, and optional cause.

Runtime extension installation and automatic loading are disabled. Required product, scanner, and
connector artifacts are version-aligned parts of the distribution rather than network-fetched
runtime dependencies. Catalog administration is explicit: loading an extension or attaching a
source does not implicitly create product catalog state.

Scanner connections are both query paths and metadata boundaries. Source schema discovery may use
the same scanner connection used for reads, but scanner-normalized types must not be represented as
source-native metadata without an explicit mapping owned by the relevant catalog integration.

## Extension model

Product query behavior integrates with DuckDB through loadable extensions. The `duckdb` build
produces a product-owned C++ extension against the same DuckDB release used by the Java binding,
packages it with the module, and loads it when the Query Engine starts. Keeping that boundary allows
the distribution to use DuckDB's published scanner extensions and to add product behavior without
fixing every extension into the application executable. The distribution owns inclusion of the
verified artifacts, and the Query Engine owns loading and using them.

See [`design/duckdb-distribution.md`](design/duckdb-distribution.md) for the packaging decision and
its rationale.
