# Semantic Catalog

**[Planned]** This document describes accepted design that is not implemented yet.

The Semantic Catalog records the knowledge a Query Agent needs to combine data from different
sources correctly, together with the material needed to judge whether that knowledge can be trusted.
It builds that knowledge as candidates, routes each candidate to machine judgement or to a person,
and lets consumers read the confirmed result at a fixed point in time. This document gives the
overall model; the documents under [`semantic-catalog/`](semantic-catalog/) explain each part in
depth. [`semantic-catalog/glossary.md`](semantic-catalog/glossary.md) defines the terms used only
inside the Semantic Catalog.

## The problem it solves

A physical schema of tables, columns, types, and declared constraints does not say what a row
represents, which identifiers denote the same object, whether a join multiplies rows, or which date
and aggregation rule a business quantity uses. Queries composed without that knowledge run normally
and return wrong answers.

The documents use one running example: from a sales domain's e-commerce order database and its
in-store POS database, find the total e-commerce sales of the customers who bought through both
channels in a given month. Answering it needs to know under which conditions an e-commerce customer
ID and a POS member ID denote the same customer, whether sales include tax and how returns count,
which date decides the month, and that a customer can have many POS purchase rows.
If the last point is missed, each e-commerce order line of a customer with three POS purchases is
counted three times.

Three parties are involved:

- **Data consumers**: the user who asks a question and the Query Agent that composes queries for
  that user. The Query Agent is an external AI agent, not part of this repository.
- **The semantics owner**: the person accountable for the semantics of one **domain**, the unit of
  management that assigns one responsible party to a body of data and its meaning. The semantics
  owner can hand judgements to a **delegate**; the owner or a delegate who judges is the **deciding
  party**.
- **The domain owner**: the person who sets the access policy for a domain's data and catalog
  information.

The Semantic Catalog changes the semantics owner's work from writing this knowledge to judging
candidates whose rationale is already assembled. Three qualities shape the design, and none of them
may be improved by silently shifting burden or risk onto another: correctness of what consumers
receive, the judgement load on the deciding party, and traceability of every construction decision
and every use.

## Two layers of knowledge

| Layer | Question it answers | Examples |
| --- | --- | --- |
| Data semantics | What does the data represent, and under which conditions can it be combined correctly? | Row meaning, keys and uniqueness, identifier correspondences and their set relationships, field meaning and units, the business concepts rows refer to and the multiplicity the business allows between them |
| Analytical semantics | How is a business quantity computed from data? | Measure definitions and aggregation rules, filter conditions, reference time and time granularity, derived classifications and calendars, business terms |

Query composition needs both. With data semantics alone, a Query Agent rebuilds aggregation logic
for every question and has no place for an organization's agreed definition of sales. With
analytical semantics alone, it cannot tell whether a measure joins correctly across sources. Both
layers share one recording mechanism; they differ only in the kinds of elements they describe.

## Core concepts

Knowledge is recorded per statement, not per catalog object. A **Fact Address** names one point at
issue: an element, a predicate, and an applicability scope. A **Fact** is one immutable claim of a
value at a Fact Address; a different value is a different Fact. **Fact Events** change how a Fact is
treated (accepted as a candidate, confirmed, rejected, retired, and so on). These, together with
observations of external input, are appended to one log per domain, and the log is the primary
record. A **Fact Record** is the current treatment of one Fact, and a **Fact Revision** is its
version after each Fact Event; both are derived from the log. See
[`semantic-catalog/record-model.md`](semantic-catalog/record-model.md).

A **Catalog Snapshot** is the whole catalog of a domain as derived from the log up to one commit.
For each Fact Address it selects at most one confirmed Fact. A consumer reads one Catalog Snapshot
throughout a question, so later commits never change what it already read. See
[`semantic-catalog/catalog-snapshot.md`](semantic-catalog/catalog-snapshot.md).

A **Generator** produces candidates, with validation results, from physical schemas, data checks,
documents, existing knowledge, and feedback. A Generator goes no further than producing candidates.
Each run is tracked as a **build task**. The **user policy** that the semantics owner sets decides
whether an accepted candidate is confirmed by **machine judgement**, inline on deterministic signals
or by a separate policy evaluation model that weighs fuzzy ones, or needs the deciding party's
consent. A confirmed Fact is adopted knowledge, not proven knowledge. See
[`semantic-catalog/construction.md`](semantic-catalog/construction.md) and
[`semantic-catalog/generators.md`](semantic-catalog/generators.md).

## Responsibilities and boundaries

The Semantic Catalog owns semantic state and its construction: the log, the Catalog Snapshot rules,
Generators and their language-model adapters and prompts (see
[`semantic-catalog/generators.md`](semantic-catalog/generators.md#reaching-a-language-model)), the
duplicate check, user policy evaluation, and the state of every build task, including its inputs,
outputs, Generator versions, and the conditions for resuming it. Calls to an external model may
leave the process, but the work state stays here.

The surrounding components keep the following responsibilities:

- **Physical Catalog.** It is authoritative for physical schemas and Analytics types used for
  planning and execution. The two catalogs do not depend on each other. The application passes each
  table's metadata, exactly as the connector returned it, to both; the Semantic Catalog keeps the
  source-declared part as its input and holds the connector-derived part only as a side record. A
  Dataset reaches a queryable table through a technical reference to a Physical Catalog table, never
  through a table name. See [`physical-catalog.md`](physical-catalog.md).
- **Application.** It decides when build tasks run, one process per domain at a time, because it
  owns startup, shutdown, and resource release as one lifecycle. It provides the Semantic Catalog
  with SQL execution for data checks, bounded by sampling and limits, name resolution from a
  Physical Catalog table to its current name, and re-retrieval of table metadata. It authenticates
  callers, issues the authority-check reference after checking that a person is a semantics owner,
  creates the domain at `workspace init`, and keeps the domain's settings in the workspace
  configuration.
  See [`application.md`](application.md). It calls the Semantic Catalog only through the public API
  in [`semantic-catalog/public-api.md`](semantic-catalog/public-api.md).
- **Query Agent.** Its model, knowledge discovery, SQL generation, and result interpretation are
  outside this component. It calls through the application as a principal an administrator
  registered, reads knowledge, registers usage feedback, and cites what it used with its answer. It
  cannot confirm knowledge.
- **Governance.** It evaluates whether a using party may access knowledge. The Semantic Catalog
  decides only whether knowledge is semantically presentable, and masks what Governance denies.
- **Query Engine.** The Semantic Catalog never executes SQL or returns data rows itself. Its storage
  is unreachable from Query Engine connections and from SQL written by users or models, so untrusted
  SQL can neither read nor change the log. See
  [`semantic-catalog/storage.md`](semantic-catalog/storage.md).

What a user's LLM session finds worth keeping, whether missing knowledge met while composing a
query or something the user said, reaches the Semantic Catalog as usage feedback, an input like any
other with its own URNs. The Semantic Catalog does not depend on the product's tracing of use and
never stores query result rows.

The current design covers one domain. References between domains are limited to read-only access,
governed by the domain owner's policy, that never lets one domain change another's meaning.

## End-to-end flow

```text
data source registration / re-retrieval / documents / feedback
        |
        v
register observation ---> build tasks ---> Generators (outside the writer)
                                                 |
                                                 v
                                  duplicate check, acceptance as candidates
                                                 |
        user policy evaluation (once dependencies are confirmed or awaiting consent)
                           |                                  |
         machine judgement: inline or the model   review by deciding party
                           |                                  |
                           +-------------> confirmed <--------+
                                               |
                                               v
                          Catalog Snapshot at a later commit
                                               |
                                               v
              Query Agent reads at a fixed point; missing knowledge,
              conflicts, and corrections return as new input
```

1. Registering a data source records the observed physical schema of each table. Each new
   observation creates build tasks for the Generators that read it.
2. Generators produce candidates. The Semantic Catalog accepts them as candidate Facts after the
   duplicate check, in one commit per run.
3. Once every Fact a candidate depends on is confirmed or awaiting consent, the user policy decides
   its path. Candidates that deterministic signals cover are adopted in the same commit; under
   `auto` the policy evaluation model decides the others in its own build task, adopting them or
   preparing review material for the deciding party, who consents, rejects, corrects, or answers
   unknown.
4. Each confirmation produces new build tasks for the Generators that build on it. For example,
   confirming that a Dataset exists starts key, uniqueness, row-meaning, and field-meaning
   Generators.
5. The Query Agent fixes a Catalog Snapshot, reads along the hierarchy of data sources and tables
   or finds elements, and requests knowledge. For each request it receives the selected Fact with
   its validation results, provenance, and confirmation path, or a status of missing knowledge,
   awaiting judgement, or conflict.
6. Missing knowledge, other statements from use, and corrections are registered as usage feedback
   and start the next round. A user who is the deciding party can confirm on the spot; other users
   wait, or explicitly accept a result marked as based on unconfirmed knowledge.

## Design decisions

- [`design/semantic-catalog-store.md`](design/semantic-catalog-store.md)
- [`design/semantic-catalog-write-serialization.md`](design/semantic-catalog-write-serialization.md)
- [`design/semantic-catalog-generator-granularity.md`](design/semantic-catalog-generator-granularity.md)
- [`design/semantic-catalog-default-user-policy.md`](design/semantic-catalog-default-user-policy.md)
- [`design/semantic-catalog-machine-judgement.md`](design/semantic-catalog-machine-judgement.md)
- [`design/semantic-catalog-premise-review.md`](design/semantic-catalog-premise-review.md)
- [`design/semantic-catalog-urn-form.md`](design/semantic-catalog-urn-form.md)
- [`design/semantic-catalog-expression-values.md`](design/semantic-catalog-expression-values.md)
