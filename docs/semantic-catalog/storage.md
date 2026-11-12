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
derived-record-only observation, a review presentation, a resume, and a build task finishing without
candidates. Build task and review presentation numbers are also one past the last number in the
snapshot and are unique by primary key. State changes and added triggers on a build task update the
same `build_tasks` row, so overlapping ones also collide and retry. Registering table metadata,
whether it records an observation or only a derived record, updates the latest received version on
the table's `input_resources` row. A derived-record-only registration judges against the latest
observation of that table, so this update makes it collide with any concurrent registration for the
same table, and the retry judges against the observation that won.

Running a build task never holds a transaction while a Generator runs:

1. **Claim.** Change the task from `created` to `running` and commit. Of two processes claiming one
   task, the later fails on the row update. Adding a trigger updates the same row, so a claimed task
   takes no more triggers.
2. **Run.** Run the Generator, the model-based duplicate check, or the impact judgement outside any
   transaction. A model call takes seconds to tens of seconds, and an open transaction would collide
   with answers committed meanwhile and force the whole run to restart.
3. **Record.** Record the output by the write procedure above, set the task to `succeeded` or
   `failed`, and commit. On a `commits` collision only this step is retried, keeping the run's
   output.

The output was produced from the state at the start of step 2, so step 3 reconciles it with the
current state inside the recording transaction:

- It replaces temporary numbers with issued URNs.
- It matches edges, empty-scope Fact Addresses, and closed-range Facts, including those just written
  by concurrent Generators.
- It finds Datasets and Fields by technical reference. Unique constraints cannot express this
  identity, so a search inside the transaction does the matching, and a concurrent writer that
  created the element first is found on retry.
- It decides whether each data observation equals its predecessor.
- It runs the consistency check, which fails the task and writes nothing when it finds a dependency
  cycle or a missing reference.

If the process stops during step 2, the task stays `running`.

A read runs in one `REPEATABLE READ` transaction, so its result does not change if a commit lands
during it, and it neither blocks nor collides with writes. "Latest" means the last commit visible to
that transaction. A read at a point searches the materialized records by commit range and never
replays the log.

## Identifiers

URNs of logged records come from the position of the log record that created them:

| Record | Identifier |
| --- | --- |
| Element, Fact Address, Fact, Input Observation | Commit number and order within the commit of the creating record |
| Input Resource | Position of the record that first observed it; the question URN for missing knowledge at question time |
| Fact Revision | Fact URN plus the commit number and order of the producing Fact Event |
| Catalog Snapshot | Commit number |
| Build task, review presentation record | A number increasing within the domain |

Log positions are unique, never reused, and reproduced exactly when the log is replayed from empty,
as long as log records are never deleted and the domain is never recreated. Generator output refers
to records not yet written by temporary numbers.

## Tables

Every table has the domain as the first column of its primary key. Record content is a `jsonb`
column whose shape the module's types define. Only what searches and unique constraints need is
promoted to columns.

The log:

| Table | Primary key | Holds |
| --- | --- | --- |
| `domains` | Domain | Creation time |
| `commits` | Domain, commit number | Time, kind of operation, author (the person or agent behind the call) |
| `log_records` | Domain, commit number, order | Record kind, target Fact, target Input Resource, content |

`log_records` is append-only. Fact Events, Observation Events, Input Observations, and policy
versions all go there. Its target Fact and target Input Resource columns are indexed, to find a
Fact's events and to walk observations per Input Resource.

Materialized records are updated in the same transaction as the log records they derive from. Where
a table holds commit ranges, a row stores the commit from which it holds and the commit from which
it no longer does.

| Table | Holds | Used for |
| --- | --- | --- |
| `fact_records` | Fact, Fact Address, the position of its latest event, state, awaiting consent, open retirement proposal, unknown answer without new dependency since, the whole Fact Record; always equal to folding the Fact's events | Acceptance checks, current lists and searches, the review list, and reads at a point the Fact has not changed since; a read at an earlier point folds the Fact's events |
| `presentable_periods` | Fact, commit confirmed, commit retired | Determining eligible Facts |
| `address_selections` | Fact Address, selected and eligible Facts per commit range | Reads at a point |
| `dependencies` | Dependent Fact, the Fact or Input Resource depended on, whether in effect, whether used for confirmation; a row neither in effect nor used for confirmation is deleted | Cascades, impact judgement targets, newer-observation checks |
| `elements` | Element, kind, parent, and for edges the kind, ends, direction, and identifying part | Edge identity, filtering by parent |
| `fact_addresses` | Fact Address, element, predicate, scope description, scope URNs, predicate definition of a free-description predicate | Finding empty-scope Fact Addresses |
| `facts` | Fact, Fact Address, identity key | Matching closed-range Facts, finding elements by technical reference |
| `input_resources` | Input Resource, kind, Physical Catalog table identifier, latest table metadata version received, question URN | Lookup by table identifier and by question, ignoring versions already received, and serializing registrations for one table |

Unique constraints enforce the identity rules that can be expressed as uniqueness:

- `elements`: an edge's kind, ends, direction, and identifying part. The ends of an undirected edge
  are sorted by URN first.
- `fact_addresses`: element and predicate, for rows with an empty scope and a closed-core or
  Generator-declared predicate. Free-description rows are matched only by pointing and are not
  unique.
- `facts`: Fact Address and identity key, for rows that have one. The identity key consists of the
  closed choices and references in the value, with sets such as Field sets sorted by URN.
- `input_resources`: table identifier, and question URN, each where present.

Dataset and Field identity depends on the state of technical-reference Facts, so it is kept by the
search in the recording transaction together with the `commits` collision.

Records kept outside the log:

| Table | Key | Holds |
| --- | --- | --- |
| `build_tasks` | Domain, build task | Kind, state, creation order, content (triggers, Generator and version, observation judged, result, failure reason) |
| `review_presentations` | Domain, review presentation | Creation order, content |
| `review_presentation_targets` | Domain, review presentation, Fact | Nothing more |
| `derived_records` | Domain, Physical Catalog table identifier, table metadata version | Analytics types, retrieval information, content digest |
| `call_records` | Domain, call identifier | Operation kind, input, output, time recorded |

`build_tasks` is indexed on state and kind for build task reads. `review_presentation_targets` finds
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
