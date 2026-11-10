# Application

The application is what a user starts. It composes the Physical Catalog, the Semantic Catalog, the
Query Engine, and connectors into one product in one user process, without requiring an independent
server, an external JVM, or a separate catalog process. It owns the lifecycle, configuration,
user-facing interfaces, and distribution of that product, and none of the components' domain logic.
Module-level orientation and the current commands are in
[`../application/README.md`](../application/README.md).

## Responsibilities

The application provides:

- the entry point that starts the product from the distributed executable;
- loading of the workspace configuration, credential references, and storage locations;
- component initialization, dependency wiring, startup order, and shutdown;
- the user-facing interfaces for data-source registration, catalog access, Semantic Catalog
  construction and decisions, and query execution;
- **[Planned]** the retrieval of table metadata and its hand-off to both catalogs, including
  re-retrieval;
- **[Planned]** the capabilities the Semantic Catalog needs from outside it: SQL execution for data
  checks, name resolution, and re-retrieval;
- **[Planned]** the operations that run Semantic Catalog build tasks and expose their state and
  failures;
- a versioned Agent Skill that teaches a Query Agent to use the CLI safely;
- structured errors and diagnostics; and
- assembly of the executable and distribution.

It does not own:

- domain logic of the Physical Catalog, Semantic Catalog, connectors, or Query Engine;
- source-specific types, dialects, authentication, or connection rules;
- the Query Agent's runtime, model or provider, question-specific planning, or SQL generation. The
  Agent Skill is product usage guidance, not an agent;
- decisions that confirm a Semantic Catalog candidate; or
- separate product components named `native-image` or `distribution`. Those describe how the
  application is built and shipped.

## Workspace

A **workspace** is one logical unit of product state selected at launch. It binds configuration and
credential references, the Physical Catalog's store and registrations, the Semantic Catalog's
records, construction-work state, and local usage information. It is not necessarily one directory;
the application opens each component's persisted content under the same workspace identity.

Changing interfaces opens the same workspace and uses the same Query Engine without migrating
sources or catalogs. Several application processes may open one workspace at the same time, and
every one-shot CLI invocation is its own process. Each component keeps its writes serializable
through transactions in its store, so no process needs to own the workspace exclusively.

## Interfaces

All interfaces share one **application service layer** that connects user operations to component
public APIs. A screen or transport carries no business logic of its own and does not reimplement
use-case orchestration. Whatever the interface, a user observes the same operations, states, and
product errors; the same propagation of caller identity, timeout, and cancellation; the same
protection against duplicate effects on retry; and the same visibility into partial completion and
what is needed to resume.

| Purpose | Consumer | Interface |
| --- | --- | --- |
| Automate SQL | Script, CI, job | Non-interactive CLI |
| Explore SQL interactively | Data engineer, analyst | **[Planned]** Embedded SQL shell |
| Run SQL from existing tools | SQL client, BI, IDE | **[Planned]** Flight SQL |
| Manage data sources and the Physical Catalog | Administrator, data engineer | CLI; **[Planned]** Web UI |
| Construct, review, and correct the Semantic Catalog | Semantics owner | **[Planned]** CLI, Web UI |
| Query with semantic knowledge | Query Agent | CLI and Agent Skill; **[Planned]** semantic knowledge |

The product runs in one of two launch profiles. Both use the same components, application services,
and persistence formats.

- **Standalone** is the canonical form. A one-shot CLI operation, or an SQL shell session, starts
  the runtime, opens the workspace, and runs every component in the process. A one-shot operation
  waits until it completes, reaches a persisted state awaiting a review decision, or fails
  explicitly, and then stops the runtime and exits. Construction state persists in the workspace, so
  a later invocation can review, decide, and resume without a long-running process.
- **[Planned]** **Local workspace** keeps one foreground process with the workspace open and exposes
  REST, gRPC, and Flight SQL on loopback, for the Web UI and external SQL clients. It is not a
  remote or multi-user server.

**[Planned]** Versioned protobuf over gRPC is the programmatic management contract, and the Web UI
uses the same contract through a REST mapping. Flight SQL is the canonical external SQL protocol.
Registration, Semantic Catalog updates, confirmation, and credential access are never exposed as
Flight SQL administrative statements. In standalone, the CLI calls application services in process.
A CLI given an explicit local-workspace endpoint sends management operations to gRPC and SQL to
Flight SQL. A CLI without an endpoint runs standalone; it never discovers a running process or
switches modes implicitly.

SQL interpretation and execution belong to the Query Engine. The product adds no SQL parser or
second execution contract. Non-interactive SQL takes one statement from an argument, a file, or
standard input.

**[Planned]** The interactive shell adds editing, history, completion, catalog discovery,
cancellation, timing, `EXPLAIN`, result formats, explicit limits and truncation, error positions,
and a way to trace the Catalog Snapshot and Facts used.

**[Planned]** For the Semantic Catalog, the CLI exposes registration, exploration, build tasks and
their status, candidate review, decisions, corrections, and feedback as automatable,
machine-readable operations. The Web UI lets a person compare candidates, validation results,
conflicts, applicability, and impact before deciding. It owns no state transitions or decision rules
of its own.

## Query Agents

There is no Query-Agent-specific API or protocol. A Query Agent uses the CLI, guided by the Agent
Skill, to explore the Physical Catalog, obtain Semantic Catalog knowledge, execute SQL, and record
feedback. The same CLI serves the semantics owner's decisions.

Machine use of the CLI guarantees:

- versioned, stable JSON or Arrow output;
- separate stdout and stderr, and meaningful exit codes;
- no prompt in non-interactive mode;
- explicit pagination, limits, and truncation;
- references that identify query executions, and **[Planned]** Fact Revisions and Catalog Snapshots;
  and
- no output of credentials, secrets, or unauthorized information.

The application owns, versions, and distributes the Agent Skill with the CLI. It covers operating
the CLI and interpreting what it returns. **[Planned]** It also covers the order of knowledge
discovery, how to read Fact states, how to proceed on missing knowledge or a conflict (resolve it on
the spot, run with unconfirmed knowledge only when the user explicitly asks, or stop), and how to
report feedback after use. It does not cover the agent's model, provider, question-specific
planning, or SQL-generation prompt.

## Table metadata retrieval

**[Planned]** This section describes design that is not implemented yet. Today, registration
resolves source schemas through the upstream catalog.

The Physical Catalog and the Semantic Catalog share one retrieval of table metadata, the record a
connector returns for one table.
[`design/shared-table-metadata-retrieval.md`](design/shared-table-metadata-retrieval.md) records
why. The **retrieval operation** runs as follows:

1. The application decides the target tables, which are limited to tables registered in the Physical
   Catalog. On data-source registration, they are the tables chosen in that registration.
2. The Physical Catalog opens the source with the registration's connection settings and credentials
   and requests table metadata from the connector.
3. The Physical Catalog creates and stores versions and returns a change notification per table.
4. For every table except those not retrieved, the application passes the whole table metadata,
   exactly as the connector returned it, with the table's resource identifier and current version,
   to the Semantic Catalog's observation registration. Tables whose version did not change are
   passed too; the Semantic Catalog records nothing for a version it already has.

Credentials stay in the Physical Catalog's registration and never reach the Semantic Catalog. If
step 4 fails, the application does not resend a stored copy, because the Physical Catalog stores
only part of the metadata. It runs the retrieval operation again for that table, and the next run
delivers whatever version is then current.

**Re-retrieval** runs the retrieval operation again for registered tables. The application starts it
when:

1. a query read finds that a column's type differs from the Physical Catalog's. On the connector
   path this is a "stale registration" error from the connector's schema check; on the scanner path
   it is a mismatch in DuckDB's type comparison;
2. a user or administrator requests it for a table; or
3. the Semantic Catalog requests it.

Case 1 starts without waiting for a user, because queries on that table would fail until then. It
touches only the registered tables that triggered it and only brings their versions in line with the
source, so it makes no change the user did not intend. It finds only mismatches in the columns a
query reads, so added columns and changed constraints arrive through cases 2 and 3. The application
never reruns failed queries: if Analytics types changed, the same SQL can mean something else, and
the user or Query Agent recomposes it. Re-retrieval never registers new tables.

## Startup, configuration, and isolation

The application validates configuration and credential references, checks dependency compatibility,
initializes components, publishes interfaces, and shuts down safely, as one lifecycle. **[Planned]**
It initializes the connector API library once per process, hands it to the Query Engine adapter and
the Physical Catalog, and finalizes it at shutdown.

The application unifies configuration but does not interpret source-specific provider payloads;
connectors or the Physical Catalog codec validate and interpret them. Credentials never appear in
listings, query results, plans, ordinary logs, or errors, and no API returns a registered
credential.

One process does not mean one set of privileges or connections:

- SQL from a user or a model runs on a restricted Query Engine connection that can only read
  registered data.
- **[Planned]** Semantic Catalog storage, administrative table functions, and catalog-changing
  operations sit behind connections or capabilities that untrusted SQL cannot reach.
- **[Planned]** The databases that store the Physical Catalog and the Semantic Catalog cannot be
  registered as data sources, so SQL cannot reach them through the Query Engine.
- **[Planned]** Semantic Catalog build tasks receive only the inputs given to them. A build task
  that checks data values does not call connectors directly. It uses the application's SQL
  execution, which identifies the build task as the caller, limits what it may read to the inputs
  the task declared, and caps rows, bytes, and time. The result carries the connector's read
  provenance, or, on the scanner path, the path's implementation and the names and versions of its
  DuckDB extensions instead.
- External files, URLs, automatic extension downloads, and arbitrary `ATTACH` are denied by default.

The first distribution target is Linux x86_64 with glibc 2.28 or newer and the baseline x86-64
instruction set.
