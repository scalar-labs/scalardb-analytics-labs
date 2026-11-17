# Application

The application is what a user starts. It composes the Physical Catalog, the Semantic Catalog, the
Query Engine, and connectors into one product in one user process, without a separate catalog
process or product server. **[Planned]** It also needs no external JVM; the current distribution
needs one. The catalogs keep their state in a PostgreSQL database that the user organization
provides, called the store. It owns the lifecycle, configuration, user-facing interfaces, and
distribution of that product, and none of the components' domain logic. Module-level orientation and
the current commands are in [`../application/README.md`](../application/README.md).

## Responsibilities

The application provides:

- the entry point that starts the product from the distributed executable;
- loading of the workspace configuration, credential references, and storage locations;
- **[Planned]** authentication of every caller, registration of the Query Agents an administrator
  permits, and the authority checks that let a semantics owner confirm knowledge;
- component initialization, dependency wiring, startup order, and shutdown;
- the user-facing interfaces for data-source registration, catalog access, Semantic Catalog
  construction and decisions, and query execution;
- the retrieval of table metadata and its hand-off to both catalogs, including re-retrieval;
- the capabilities the Semantic Catalog needs from outside it: SQL execution for data checks,
  and **[Planned]** name resolution and re-retrieval;
- the operation that runs Semantic Catalog build tasks and reports their results and failures, and
  the operations that expose their state;
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

**[Planned]** `workspace init` also creates the workspace's one Semantic Catalog domain, named after
the workspace's catalog, and records its initial user policy version, `auto`, with the initializing
person as setter. Every call to the Semantic Catalog names that domain, and the interfaces fill it
in from the workspace. How workspace, catalog, and domain relate beyond this one-to-one
correspondence is not settled.

The workspace configuration holds what the Semantic Catalog needs from the application, per domain.
Today it holds the sample size and the time limit of construction-run SQL, the language-model
adapter and the concrete model it uses (see
[`semantic-catalog/generators.md`](semantic-catalog/generators.md#reaching-a-language-model)), and
whether count statistics (on by default) and value samples (off by default) reach the model (see
[`semantic-catalog/generators.md`](semantic-catalog/generators.md#input-passed-to-external-models)),
and the languages the domain's readers use and which of them is primary, the language the domain
records in, which cannot change once the domain exists, English alone by default (see
[`semantic-catalog/languages.md`](semantic-catalog/languages.md#declared-languages)), under
`semanticCatalog` in `workspace.json` (see
[`../application/README.md`](../application/README.md#standalone-cli)). **[Planned]** It is also to
hold:

- the semantics owners, and the Query Agents an administrator permits (see
  [Callers and authority](#callers-and-authority));
- whether value distributions (off by default) and usage feedback (on by default) reach models;
- whether data checks run (on by default).

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
| Construct, review, and correct the Semantic Catalog | Semantics owner | CLI; **[Planned]** Web UI |
| Query with semantic knowledge | Query Agent | CLI and Agent Skill |

The product runs in one of two launch profiles. Both use the same components, application services,
and persistence formats.

- **Standalone** is the canonical form. A one-shot CLI operation, or an SQL shell session, starts
  the runtime, opens the workspace, and runs every component in the process. A one-shot operation
  waits until it completes, reaches a persisted state awaiting a review decision, or fails
  explicitly, and then stops the runtime and exits. Construction state persists in the workspace, so
  a later invocation can review, decide, and resume without a long-running process. The one
  exception is the watching mode of running build tasks below, which runs until interrupted.
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
second execution contract. The application passes the Query Engine's parser to the Semantic
Catalog, which parses expression values such as a Measure's aggregation rule with it, without
binding or running them. Non-interactive SQL takes one statement from an argument, a file, or
standard input.

**[Planned]** The interactive shell adds editing, history, completion, catalog discovery,
cancellation, timing, `EXPLAIN`, result formats, explicit limits and truncation, error positions,
and a way to trace the Catalog Snapshot and Facts used.

For the Semantic Catalog, the CLI exposes registration, exploration, build tasks and their status,
candidate review, decisions, corrections, and feedback as automatable, machine-readable operations,
one command per read or operation of the Semantic Catalog's public API (see
[`../application/README.md`](../application/README.md#standalone-cli)). **[Planned]** The Web UI
lets a person compare candidates, validation results, conflicts, applicability, and impact before
deciding. It owns no state transitions or decision rules of its own.

Running Semantic Catalog build tasks (`semantic build`, see
[`../application/README.md`](../application/README.md#standalone-cli)) has two modes:

- By default, it runs the domain's created build tasks, including those created while it runs, until
  none remain, and exits. Scripts and CI use this mode.
- A watching mode runs with no timeout until interrupted, picking up build tasks as answers and
  observations from other processes create them. An agent reviewing with the semantics owner keeps
  one running in the background. The first interrupt stops it after the current build task; a
  second stops it at once, and the task left `running` returns to `created` when the next run
  starts.

Both print one event line per finished build task, with its result and the number of items awaiting
review. Only one process runs a domain's build tasks at a time; a second attempt fails at once with
a product error saying so, while answers, reads, and other operations proceed (see
[`semantic-catalog/construction.md`](semantic-catalog/construction.md#build-tasks)). The workspace
configuration's language model is the policy evaluation model the tasks call; without one, the run
fails before it starts. **[Planned]** A watching run outlives changes that other processes write to
the Physical Catalog, so each build task it runs uses the name resolution and table metadata current
when that task starts. Today construction-run SQL starts its Query Engine on first use, with the data
sources registered at that point, and keeps it for the rest of the run.

## Callers and authority

**[Planned]** This section describes design that is not implemented yet, except its last
paragraph, which says what holds today.

The application authenticates every caller and passes the authenticated principal to the components;
no component accepts a caller's word for who it is. A principal is a person or a Query Agent. Query
Agents are not assumed to be arbitrary programs: an administrator configures and permits each one
and gives it a principal of its own.

Only a semantics owner can confirm Semantic Catalog knowledge; how an owner hands judgements to a
delegate is not designed yet. When a person submits answers or resolves a conflict, the application
checks that the authenticated principal is a semantics owner of the domain and issues the
authority-check reference the Semantic Catalog requires (see
[`semantic-catalog/construction.md`](semantic-catalog/construction.md#review)). A Query Agent's
principal is never a semantics owner. An agent can present candidates, gather evidence, register
feedback, and prepare answers, but the confirmation itself is a call the person makes as themselves.
Without that, a Query Agent using the same CLI could name the owner and consent on its own, and the
separation between producing and confirming knowledge would be nominal.

The authentication mechanism, including identity sources, login, agent credentials, and how a
person approves an answer an agent prepared, is not designed yet. Until it is, the person running
the CLI is taken to be the authenticated person, and an agent calls with the name an administrator
gave it in the workspace configuration. The recorded principal has the same form either way, so
records made before real authentication stay valid after it. Until then, though, an agent is known
as an agent only because it says so: one that calls without its name is taken for the person, so
the separation between producing and confirming knowledge rests on the agents an administrator
permits and is not enforced.

Today no caller is authenticated and no agent has a name of its own: every call is made as the
person running the CLI, who is recorded as the deciding party of answers and conflict resolutions,
with `unauthenticated-local-user` as the authority-check reference, and as the registrant of
feedback. That an agent never answers for the owner rests on the Agent Skill alone.

## Query Agents

There is no Query-Agent-specific API or protocol. A Query Agent uses the CLI, guided by the Agent
Skill, to explore the Physical Catalog, obtain Semantic Catalog knowledge, execute SQL, and record
feedback. The same CLI serves the semantics owner's decisions.

Machine use of the CLI guarantees:

- versioned, stable JSON or Arrow output;
- separate stdout and stderr, and meaningful exit codes;
- no prompt in non-interactive mode;
- explicit pagination, limits, and truncation;
- references that identify query executions, Fact Revisions, and Catalog Snapshots; and
- no output of credentials, secrets, or unauthorized information.

The application owns, versions, and distributes the Agent Skill with the CLI. It covers operating
the CLI and interpreting what it returns. It also covers the order of knowledge discovery, how to
read Fact states, how to proceed on missing knowledge or a conflict (resolve it on the spot, run
with unconfirmed knowledge only when the user explicitly asks, or stop), and how to report feedback
after use. It does not cover the agent's model, provider, question-specific planning, or
SQL-generation prompt.

Agent Skill v1 covers two workflows:

- **Building and reviewing with the semantics owner.** The agent starts a watching build in the
  background if none is running and reads the review list as items appear, telling new items from
  those it has seen by URN or by the commit each carries. It presents the review material the
  Semantic Catalog built, per table: items likely to follow the suggestion one line each, with
  consent to them accepted together, and items that need judgement one by one. It records each
  presentation, submits the owner's answers, and presents again anything shown but unanswered. It
  is done when nothing awaits review and no build task is created or running. It neither gathers
  its own evidence nor triages; the policy evaluation model did both when it built the material (see
  [`semantic-catalog/construction.md`](semantic-catalog/construction.md#review)).
- **Answering a question.** The agent fixes one Catalog Snapshot for the question and reads along
  the hierarchy, recursively from the root while the catalog is small. On knowledge awaiting
  judgement it has the owner review it on the spot or, only when the user explicitly asks, proceeds
  with the unconfirmed knowledge and says so; on a conflict an owner chooses; on missing knowledge
  it registers usage feedback. It composes SQL only from confirmed knowledge and cites the Fact
  Revisions and Catalog Snapshot it used.

In both, only the owner's answer consents. The agent never answers for the owner, and stops at a
consent when the user is not one (see [Callers and authority](#callers-and-authority)).

Both workflows follow the reader's language. The agent knows the language the user
reads from the conversation, or from a request in whatever words; when it is one of the domain's
declared languages other than the primary one, the agent passes it on every read whose text it
shows the user, and otherwise reads originals (see
[`semantic-catalog/languages.md`](semantic-catalog/languages.md#agents-and-sessions)). The agent shows the translations the Semantic
Catalog returns and never translates knowledge itself, so a reader can always tell a translation
from the wording that was judged, and writes its own explanations in the reader's language. A
translation still pending it reports as not available yet, and shows the original only on request.
What the user writes for the Semantic Catalog to record, such as a correction's wording or usage
feedback, the agent translates into the domain's recording language before submitting it. When
answering a question, it composes SQL from original text and requests translations only for text it
shows the user, such as a definition it cites, so an unreviewed translation never shapes a query.

The first distribution of the Agent Skill for an agent host is a Claude Code plugin in the
distribution. A user starts Claude Code with the plugin directory (`claude --plugin-dir`), and the
plugin bundles Agent Skill v1, the slash commands `setup`, `build`, `ask`, and `status`, and a
wrapper that only locates the distribution's CLI; the CLI itself reads the workspace from the
`SCALARDB_ANALYTICS_WORKSPACE` environment variable when `--workspace` is omitted. Claude Code wakes
an agent whose turn has ended only on a notification, so `build` makes the watching build's events
notifications: it runs the watching build in the background and watches its output with Claude
Code's `Monitor` tool, which passes on a build task event that raises the number of items awaiting
review, a failed task, or an error. The watching build is not itself the `Monitor` command, because
Claude Code stops a monitor at a deadline and re-arms only the watch, which must not stop a build
task. Only when another process already runs the domain's build tasks does the agent read the review
list at an interval instead. The plugin is a way to deliver the Agent Skill, not an agent the product
owns.

## Table metadata retrieval

The Physical Catalog and the Semantic Catalog share one retrieval of table metadata, the record a
connector returns for one table.
[`design/shared-table-metadata-retrieval.md`](design/shared-table-metadata-retrieval.md) records
why. The **retrieval operation** runs as follows:

1. The application decides the target tables, which are limited to tables registered in the Physical
   Catalog. On data-source registration, they are the tables chosen in that registration.
2. **[Planned]** The Physical Catalog opens the source with the registration's connection settings
   and credentials and requests table metadata from the connector.
3. **[Planned]** The Physical Catalog creates and stores versions and returns a change notification
   per table.
4. For every table except those not retrieved, the application passes the whole retrieved record,
   exactly as the connector returned it, with the table's resource identifier, current version, and
   Physical Catalog reference (the data source name, namespace, and table name, by which the
   Semantic Catalog names the table's Dataset), to the Semantic Catalog's observation registration, which observes the source metadata and keeps
   the table metadata in a derived record. Tables whose version did not change are
   passed too; the Semantic Catalog records nothing for a version no newer than one it already has.

Until the Physical Catalog requests table metadata from the connector itself (it still resolves
source schemas through the upstream `schema-resolver` at registration), the application runs steps
2 and 3 in an interim form:

- The application resolves the registration's connection from the Physical Catalog, opens the
  source through the connector with it, and requests the metadata of every table the Physical
  Catalog lists for the source. The Physical Catalog stores nothing from this retrieval.
- The table identifier is built from names: the data source name, the namespace names, and the
  table name joined with dots, each name double-quoted, with inner double quotes doubled, when it is
  empty or contains a dot or a double quote. It identifies a table by name, as a resource
  identifier does.
- The version is the time the connector read the table's catalog, in microseconds since the epoch,
  and a table that does not exist gets the time of the retrieval. Every retrieval therefore makes a
  new version, and an unchanged table adds only a derived record. The content digest is the SHA-256
  of the canonical JSON of the table metadata and the source metadata.
- The source metadata schema passed with a table is the one the connector library's static
  information publishes for the connector that read it, and its digest is the SHA-256 of its
  canonical JSON. The connector API requires every connector to publish one, so a library that
  publishes none for the reading connector fails the retrieval.
- The Semantic Catalog domain is the workspace's catalog name, lowercased, with every run of other
  characters than letters and digits replaced by one hyphen. The first retrieval creates the domain
  when `workspace init` has not.
- PostgreSQL and MySQL sources are retrieved through their connectors, with the connection options
  the connector path attaches them with, so the table metadata has the types queries read. A MySQL
  source reads `tinyint(1)` as `Boolean`. Every source type has a connector.

`source register` runs the retrieval operation after the Physical Catalog registration succeeds and
reports what the Semantic Catalog recorded for each table. If the retrieval fails, the data source
stays registered and the failure says to run `metadata retrieve` for it.

Credentials stay in the Physical Catalog's registration and never reach the Semantic Catalog. If
step 4 fails, the application does not resend a stored copy, because the Physical Catalog stores
only part of the metadata. It runs the retrieval operation again for that table, and the next run
delivers whatever version is then current.

**Re-retrieval** runs the retrieval operation again for registered tables. The application starts it
when:

1. **[Planned]** a query read finds that a column's type differs from the Physical Catalog's, which
   is a "stale registration" error from the connector's schema check;
2. a user or administrator requests it, which `metadata retrieve --source <name>` does for every
   registered table of a data source; or
3. **[Planned]** the Semantic Catalog requests it.

Case 1 starts without waiting for a user, because queries on that table would fail until then. It
touches only the registered tables that triggered it and only brings their versions in line with the
source. What follows in the Semantic Catalog still goes through its user policy. Under the default
policy, for example, a column found to have disappeared without a same-type replacement is retired
by machine judgement, together with the confirmed correspondences and Measures that have it as a
premise. It finds only mismatches in the columns a query reads, so added columns and changed
constraints arrive through cases 2 and 3. The application never reruns failed queries: if Analytics
types changed, the same SQL can mean something else, and the user or Query Agent recomposes it.
Re-retrieval never registers new tables.

## Startup, configuration, and isolation

The application validates configuration and credential references, checks dependency compatibility,
initializes components, publishes interfaces, and shuts down safely, as one lifecycle. It
initializes the connector API library once per process, the first time table metadata retrieval or
a Query Engine with a connector-path source needs it, and shuts it down after the Query Engine has
closed. The engine's connectors extension uses that initialization instead of making its own.
**[Planned]** It hands the same library to the Physical Catalog.

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
- **[Planned]** Semantic Catalog build tasks receive only the inputs given to them.
- A build task that checks data values does not call connectors directly. It uses the
  application's construction-run SQL, an entry point separate from a user's SQL, so every read
  through it is the build task's:
  - The build task declares each table it reads, by data-source registration, namespace, and table
    name, as sampled or whole. A declared table must be registered in the Physical Catalog, and the
    statement must read every declared table and nothing else: its parse tree may reference only
    declared tables and its own common table expressions, and no table function.
  - The application, not the build task, applies sampling: each reference to a sampled table reads
    a reservoir sample of the domain's sample size, with one fixed seed, so every reference to a
    table in a statement reads the same rows. A table within the sample size is read whole. A
    reservoir sample is drawn from a scan of the whole table, so sampling bounds what a check
    holds, not what it reads from the source; each reference scans the table again, and a large
    table is bounded only by the time limit.
  - The statement and the counts of what it read share the domain's time limit, and its result is
    capped at 10,000 rows and 64 MiB. A statement over a limit returns nothing and leaves its items
    undecidable. The engine runs one query at a time, so these queries run one after another.
  - The result says, for each declared table, whether it was read through a sample or whole, how
    many rows were read, and when. The connector path does not return read provenance yet, so it
    carries the name and version of the DuckDB extension that read each data source.
    **[Planned]** It is to carry the connector's read provenance.
  - The Query Engine for these reads is started with every data source of the catalog attached, on
    the first such read, the same way as for a user's query.
- External files, URLs, automatic extension downloads, and arbitrary `ATTACH` are denied by default.

The first distribution target is Linux x86_64 with glibc 2.28 or newer and the baseline x86-64
instruction set.
