# Semantic Catalog storage

**[Planned]** This document describes accepted design that is not implemented yet.

This document explains where the Semantic Catalog keeps its records, how one domain's writes stay
serializable, and how the log, the materialized records, and the records kept outside the log are
laid out. It covers how the records of [`record-model.md`](record-model.md),
[`catalog-snapshot.md`](catalog-snapshot.md), and [`construction.md`](construction.md) are realized
on a database. Readers are assumed to know PostgreSQL isolation levels, unique constraints, and
`jsonb`.

## The store

The Semantic Catalog keeps its records in its own `semantic_catalog` schema in the Physical
Catalog's PostgreSQL database, called the store. It accesses that schema directly through Spring
Data JDBC and JDBC, not through ScalarDB. The two catalogs share the database but neither reads the
other's schema. [`../design/semantic-catalog-store.md`](../design/semantic-catalog-store.md) records
why.

Storage stays behind an adapter inside the module. Neither the domain model nor the public API makes
tables or SQL part of its contract. The store's connection settings come from the application's
workspace configuration and are held only by the two catalogs, never passed to the Query Engine. The
application refuses to register the store's database as a data source, so SQL written by users or
models cannot reach the log through DuckDB.

The store must be PostgreSQL. The Semantic Catalog relies on `REPEATABLE READ` snapshots and
`jsonb`, and fails at startup with the reason when the store is anything else.

## Writes and reads

One domain's writes produce the same result as if they ran one at a time, without limiting how many
processes write.
[`../design/semantic-catalog-write-serialization.md`](../design/semantic-catalog-write-serialization.md)
records how this was chosen. An operation that writes to the log runs in one `REPEATABLE READ`
transaction:

1. It reads the materialized records to check acceptance, match identities, check consistency,
   evaluate the policy, and compute cascades. The snapshot contains every commit committed before
   the transaction began.
2. It inserts a `commits` row numbered one past the last commit in its snapshot, writes the log
   records, and updates the materialized records.
3. It writes the records kept outside the log that the same operation produces.
4. It commits.

Two overlapping writers to one domain try to insert the same commit number, and the later one fails
on the primary key of `commits`. It then starts again from step 1 in a new transaction, so only
writes judged on top of the last committed commit ever commit. Judgements use only what the
transaction read from the store, never state cached in the process, so a retry carries no stale
assumptions.

Operations that write no commit detect collisions on the rows they write. These are a
derived-record-only observation, a review presentation, a resume, a translation request, and a
build task finishing without candidates or with translations. Build task and review presentation numbers are also one past the last number in the
snapshot and are unique by primary key. State changes and added triggers on a build task update the
same `build_tasks` row, so overlapping ones also collide and retry. Registering table metadata,
whether it records an observation or only a derived record, updates the latest received version on
the table's `input_resources` row. A derived-record-only registration judges against the latest
observation of that table, so this update makes it collide with any concurrent registration for the
same table, and the retry judges against the observation that won.

A process running a domain's build tasks first takes the domain's **builder semaphore**, a
PostgreSQL session-level advisory lock held on a connection of its own for the whole run. Its key
is an integer the `domains` row issues, so no two domains share one. The semaphore has to live
exactly as long as the running process, because the reset below treats every task left `running` as
abandoned. The server releases a session-level lock when its connection ends, so a stopped process
never leaves it behind, and holding it keeps no transaction open. A second process that cannot take
it is refused at once, while answers, reads, and other writes proceed. A lock file would exclude
only processes on one host, while the store may be reached from several; a runner row with a lease
outlives a stopped process until the lease expires; and a row lock needs a transaction open for the
whole run, which holds back vacuum in watching mode.

Running a build task never holds a transaction while a model or a construction query runs:

1. **Claim.** Change the task from `created` to `running` and commit. Of two processes claiming one
   task, the later fails on the row update. Adding a trigger updates the same row, so a claimed task
   takes no more triggers.
2. **Run.** Run the Generator, the model-based duplicate check, the impact judgement, or the policy
   evaluation model outside any transaction. A model call takes seconds to tens of seconds, and an
   open transaction would collide with answers committed meanwhile and force the whole run to
   restart. A deterministic Generator that reads only the store, such as schema interpretation,
   finishes in milliseconds and runs inside the transaction of step 3 instead, so it reads the
   state it is recorded on and needs no reconciliation; a collision runs it again. A data check
   splits its run: it plans its queries in a read of the latest commit, the queries run outside any
   transaction, and it interprets the reads inside the transaction of step 3, so a collision keeps
   the reads and runs no query again.
3. **Record.** Record the output by the write procedure above, set the task to `succeeded` or
   `failed`, and commit. On a `commits` collision only this step is retried, keeping the run's
   output, or for a data check the reads it interprets.

The output was produced from the state at the start of step 2, so step 3 reconciles it with the
current state inside the recording transaction:

- It replaces temporary numbers with issued URNs, appending a sequence number to a slug or another
  name-based identifier already taken. A Fact whose value is one record, such as the Concept a row
  represents, may be proposed without a value and with an identity key of that one reference; its
  value is then the URN the reference resolves to, so a Generator can refer to a Concept it creates
  in the same output. A Measure's default time granularity may likewise be proposed without a value
  and with the identity key of its Dimension and level; its value is then written with the
  Dimension's issued URN.
- It matches edges, empty-scope Fact Addresses, and closed-range Facts, including those just written
  by concurrent Generators.
- It finds Datasets and Fields by Physical Catalog reference. Unique constraints cannot express this
  identity, so a search inside the transaction does the matching, and a concurrent writer that
  created the element first is found on retry.
- It decides whether each data observation equals its predecessor; the data check does this as it
  interprets its reads, and a data-observation Input Resource is matched by its check and target.
- It runs the consistency check, which fails the task and writes nothing when it finds a dependency
  cycle or a missing reference.

If the process stops during step 2, the task stays `running` until the next process that runs the
domain's build tasks returns it to `created`.

A read runs in one `REPEATABLE READ` transaction, so its result does not change if a commit lands
during it, and it neither blocks nor collides with writes. "Latest" means the last commit visible to
that transaction. A read at a point searches the materialized records by commit range and never
replays the log.

## Identifiers

URNs of elements, Fact Addresses, and Facts are built from names, as
[`record-model.md`](record-model.md#identity-and-urns) explains; the others come from the position
of the log record that created them:

| Record | Identifier |
| --- | --- |
| Element, Fact Address, Fact | Name-based, with a sequence number appended when the identifier is taken |
| Input Observation | Commit number and order within the commit of the creating record |
| Input Resource | Position of the record that first observed it |
| Fact Revision | Fact URN plus the commit number and order of the producing Fact Event |
| Catalog Snapshot | Commit number |
| Build task, review presentation record | A number increasing within the domain |

Log positions are unique, never reused, and reproduced exactly when the log is replayed from empty,
as long as log records are never deleted and the domain is never recreated. A name-based URN is
issued once, inside the serialized write, with sequence numbers for taken identifiers assigned in
log order, and is kept in the log record that created it. Replay reads these URNs from the log
instead of building them again, so a later change to how new URNs are built leaves earlier ones as
they were. Generator output refers to records not yet written by temporary numbers.

## Tables

Every table has the domain as the first column of its primary key. Record content is a `jsonb`
column whose shape the module's types define. Only what searches and unique constraints need is
promoted to columns.

The log:

| Table | Primary key | Holds |
| --- | --- | --- |
| `domains` | Domain | Creation time, recording language, builder semaphore key |
| `commits` | Domain, commit number | Time, kind of operation, author (the person or agent behind the call) |
| `log_records` | Domain, commit number, order | Record kind, target Fact, target Input Resource, target Fact Address of a conflict resolution, content |

`log_records` is append-only. Fact Events, conflict resolutions, Observation Events, Input
Observations, and policy versions all go there. Its target Fact, target Input Resource, and target
Fact Address columns are indexed, to find a Fact's events, to walk observations per Input
Resource, and to find a Fact Address's conflict resolutions. `fact_records` is also indexed by
Fact Address, for reads of the Facts at many Fact Addresses at once.

Materialized records are updated in the same transaction as the log records they derive from. Where
a table holds commit ranges, a row stores the commit from which it holds and the commit from which
it no longer does.

| Table | Holds | Used for |
| --- | --- | --- |
| `fact_records` | Fact, Fact Address, the position of its latest event, state, awaiting consent, open retirement proposal, unknown answer without new dependency since, the whole Fact Record; always equal to folding the Fact's events | Acceptance checks, current lists and searches, the review list, and reads at a point the Fact has not changed since; a read at an earlier point folds the Fact's events |
| `presentable_periods` | Fact, commit confirmed, commit retired | Determining eligible Facts |
| `address_selections` | Fact Address, selected and eligible Facts per commit range | Reads at a point |
| `dependencies` | Dependent Fact, the Fact or Input Resource depended on, whether in effect, whether used for confirmation; a row neither in effect nor used for confirmation is deleted | Cascades, impact judgement targets, newer-observation checks, the Facts schema interpretation produced from a table |
| `elements` | Element, kind, parent, the Physical Catalog reference of a Dataset or Field, and for edges the kind, ends, direction, and identifying part | Edge identity, filtering by parent, checking that a Field is a column of its Dataset's table |
| `fact_addresses` | Fact Address, element, predicate, scope description, scope URNs, predicate definition of a free-description predicate | Finding empty-scope Fact Addresses |
| `facts` | Fact, Fact Address, identity key | Matching closed-range Facts, finding elements by Physical Catalog reference |
| `input_resources` | Input Resource, kind, Physical Catalog table identifier, the check and target of a data observation, latest table metadata version received | Lookup by table identifier or by check and target, ignoring versions already received, and serializing registrations for one table |

Unique constraints enforce the identity rules that can be expressed as uniqueness:

- `elements`: an edge's kind, ends, direction, and identifying part. Each end is stored as the
  sorted URNs of its elements, such as the Fields of a correspondence's end, and the ends of an
  undirected edge are sorted first. The identifying part is compared by its identity key, which the
  store derives from a correspondence's matching condition with the pairs of each step sorted.
- `fact_addresses`: element and predicate, for rows with an empty scope and a closed-core or
  Generator-declared predicate. Free-description rows are matched only by pointing and are not
  unique.
- `facts`: Fact Address and identity key, for rows that have one. The identity key consists of the
  closed choices and references in the value, with sets such as Field sets sorted by URN.
- `input_resources`: table identifier, or the check and target of a data observation, where
  present.

Dataset and Field identity depends on the state of physical-catalog-reference Facts, so it is kept by the
search in the recording transaction together with the `commits` collision.

Records kept outside the log:

| Table | Key | Holds |
| --- | --- | --- |
| `build_tasks` | Domain, build task | Kind, state, the Fact an evaluation evaluates, content (work, triggers, Generator and version, observation judged, result, failure reason) |
| `review_presentations` | Domain, review presentation | Creation order, content |
| `review_presentation_targets` | Domain, review presentation, Fact | Nothing more |
| `derived_records` | Domain, Physical Catalog table identifier, table metadata version | Table metadata, completeness, read time, retrieval information, content digest, digest of the source metadata schema |
| `source_metadata_schemas` | Domain, schema digest | Connector name, the JSON Schema |
| `translations` | Domain, log record, language | The translated texts by their key in the record, the translator version |
| `call_records` | Domain, call identifier | Operation kind, input, output, time recorded |

`build_tasks` is indexed on state, kind, and target (the evaluated Fact, the Generator of a run, or the language of a translation), for build task reads and for finding the `created` task a new trigger joins. `review_presentation_targets` finds
earlier presentations per Fact for the review list. `call_records` keeps the input itself and
compares it directly to detect a reused identifier with different input.

## Schema and connections

At startup the Semantic Catalog creates the `semantic_catalog` schema if needed and applies, from an
ordered list of changes, those not yet applied. A schema version table in the same schema records
them. Each change and its version row are applied in one transaction, which PostgreSQL's
transactional DDL allows. Processes starting together that try the same change collide on the
version table's primary key, and the loser rereads what was applied and continues. The Physical
Catalog's schema is migrated by the embedded upstream catalog, and the Semantic Catalog manages only
its own.

The Semantic Catalog opens its own connection pool from the store's connection settings, because the
Physical Catalog's connections live inside ScalarDB and cannot be shared.

## Replay check

The invariant that materialized records equal the result of replaying the log is checked by
replaying the whole log from empty and comparing the result, URNs included, with the materialized
tables. The latest received version on `input_resources` is the one column not derived from the log:
it equals the highest version among the table's derived records and is compared against those
instead. The module's tests must run this check, and an operation runs it on a live store. It takes
time proportional to the log, so it does not run at every startup.

## Retaining call identifiers

Call records are kept for 30 days and older ones are deleted on each write. A resend follows soon
after a lost response, so the window only has to cover that. An operation sent with the same
identifier after 30 days is a new call.
