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
| Observation Event | — | The log record of one observation of an Input Resource. It is written in the same commit as the Input Observation it records. | [`record-model.md`](record-model.md) |
| inputs read | — | What a Generator read to produce a candidate, recorded in the `accept` and `propose-retire` Fact Events and in provenance. Input Observations and the Fact Revisions of selected confirmed Facts are also dependency references; rejected or retired Facts, unselected related Facts of feedback, and answers are inputs read only. | [`generators.md`](generators.md) |
| review presentation record | — | The record of what was presented in review, to whom, and when, with the target Facts and Fact Address, a reference to the context shown, any triggering question, and the resulting events. It is kept outside the log because presenting changes no Fact's state. | [`construction.md`](construction.md) |

## Construction and review

| Term | Code identifier | Definition | Explained in |
| --- | --- | --- | --- |
| input route | — | One of the three groups of Generators by what they mainly read: schema interpretation, data checks, and meaning inference. Routes differ in how they read and in the conditions for passing input to an external model. Whether `auto` can confirm a candidate depends on the validation methods of its checks, not on its route. | [`generators.md`](generators.md) |
| validation capability | — | A Generator's declaration of which validation items of which predicates it can examine with which validation methods. | [`generators.md`](generators.md) |
| non-deterministic Generator | — | A Generator that can produce different candidates from the same input, such as one in the meaning-inference route that passes input to a model. It produces only for targets that meet the conditions in the generation strategy. | [`generators.md`](generators.md) |
| generation strategy | — | The rules that decide what each Generator run aims at, how runs come to be ordered, and why construction stops. | [`generators.md`](generators.md) |
| duplicate check | — | The check, before acceptance, of whether a Generator's output makes the same claim as an existing Fact. A match becomes an additional acceptance of that Fact instead of a new Fact. | [`construction.md`](construction.md) |
| preset | `full-auto`, `auto`, `manual` | The part of a user policy version that decides when machine judgement proceeds: always, when the `auto` conditions hold, or never. A new domain starts with `auto`. | [`construction.md`](construction.md) |
| review orchestration | — | The responsibility for deciding what to present, when, to whom, and in which unit and context, for candidates and retirement proposals needing consent and for conflicts needing a selection. It is separate from Generators. | [`construction.md`](construction.md) |
