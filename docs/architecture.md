# Architecture

ScalarDB Analytics lets people and AI agents discover, understand, combine, and query data across
heterogeneous sources without first moving the data. It is an in-process analytics application
assembled from independently owned Java, C++, and Rust build units. It presents one application
lifecycle and machine-readable CLI while preserving the native build systems and extension
boundaries required by its components.

Terms shared across these documents are defined in [`glossary.md`](glossary.md).

## The problem and the cycle

A Query Engine alone cannot tell business meaning beyond table names and column types, and a catalog
of meaning cannot turn confirmed meaning into a query over actual data by itself. Queries composed
without knowing what a row represents, which identifiers denote the same object, or whether a join
multiplies rows run normally and return wrong answers. The product places a Semantic Catalog beside
the Physical Catalog and the Query Engine and closes the following cycle within one startup unit:

```text
data sources
    |
    +-- table metadata --+--> Physical Catalog
    |                    |
    |                    +--> Semantic Catalog construction <-- data values via the Query Engine
    |                                   |
    |                                   +--> Catalog Snapshot
    |                                              |
question --> Query Agent --------------------------+
    |                                              v
    +---------------------- SQL ---------------> Query Engine --> result

missing knowledge, conflicts, and corrections
    +----------------------------------------------> Semantic Catalog construction
```

An organization constructs knowledge about the data sources it registered, runs queries composed
with that knowledge in the same product, and returns the missing knowledge, conflicts, and
corrections found from the results to the next construction. Repeating the cycle accumulates the
knowledge that actual questions need, and later questions reuse it.

The **Query Agent** is an external consumer: an AI agent that interprets a user's question, finds
the knowledge it needs, and composes a query. Its model, planning, and SQL generation do not belong
to this repository. A person or any Query Agent uses the same catalogs and Query Engine through the
application's interfaces.

The product first works within one **domain**, the unit of management that assigns one responsible
party to a body of data and its meaning. The same mechanism is meant to extend to several domains
without moving data or meaning to a center: the authority to decide the meaning of a domain's data
stays with that domain, and other domains read its knowledge only as far as they are permitted.
Cross-domain references are therefore read-only, and no domain can change another's meaning.

The cycle needs three roles. The Semantic Catalog manages knowledge about the meaning of data.
Governance evaluates whether use of data and catalog information is permitted and enforces the
result. The Query Engine runs queries against the registered sources. These are roles, not modules,
and the Semantic Catalog decides only whether knowledge is semantically presentable, never whether a
using party may access it.

## Components and ownership

| Component | Ownership |
| --- | --- |
| Application | CLI contracts, use-case coordination, process lifecycle, composition, and distribution; see [`application.md`](application.md) |
| Semantic Catalog | **[Planned]** Semantic state, evidence, decisions, and construction of query-facing catalog context; see [`semantic-catalog.md`](semantic-catalog.md) |
| Physical Catalog | Data-source registration, the physical schemas and Analytics types used for planning and execution, and name resolution, through the embedded upstream catalog; see [`physical-catalog.md`](physical-catalog.md) |
| Query Engine | Embedded DuckDB lifecycle, product extensions, SQL execution, and Arrow result streaming; see [`query-engine.md`](query-engine.md) |
| Connectors | Metadata retrieval, type mapping, and data read for one source kind, produced by the separate connector repository and carried as its distribution by the `connector` module; see [`../connector/README.md`](../connector/README.md) |

The application is the composition root, not the owner of component domain logic. Dependencies point
from application use cases toward component APIs. JDBC, native-library, DuckDB extension, and
connector-specific types remain behind their owning component boundaries.

```text
external Query Agent
        |
        v
application CLI and use cases
        |
        +-- Semantic Catalog ----> its schema in the catalog store
        +-- Physical Catalog ----> upstream scalardb-analytics (submodule)
        |                    +---> connector API
        +-- Query Engine
                |
                +-- product and scanner extensions
                +-- connector API
```

Some rules hold across components:

- The Physical Catalog owns only the physical schemas and Analytics types used for planning and
  execution, and never confirms business meaning.
- The Semantic Catalog owns semantic state and decision material. It neither authorizes using
  parties nor executes SQL. It takes the material for meaning from the table metadata a connector
  returns and from data values, not from what the Physical Catalog stores.
- A Query Engine contains no source-specific type interpretation or authentication.
- **[Planned]** One connector performs both metadata retrieval and data read for a source, so
  registration-time types and execution-time values come from one interpreting party. The
  responsibilities of connectors, Query Engine adapters, and the type conversion library are defined
  in the [connector
  architecture](https://github.com/scalar-labs/scalardb-analytics-connectors/blob/main/docs/architecture.md).
- Candidates, confirmed knowledge, physical schemas, and query results stay distinct across every
  boundary.
- SQL produced by a user or a model never runs on a connection that can reach Semantic Catalog
  storage or administrative operations.

## Representative flows

**Registering a data source.** A user registers a source and its connection settings through the
application. Registration is an explicit administrative operation. Starting the application or
attaching a catalog never creates catalogs or data sources implicitly.

**[Planned]** The Physical Catalog retrieves the table metadata of the registered tables (names,
columns, source types, Analytics types, and constraints) and persists, with a version, the parts
used for planning and execution. It does not persist declared constraints. The application passes
the connector's source metadata from the same retrieval to the Semantic Catalog, which records it as
its input.

**[Planned]** **Constructing knowledge.** Registration, an input change, or usage feedback from a
user's LLM session, such as missing knowledge met while composing a query, becomes input to Semantic
Catalog build tasks. Generators produce candidates with validation results, and the user policy
routes each to machine judgement or the deciding party's consent. Confirmed knowledge appears in
Catalog Snapshots at later commits. The authority to produce candidates is separate from the
authority to confirm them: an answer or conflict resolution is accepted only with a reference to an
authority check made outside the Semantic Catalog. The application authenticates every caller, and a
Query Agent calls as a principal an administrator registered for it, which can never confirm. No new
input changes a past Catalog Snapshot.

**Querying with knowledge.** **[Planned]** The Query Agent or a person reads the knowledge a
question needs from one Catalog Snapshot and composes SQL.

The non-interactive query path is a stable machine-to-machine
boundary:

1. A caller supplies SQL to the `query` CLI command.
2. The application opens the workspace's runtime and starts the Query Engine with the catalog's data
   sources attached.
3. The Query Engine resolves names through the Physical Catalog, reads data, and returns the result
   as an Arrow stream without introducing another value model.
4. The CLI materializes a bounded JSON success envelope, or emits a typed error envelope, with a
   corresponding exit code.
5. Application shutdown closes the engine and then the catalog.

**[Planned]** Missing knowledge and feedback from the answer return to construction as usage
feedback, within what the organization permits. Use alone is never evidence that a Fact is correct.

## Tracing use

**[Planned]** This section describes design that is not implemented yet.

The product always provides the means to trace application use, and the user organization chooses
what is acquired. The product records the correspondence between an answer and the versions of
knowledge it used, what each review presented and how the judgement proceeded, and the sequence of
use for one question: its text, the knowledge searched for and used, any missing knowledge or
conflict met, the query and its execution, and the user's acceptance or correction. These records
let quality be evaluated after the fact.

For a Query Agent, only what that agent reports is recorded. Tracing is not how use reaches
construction: what a session finds worth keeping is registered with the Semantic Catalog as usage
feedback, and the Semantic Catalog neither depends on trace records nor receives them. An
organization can therefore stop acquiring trace records, losing after-the-fact evaluation, and
separately stop passing usage feedback to Generators, losing construction from use.
[`semantic-catalog.md`](semantic-catalog.md) explains which records the Semantic Catalog holds.

## Catalog access

The Physical Catalog runs inside the application process and keeps its state in a ScalarDB store.
The application calls it directly to register and resolve data sources. DuckDB reaches it only
through a read-only provider that the application registers into the product extension, so catalog
administration and source credentials stay out of SQL. `ApplicationRuntime` composes the two: it
starts the catalog before the Query Engine and stops it after the engine. It resolves the registered
data sources of one catalog, chooses a read path for each, and hands them to the engine, so SQL
addresses sources only by their registered names. Until connectors are available, every source uses
the interim scanner path; see [`query-engine.md`](query-engine.md#source-attachment-and-read-paths).
See [`physical-catalog.md`](physical-catalog.md) for the boundary and its credential handling.

**[Planned]** The Semantic Catalog also runs inside the application process. It keeps its records in
its own schema of the Physical Catalog's store database, accessed directly rather than through
ScalarDB, and that storage is equally unreachable from SQL. The two catalogs never call each other.
The application retrieves each table's metadata once and passes it to both, and it gives the
Semantic Catalog SQL execution for data checks and name resolution for its references to Physical
Catalog tables. See [`semantic-catalog.md`](semantic-catalog.md) for its model of recorded knowledge
and how that knowledge is built and read.

## Repositories and build units

Product code is divided among four repositories:

| Repository | Responsibility |
| --- | --- |
| `scalardb-analytics` | The existing public product: Analytics, the Physical Catalog implementation, and the Spark integration. Pinned here as the `upstream/scalardb-analytics` submodule |
| `scalardb-analytics-enterprise` | Enterprise-specific features of the existing product |
| `scalardb-analytics-connectors` | The connector contract, connector API and SDK, per-source connectors, our own ADBC drivers, and conformance tests, in Rust. Its [`docs/`](https://github.com/scalar-labs/scalardb-analytics-connectors/blob/main/docs/architecture.md) are authoritative for connectors and Query Engine pushdown |
| `scalardb-analytics-labs` | This repository: the new product's integration of the Physical Catalog, Semantic Catalog, Query Engine, and application |

This repository is where the new product is implemented, not a place for disposable demonstrations.
It consumes the existing implementation through the submodule and versioned connector artifacts, so
the existing product lines and connector releases keep their own cycles.

Module boundaries follow responsibilities and change cycles rather than the fact that users start
one application. The modules live in one repository so that a change can cross them atomically while
the product boundary is being validated, and they stay separate build units so that ordinary changes
do not rebuild unrelated native parts.

| Module | Language and build | Changes that trigger it |
| --- | --- | --- |
| `application` | Java, Gradle | CLI, Agent Skill, composition, distribution, and product release |
| `physical-catalog` | Java, Gradle, with the upstream build included | Submodule updates and the product boundary around the catalog |
| `connector` | Java, Gradle, carrying the connectors distribution | Connectors distribution updates, the Java binding of its library |
| `semantic-catalog` | **[Planned]** Java, Gradle | Domain model, construction, and model-assisted processes |
| `query-engine` and `duckdb` | Java and a C++ DuckDB extension | DuckDB version, extensions, and engine integration |

DuckDB is the first embedded Query Engine. Spark remains in `scalardb-analytics` for the existing
product line, and no empty `spark` module is created here until a labs-specific Spark integration is
needed. In this composition Rust is confined to connectors; adopting another engine would reopen
that and the single-process composition.

## Build and runtime boundaries

Root Gradle is the repository-wide entry point. It builds Java modules directly and delegates C++
and Rust work to the build system that owns each native artifact. Aggregate lifecycle tasks connect
those build units through explicit task dependencies, inputs, and outputs; they do not reproduce
CMake, DuckDB extension, or Cargo configuration.

The resulting product is a single process and one user-facing executable entry point, started
without a separate product service. The catalogs keep their state in a PostgreSQL database that the
user organization provides. **[Planned]** It also starts without an external JVM; the current
distribution needs one. The distribution may also contain shared libraries and loadable extensions.
This separation preserves DuckDB's extension model while allowing the application to own startup,
shutdown, and artifact compatibility.

The rationale for these boundaries is recorded in:

- [`design/gradle-build-orchestration.md`](design/gradle-build-orchestration.md)
- [`design/duckdb-distribution.md`](design/duckdb-distribution.md)
- [`design/physical-catalog-hosting.md`](design/physical-catalog-hosting.md)
- [`design/physical-catalog-source-boundary.md`](design/physical-catalog-source-boundary.md)
- [`design/shared-table-metadata-retrieval.md`](design/shared-table-metadata-retrieval.md)
