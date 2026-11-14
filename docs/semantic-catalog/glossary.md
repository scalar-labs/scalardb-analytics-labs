# Semantic Catalog glossary

This glossary defines the terms used only inside the Semantic Catalog. Each entry names the document
that explains the term in context.

Terms shared with the rest of the product, such as Fact, Fact Record, Catalog Snapshot, Input
Observation, closed core, user policy, and confirmation, are defined in the product
[glossary](../glossary.md) and are not repeated here.

The glossary keeps names and definitions consistent and does not override the documents. When an
entry and a document disagree, fix the disagreement.

## Parties and structure of knowledge

| Term | Code identifier | Definition | Explained in |
| --- | --- | --- | --- |
| data consumer | — | The user who asks a question, and the Query Agent that composes queries for that user. | [`../semantic-catalog.md`](../semantic-catalog.md) |
| labeled property graph | — | The structure of knowledge: a graph whose nodes and edges have kinds, each kind fixing the predicates recorded for it. | [`record-model.md`](record-model.md) |
| open extension | — | The part of the graph that adds domain-specific meaning without changing how the closed core is interpreted. It holds only what established data-modeling vocabulary cannot express. | [`record-model.md`](record-model.md) |

## Records

| Term | Code identifier | Definition | Explained in |
| --- | --- | --- | --- |
| Observation Event | `LogRecord.ObservationEvent` | The log record of one observation of an Input Resource. It is written in the same commit as the Input Observation it records. | [`record-model.md`](record-model.md) |
| Fact state | `Vocabulary.FactState` | The state of a Fact Record, `candidate`, `confirmed`, `rejected`, or `retired`, defined by which Fact Events it accepts next. | [`record-model.md`](record-model.md) |
| judged transition | `Vocabulary.Transition` | The transition a policy evaluation or an answer is about: confirming a candidate, or retiring a confirmed Fact on an open retirement proposal. A rejection answer is about confirmation, and the `reject` it leads to is a separate event. | [`record-model.md`](record-model.md) |
| basis | `LogRecord.Basis`, `LogRecord.Confirm.basis` | The event a transition rests on. A confirmation rests on a policy evaluation or a consent answer. A rejection or a retirement rests on a Fact Event of the same Fact or, for a cascade, on the rejection or retirement of a premise dependency. | [`record-model.md`](record-model.md) |
| current judgement | `FactRecord.judgementStart` | The judgement of a candidate since the acceptance that last made it a candidate. Only its evaluations and answers, made after the current judgement of every premise dependency began, can be the basis of a confirmation or of a rejection resting on an answer. | [`record-model.md`](record-model.md) |
| selection | `SelectionRule`, `AddressSelectionRow` | The Fact presented at a Fact Address at a commit, and the eligible Facts, derived by rule from confirmations, retirements, replacement dependencies, and conflict resolutions. It is never written to the log; its history per commit range is materialized for reads at past points. | [`catalog-snapshot.md`](catalog-snapshot.md) |
| eligible Facts | `SelectionRule.Result.eligible` | The presentable Facts at a Fact Address that are not displaced by a replacement or by a conflict resolution. Two or more are a conflict. | [`catalog-snapshot.md`](catalog-snapshot.md) |
| conflict resolution | `LogRecord.ResolveConflict` | A deciding party's choice of one of the eligible Facts in a conflict, recorded as `resolve-conflict` with the chosen and passed-over Facts. It is the only part of the selection written to the log. | [`record-model.md`](record-model.md) |
| presentable period | `PresentablePeriodRow` | The commits from a Fact's confirmation until its retirement, during which it is presentable. A Fact confirmed again after retirement has several. | [`catalog-snapshot.md`](catalog-snapshot.md) |
| inputs read | — | What a Generator read to produce a candidate, recorded in the `accept` and `propose-retire` Fact Events and in provenance. Input Observations and the Fact Revisions of selected confirmed Facts are also dependency references; rejected or retired Facts, unselected related Facts of feedback, and answers are inputs read only. | [`generators.md`](generators.md) |
| review presentation record | — | The record of what was presented in review, to whom, and when, with the target Facts and Fact Address, a reference to the context shown, and the resulting events. It is kept outside the log because presenting changes no Fact's state. | [`construction.md`](construction.md) |

## Construction and review

| Term | Code identifier | Definition | Explained in |
| --- | --- | --- | --- |
| input route | — | One of the three groups of Generators by what they mainly read: schema interpretation, data checks, and meaning inference. Routes differ in how they read and in the conditions for passing input to an external model. Whether `auto` adopts a candidate inline depends on the validation methods of its checks, not on its route. | [`generators.md`](generators.md) |
| validation capability | — | A Generator's declaration of which validation items of which predicates it can examine with which validation methods. | [`generators.md`](generators.md) |
| non-deterministic Generator | — | A Generator that can produce different candidates from the same input, such as one in the meaning-inference route that passes input to a model. It produces only for targets that meet the conditions in the generation strategy. | [`generators.md`](generators.md) |
| generation strategy | — | The rules that decide what each Generator run aims at, how runs come to be ordered, and why construction stops. | [`generators.md`](generators.md) |
| duplicate check | — | The check, before acceptance, of whether a Generator's output makes the same claim as an existing Fact. A match becomes an additional acceptance of that Fact instead of a new Fact. | [`construction.md`](construction.md) |
| preset | `full-auto`, `auto`, `manual` | The part of a user policy version that decides when machine judgement adopts a candidate: always; inline on deterministic signals and otherwise as the judging model decides; or never. A new domain starts with `auto`. | [`construction.md`](construction.md) |
| inline adoption | — | Machine judgement under `auto` without a model, when deterministic checks support all of a candidate's content, no restricting item rests on data checks alone, nothing refutes it, and no other Fact is selected at its Fact Address. | [`construction.md`](construction.md) |
| judging model | — | The language model, separate from Generators, that under `auto` weighs the signals of every candidate not adopted inline and either adopts it or sends it to the deciding party with review material. It runs as its own build task, and its version and reason are recorded on the evaluation. | [`construction.md`](construction.md) |
| review item | — | What the review list returns for one candidate or retirement proposal needing consent, or one conflict: what is claimed, the records behind it with their content expanded, what consent changes, and any review material. The Semantic Catalog decides its content; the presenting interface decides its format. | [`construction.md`](construction.md) |
| review material | — | What the judging model builds for a candidate it sends to a person: what is to be decided, why it was not adopted, the evidence for and against, a suggested answer with reasons, and its grouping as likely to follow the suggestion or needing judgement. Kept with the evaluation. | [`construction.md`](construction.md) |
| slug | `ElementIdentity.Unique.slug`, `Urns.SLUG` | The short business name a Generator gives a new Concept, Measure, Dimension, or business term, from which its URN is built. Made unique at acceptance; never a basis for sameness. | [`record-model.md`](record-model.md) |
| technical reference | `TechnicalReference` | The table, by data-source registration, namespace, and table name, or the column a Dataset or Field refers to. It never moves, and its canonical form is both the matching value of the technical-reference Fact and the identifier of the element's URN. | [`record-model.md`](record-model.md) |

## Storage

| Term | Code identifier | Definition | Explained in |
| --- | --- | --- | --- |
| store | `SemanticCatalogStore`, `StoreSettings` | The Physical Catalog's PostgreSQL database, in which the Semantic Catalog keeps its own `semantic_catalog` schema and which it opens with its own connection pool. | [`storage.md`](storage.md) |
| schema change | `SchemaMigrator.Change` | One entry of the ordered list of changes to the `semantic_catalog` schema. Opening the store applies the changes not yet recorded in the schema version table, each exactly once. | [`storage.md`](storage.md) |
| domain identifier | `Urns.DOMAIN` | The caller-supplied identifier of a domain: lowercase letters, digits, and hyphens. It is embedded unchanged in every URN the domain issues. | [`record-model.md`](record-model.md) |
| log record | `LogRecord` | One record of a domain's append-only log: an element, a Fact Address, a Fact, a Fact Event, an Input Resource, an Input Observation, an Observation Event, or a user policy version. Log records are never updated or deleted. | [`record-model.md`](record-model.md) |
| log position | `LogPosition` | The commit number of a log record and its order within the commit, written `c<commit>.<ordinal>`. URNs of Input Resources, Input Observations, log records, and Fact Revisions are issued from it; those of elements, Fact Addresses, and Facts are built from names and kept in the log record that created them. | [`storage.md`](storage.md) |
| temporary number | `Ref.Temp`, `CommitRequest` | How a commit request refers to a record of the same request before it is written. Writing the commit replaces it with the issued URN, or with the URN of the existing record the proposal was the same as. | [`storage.md`](storage.md) |
| element identity | `ElementIdentity` | How a proposed element is identified: unique, always a new element unless the proposer points to an existing one (concepts, Measures, Dimensions, business terms); by the physical object its technical reference names (Datasets and Fields), or by kind, ends, direction, and identifying part (edges). | [`record-model.md`](record-model.md) |
| identity key | `IdentityKey` | The closed choices and record references in a value that decide sameness. The proposer states it for closed-range Facts at one Fact Address; the store derives it for an edge's identifying part, from a correspondence's matching condition with the pairs of each step sorted or from a concept relationship's kind. Compared in a canonical form with reference sets sorted. | [`storage.md`](storage.md) |
| materialized records | `MaterializedRow` | The rows derived from the log and kept for reading at any commit without replaying it. They must always equal what replaying the log gives. | [`storage.md`](storage.md) |
| replay check | `ReplayCheck` | Replaying a domain's whole log from empty and comparing the result, URNs included, with the materialized records. | [`storage.md`](storage.md) |
| commit author | `CommitRequest.author` | The person or agent behind the call that wrote a commit, kept on the commit. | [`storage.md`](storage.md) |
