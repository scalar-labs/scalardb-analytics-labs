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
transaction committed. `SERIALIZABLE` is not needed. Every writer of a domain collides on the same
`commits` key, so write skew between writers cannot occur.

## Consequences

- Judgements must use only what the transaction read from the store, never state cached in the
  process, so a retry carries no stale assumptions.
- Writes that add no commit must collide on the rows they write instead. Build task and review
  presentation numbers are unique by primary key, and build task claims and added triggers update
  the same `build_tasks` row.
- Generators and model calls must run outside any transaction. A build task is claimed, run, and
  recorded in separate steps, and only the recording step retries on a collision.
