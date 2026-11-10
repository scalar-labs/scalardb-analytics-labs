# Architecture

ScalarDB Analytics is an in-process analytics application assembled from independently owned Java,
C++, and Rust build units. It presents one application lifecycle and machine-readable CLI while
preserving the native build systems and extension boundaries required by its components.

The Query Agent is an external consumer. It invokes the product through the CLI and an Agent Skill;
model execution, planning, and SQL generation do not belong to this repository.

## Components and ownership

| Component | Ownership |
| --- | --- |
| Application | CLI contracts, use-case coordination, process lifecycle, composition, and distribution |
| Semantic Catalog | Semantic state, evidence, decisions, and construction of query-facing catalog context |
| Physical Catalog | Physical source registration and metadata, through the embedded upstream catalog and its in-process boundary |
| Query Engine | Embedded DuckDB lifecycle, product extensions, SQL execution, and Arrow result streaming |
| Connectors | Source access and ADBC drivers, produced by the separate connector repository |

The application is the composition root, not the owner of component domain logic. Dependencies
point from application use cases toward component APIs. JDBC, native-library, DuckDB extension, and
connector-specific types remain behind their owning component boundaries.

```text
external Query Agent
        |
        v
application CLI and use cases
        |
        +-- Semantic Catalog
        +-- Physical Catalog
        +-- Query Engine
                |
                +-- product and scanner extensions
                +-- connector artifacts
```

## Query execution flow

The non-interactive query path is designed as a stable machine-to-machine boundary:

1. A caller supplies SQL to the `query` CLI command.
2. The application service obtains the process-owned Query Engine and executes the request.
3. The Query Engine returns the result as an Arrow stream without introducing another value model.
4. The CLI materializes a bounded JSON success envelope, or emits a typed error envelope, with a
   corresponding exit code.
5. Application shutdown closes the embedded engine and its resources.

This path keeps presentation and process concerns in `application` while keeping execution details
inside the Query Engine. See [`query-engine.md`](query-engine.md) for that component's mental model.

## Catalog access

The Physical Catalog runs inside the application process and keeps its state in a ScalarDB store.
The application calls it directly to register and resolve data sources. DuckDB reaches it only
through a read-only provider that the application registers into the product extension, so catalog
administration and source credentials stay out of SQL. `ApplicationRuntime` composes the two:
it starts the catalog before the Query Engine and stops it after the engine. It resolves the
registered data sources of one catalog, chooses a read path for each, and hands them to the engine,
so SQL addresses sources only by their registered names. Until connectors are available, every
source uses the interim scanner path; see
[`query-engine.md`](query-engine.md#source-attachment-and-read-paths). The `query` CLI command does
not use that composition yet, because the CLI has no catalog store configuration. See
[`physical-catalog.md`](physical-catalog.md) for the boundary and its credential handling.

The Semantic Catalog also runs inside the application process. It keeps its records in its own
schema of the Physical Catalog's store database, accessed directly rather than through ScalarDB,
and that storage is equally unreachable from SQL. The two catalogs never call each other: the
application passes each retrieved table's metadata to both, and gives the Semantic Catalog SQL
execution for data checks and name resolution for its references to Physical Catalog tables. See
[`semantic-catalog.md`](semantic-catalog.md) for its model of recorded knowledge and how that
knowledge is built and read.

## Build and runtime boundaries

Root Gradle is the repository-wide entry point. It builds Java modules directly and delegates C++
and Rust work to the build system that owns each native artifact. Aggregate lifecycle tasks connect
those build units through explicit task dependencies, inputs, and outputs; they do not reproduce
CMake, DuckDB extension, or Cargo configuration.

The resulting product is a single process and one user-facing executable entry point. The
distribution may also contain shared libraries and loadable extensions. This separation preserves
DuckDB's extension model while allowing the application to own startup, shutdown, and artifact
compatibility.

The rationale for these boundaries is recorded in:

- [`design/gradle-build-orchestration.md`](design/gradle-build-orchestration.md)
- [`design/duckdb-distribution.md`](design/duckdb-distribution.md)
- [`design/physical-catalog-hosting.md`](design/physical-catalog-hosting.md)
- [`design/physical-catalog-source-boundary.md`](design/physical-catalog-source-boundary.md)
