# How are writes to one Semantic Catalog domain kept serializable?

**[Planned]** This decision is accepted but not implemented yet.

## Current answer

Each write that appends to a domain's log runs in one `REPEATABLE READ` transaction. It judges from
the transaction's snapshot and inserts a `commits` row numbered one past the last commit it saw.
Overlapping writers try to insert the same number, the later one fails on the primary key of
`commits`, and it retries from the beginning in a new transaction. No lock is taken and the number
of writing processes is not limited.

## Rationale

The model needs every judgement to be made on top of the previous commit, so that commit numbers are
assigned in order and acceptance, policy evaluation, and cascades never see a stale state. What it
needs is that overlapping writes end as if made one at a time. Mutual exclusion is not itself
required. Each CLI invocation is a separate process, and construction and review answers can run at
the same time, so the guarantee must come from the store, not from inside one process.

A transaction-level advisory lock per domain also gives that guarantee, but adds a lock-key scheme
and a domain-to-integer mapping to maintain, and transactions with a unique key already provide the
same outcome. `READ COMMITTED` is not enough. It takes a new snapshot per statement, so a
transaction could judge on an old state and then read and use a commit number written after another
transaction committed. `SERIALIZABLE` is not needed. Every write that judges against the log appends
a commit, so any two of them collide on the same `commits` key and write skew between them cannot
occur. Writes that append no commit must collide on some row too. A review presentation, a resume,
and a build task finishing without candidates decide only about the rows they write, a newly
numbered presentation or one `build_tasks` row, which primary keys and same-row updates protect. A
derived-record-only observation does read the log: it compares the table metadata with the latest
observation of the same table. Every registration for a table therefore also updates that table's
Input Resource row, so it collides with any concurrent registration for the table, including one
that commits a new observation.

## Consequences

- Judgements must use only what the transaction read from the store, never state cached in the
  process, so a retry carries no stale assumptions.
- Writes that add no commit must collide on the rows they write instead. Build task and review
  presentation numbers are unique by primary key, and build task claims and added triggers update
  the same `build_tasks` row.
- Generators that call a model or the application's SQL execution, and model calls, must run
  outside any transaction. Such a build task is claimed, run, and recorded in separate steps, and
  only the recording step retries on a collision.
- A deterministic Generator that reads only the store finishes in milliseconds, so it runs inside
  the transaction that records its output. It then reads exactly the state it is recorded on and
  needs no reconciliation; a collision runs it again, which gives the same output on the same state.
