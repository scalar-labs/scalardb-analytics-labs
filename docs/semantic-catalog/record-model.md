# Semantic Catalog record model

**[Planned]** This document describes accepted design that is not implemented yet.

This document explains what the Semantic Catalog records: the structure of knowledge, the log,
identity, Fact states, and validation results. Its terms are introduced in
[`../semantic-catalog.md`](../semantic-catalog.md). How the Catalog Snapshot is derived from these
records is in [`catalog-snapshot.md`](catalog-snapshot.md), and how records are added is in
[`construction.md`](construction.md).

## Structure of knowledge

Knowledge is a labeled property graph. Nodes and edges have kinds, and each kind fixes the
predicates recorded for it. Typed elements let the Query Agent choose the properties and checks each
kind needs, which untyped subject-predicate-object triples would not. The graph has two parts:

- The **closed core** is the structure the Semantic Catalog and the Query Agent interpret in common.
- The **open extension** adds domain-specific meaning without changing how the closed core is
  interpreted, because a central body cannot enumerate every domain's meaning in advance.

Relationships between data use established data-modeling vocabulary: uniqueness, set relationships
between identifiers, and multiplicity between concepts. Only what that vocabulary cannot express
goes into the open extension.

The **business domain model**, the concepts such as customer and order and the relationships between
them, is data semantics and lives in the Semantic Catalog. Each domain holds only as much of it as
it needs, and no unmanaged business domain model is kept elsewhere.

A Dataset is the logical object meaning is given to, not a table. It holds a **technical reference**
to the Physical Catalog table its data comes from, so a table rename is handled as a change of
reference rather than a change of meaning. One technical-reference Fact points to one table.

## Elements and predicates

The closed core defines, per kind of element, the predicates Generators produce candidates for. The
list says what construction aims at; it is not a set of conditions an element must meet before use.
Whether a piece of knowledge matters depends on how the element is used: a field's unit matters once
the field is aggregated.

| Node element | Predicates |
| --- | --- |
| Dataset | Technical reference, concept a row represents, row kind, row grain, row inclusion, key, uniqueness of a set of Fields |
| Field | Technical reference, meaning, value kind, unit, meaning of null, represented concept, time meaning and time zone |
| Concept | Meaning |
| Measure | Meaning, base Dataset, aggregation rule, filter conditions, reference time Field, default time granularity |
| Dimension | Meaning, Dimension kind, source Field and classification expression, source time Field, hierarchy, calendar rule |
| Business term | Written form, referenced element |

| Edge element | Ends and direction | Identifying part | Predicates |
| --- | --- | --- | --- |
| Identifier correspondence | Field set to Field set | Matching condition: pairs of corresponding Fields, each side optionally normalized, direct or through another Field or Dataset | Set relationship, business definition that both denote the same object |
| Concept relationship | Concept to concept; undirected when both denote the same object | Relationship kind | Multiplicity |

Every element also has an **existence** predicate, which states that the element is a target of the
catalog. Every other Fact about an element depends on its existence Fact. A Field holds a reference
to its Dataset.

There is no separate join element. Joining two Datasets correctly rests on knowing that values on
both sides denote the same object, which is what an identifier correspondence states, from an order
line's customer ID to the customer table as much as from e-commerce customers to POS members. What a
join would add is already elsewhere: how many rows on one side match a row on the other follows from
the uniqueness of each side's Field set, and whether every row takes part is the set relationship.
A foreign key is not an element either. It is a declaration in a physical schema observation and
supports a correspondence with `declared-enforced` or `declared-unenforced`.

A correspondence's matching condition is part of its identity rather than a predicate, because a
different condition is a different correspondence, not another value of the same one. Whether the
condition holds is judged on the edge's existence Fact. A side of a pair may be normalized before
comparison, for example when e-mail addresses match only when case is ignored. Normalization steps
are chosen from a closed list the product defines, such as lower-casing, trimming, Unicode
normalization, conversion to text, and removing non-digits, never written as free expressions: two
free expressions of the same normalization would make one correspondence two edges, and a Query
Agent can turn a listed step into SQL without interpreting it.

Some values deserve note:

- **Row meaning** is four predicates that change independently. The concept a row represents (an
  order line), the row kind (current state of an object, event, state per point in time, or
  pre-aggregated), the row grain for per-point and pre-aggregated rows (store, product, and day for
  daily inventory), and the row inclusion (whether return rows or logically deleted rows are
  included). Adding return rows changes only row inclusion. Row meaning is never defined as what
  counting rows gives, because that cannot describe snapshot or pre-aggregated rows.
- A **key** is a set of Fields or "no key". **Uniqueness** carries its Field set in the predicate
  and takes "does not repeat" or "repeats". Whether uniqueness rests on a declaration or a data
  check is expressed by the validation method, not by separate predicates, because the declaration
  is already part of the physical schema observation.
- A Field's **value kind** is identifier, classification, amount, quantity, time or date, string, or
  boolean. It is separate from the physical type and tells Generators which further predicates to
  pursue.
- A Measure's **aggregation rule** is an aggregate function over a value expression on its base
  Dataset's Fields. A period that changes per question belongs to the question, not to the Measure.
  Its default time granularity refers to a level of a calendar Dimension, because "monthly" may mean
  calendar or fiscal month.
- An identifier correspondence's **set relationship** is equal, subset, partial overlap, or
  disjoint. Without it, nobody can tell whether an inner join drops members who appear on one side
  only.
- A concept relationship's **multiplicity** is what the business allows, such as a member making any
  number of purchases. Data can only show what holds now: if every member has bought once so far,
  the purchase rows' member IDs do not repeat, yet a query that relies on that double-counts as soon
  as a member buys again. The uniqueness of Field sets records what the data shows; concept
  multiplicity records the rule.

Dimensions become elements only for derived classifications (age bands, regional groupings) and
calendars (fiscal year, fiscal month). An axis that is a Field's own value is that Field's meaning,
and aggregation per business object rests on keys and identifier correspondences. Making Dimensions
for those would split one meaning across two Fact Addresses that the deciding party would judge
twice.

The layer of a statement follows from its element: statements about Measures, Dimensions, and
business terms are analytical semantics, and the rest are data semantics.

Predicate definitions come from the closed core, from a Generator that declares a predicate with its
definition and value range, or from a **free-description predicate** whose natural-language
definition is written into the Fact Address. A model creates a free-description predicate only when
an input states knowledge the closed core cannot express, such as "whether inventory counts may be
summed across days". Free-description predicates have no identity of their own; a recurring
statement points to the existing Fact Address or is caught by the duplicate check.

Predicates whose values are closed choices or references to records have a **closed value range**:
existence; a Dataset's technical reference, represented concept, row kind, key, and uniqueness; a
Field's technical reference, value kind, and represented concept; a Measure's base Dataset and
reference time Field; Dimension kind; a business term's referenced element; set relationship; and
concept-relationship multiplicity. A Generator-declared
predicate with a closed range counts too. Two such values are the same exactly when they are equal.

## The log

The primary record of a domain is one append-only log. Each write is a **commit** with a number that
increases monotonically within the domain. A commit holds one or more records in order and is
written entirely or not at all. A point in time is a commit number. Wall-clock time is kept on
records but never used as a point, because it is neither unique nor monotonic.

The log holds:

- Elements, each written as its own record that states how the element is identified: by pointing,
  by technical reference, or for an edge by kind, ends, direction, and identifying part. A new
  element is written in the same commit as the acceptance of a Fact stating its existence. A new
  Dataset or Field also comes with the acceptance of its technical-reference Fact, because that Fact
  is what later finds it.
- Fact Addresses, Facts, and Fact Events.
- Conflict resolutions, each a deciding party's choice among the eligible Facts at one Fact
  Address.
- **Input Resources**, sources of input whose changes are tracked, such as one table's physical
  schema or one registered document. Also **Input Observations**, the content of an Input Resource
  as observed at one commit, with a reference to the previous observation, and the **Observation
  Events** that record each observation.
- User policy versions. They are in the same log so that every policy evaluation can name the
  version in effect, and the order of version changes and evaluations is decided by the log.

Facts, Fact Records, Fact Revisions, and Catalog Snapshots are derived from the log. Whatever is
materialized for reading must always equal what replaying the log gives at the same commit.

The first design stores every input's content directly in the log. Because the log is append-only,
written content cannot be erased.

## Identity and URNs

Every record is referred to by a URN of the form
`urn:scalardb:analytics:<domain>:<record kind>:<identifier>`. The namespace spells out the product
name, because the product defines no abbreviation of it. The Semantic Catalog issues URNs when it
creates records. A URN is unique, never changes, is
never reused, and carries none of the record's content. Content-based names would break references
on correction and merge distinct objects that share a name, and the business identity they would
presuppose is what construction is meant to establish. The domain comes first after the namespace
so that cross-domain references can use the same form.

| Record | Identifier | A new one appears |
| --- | --- | --- |
| Catalog Snapshot | Domain and commit number | At each commit |
| Log record | Commit number and order within the commit | For each record |
| Fact Revision | Fact URN and the Fact Event that produced it | Each time a Fact Event applies |
| Node element | Issued value | When a Fact stating its existence is first accepted |
| Edge element, Fact Address, Fact | Issued value | When no existing one is the same under the rules below |
| Input Resource, Input Observation | Issued value | On a new source, on each observation |
| Build task | Issued value | For each build task |

Sameness is decided by rules per kind, never from URNs. Meaning is fuzzy, so mechanical decisions
are limited to cases that are easy to identify:

- **Datasets and Fields** are the same element when their technical-reference Facts that are neither
  rejected nor retired point to the same physical object. Replacing the technical reference keeps
  the element through a column rename. A column dropped and re-added under the same name is the same
  Field, because observations cannot tell them apart.
- **Concepts, Measures, Dimensions, and business terms** are the same element only when the proposer
  points to the existing one. Two concepts later found to denote the same object are linked by a
  concept-relationship edge, which a deciding party or a document must support.
- **Edges** are the same when kind, ends, direction, and identifying part match; undirected edges
  ignore the order of their ends. Two Generators proposing the same correspondence therefore produce
  one edge.
  A proposal that changes the ends or the identifying part becomes a new edge plus a retirement
  proposal for the old one.
- A **Fact Address** is an element, a predicate, and an applicability scope. An applicability scope
  pairs a natural-language description with the URNs of the entities it mentions, and an
  unrestricted statement has an empty scope. A Fact Address with an empty scope is found by element
  and predicate. One with a description is the same only when the proposer points to it.
- A **Fact** with a closed-range predicate is the same when its value matches at the same Fact
  Address. Other Facts are the same only when the proposer points to them.

Wording differences that these rules miss are caught by the duplicate check before acceptance (see
[`construction.md`](construction.md#acceptance-and-the-duplicate-check)).

Separate Facts at one Fact Address are different answers to the same point and therefore
incompatible. Values that hold at the same time in different applicability scopes, such as the
posting date for accounting's monthly report and the order date for promotion analysis, belong to
different Fact Addresses and do not conflict. Whether overlapping scopes disagree is not decided by
rule; a Generator can propose that they are incompatible, and that proposal is judged like any
other.

## Fact Events and states

| Fact Event | Target | Holds |
| --- | --- | --- |
| `accept` | One Fact | Inputs read, dependency references, validation result, provenance (Generator, version, build task) |
| `propose-retire` | One Fact | Reason (element no longer exists, source declaration no longer exists, or confirmation was wrong), inputs read, validation result, provenance |
| `evaluate-policy` | One Fact | Policy version, what is evaluated (confirmation or retirement), result (machine judgement or request consent), deciding party, reason |
| `answer` | One Fact | What is answered, answer type (consent, rejection, correction, unknown), deciding party, authority-check reference, wording, review presentation |
| `confirm` | One Fact | Path (machine judgement or human consent) and the policy evaluation or answer it rests on |
| `reject` | One Fact | The answer it rests on, or the rejection or retirement of a Fact it depends on |
| `retire` | One Fact | The policy evaluation or answer it rests on, or the retirement of a Fact it depends on |

For machine judgement, the evaluating party is recorded as the deciding party. An answer and the
transition it causes are separate events. A conflict resolution, `resolve-conflict`, is not a Fact
Event: it targets one conflicting Fact Address and holds the chosen Fact, the passed-over Facts,
the deciding party, the authority-check reference, the wording, and the review presentation. It
is accepted only when its chosen and passed-over Facts are exactly the eligible Facts, two or
more, and it is the only part of the selection written to the log. An **additional
acceptance** is a later `accept` of an existing Fact. A `propose-retire` changes no dependency
reference; the checks of its validation result may reference any Input Observation or Fact
Revision. A refutation is a refuting check inside an `accept` or `propose-retire`. An update
proposal is the acceptance of another Fact that depends on the selected one.

The state of a Fact Record is defined by which Fact Events it accepts next:

| State | Meaning | Accepts |
| --- | --- | --- |
| `candidate` | Accepted but not confirmed | `accept`, `propose-retire`, `evaluate-policy`, `answer`, `confirm`, `reject` |
| `confirmed` | Confirmed knowledge | `accept`, `propose-retire`, `evaluate-policy`, `answer`, `retire` |
| `rejected` | Judged not to be confirmed | `accept` with a new dependency reference (back to `candidate`), `answer` (no change) |
| `retired` | Confirmed knowledge judged no longer usable | Same as `rejected` |

Transitions need the events they rest on:

- `confirm` requires every premise dependency to be `confirmed`, and either a latest policy
  evaluation that chose machine judgement or a consent answer.
- `retire` requires an open retirement proposal with a machine-judgement evaluation or a consent
  answer, or the retirement of a premise dependency.
- A retirement proposal against a `candidate` rejects it instead.

A retirement proposal ends with retirement, rejection, or a rejection or correction answer. An
unknown answer does not end it, but the proposal is not presented again until a new dependency
reference arrives. An answer to a candidate whose premise dependencies are not all confirmed is
recorded without a transition; the candidate is judged again, from policy evaluation, once they are.

A candidate's **current judgement** starts with the acceptance that made it a candidate, the first
one or one that returned it from `rejected` or `retired`. Policy evaluations and answers made
earlier belong to an earlier judgement, and none of them can be the basis of a transition. A
candidate is evaluated for confirmation only when every premise dependency is `confirmed`. A
confirmation, and a rejection resting on an answer, rest only on an evaluation or answer of the
current judgement made after every premise dependency was last confirmed. A rejection resting on
a retirement proposal needs a proposal made within the current judgement.

Retirement covers both an element that no longer exists and a confirmation that was wrong; the
reason and validation result say which. "No longer exists" is a change in treatment, not a value.

Some conditions are derived instead of being states, because they do not change which events a Fact
accepts. Waiting for consent is the result of the latest policy evaluation of the current judgement,
or of the open retirement proposal. Being a new candidate
for the selected Fact, and being in conflict, are derived from the Catalog Snapshot.

The dependency references that count for selection and cascading are those of the Fact Revision at
the commit of confirmation. References added by later acceptances have not been through the
confirmation judgement and do not change selection.

## Validation results and dependencies

A **validation result** records how far checks covered a predicate's **validation items**. For most
predicates the item is the value itself. An identifier correspondence's existence has one item per
pair of corresponding Fields, per normalization step, and per intermediate step of its matching
condition, and a Measure's aggregation rule has the aggregate function and the value expression.

Each check holds its validation method, the items it covered, a result of supported, refuted, or
undecidable, and a reference to the input it was checked against: an Input Observation or a Fact
Revision. A `document` check quotes the relevant source text. An `inference` check may have no
reference.

| Validation method | Meaning |
| --- | --- |
| `declared-enforced` | Declared and enforced by the source: tables and columns, types, nullability, enforced constraints |
| `declared-unenforced` | Declared but not enforced by the source |
| `data-check` | A deterministic check of an observation of the data |
| `document` | The relevant part of a document the semantics owner registered |
| `inference` | The Generator's inference, including from names or a model's prior knowledge |

`declared-enforced` and `data-check` are the **deterministic validation methods**; a user policy
version can add `declared-unenforced`. The range that could not be confirmed is computed as the
items no supported check covers; Generators report coverage, never the unconfirmed range itself. A
model's prior knowledge and name-based inference can produce candidates but are not validation. A
human answer is never part of a validation result. Inputs read, validation results, and human
consent are recorded separately and never substitute for one another.

A candidate answers three separate questions:

| Question | Record | Content |
| --- | --- | --- |
| How was it produced? | Provenance | Generator and version, build task, and the inputs read |
| How far was it checked? | Validation result | Each check's method, items, result, and reference |
| What changes require re-examining it? | Dependency references | The Input Observations and Fact Revisions, at the versions read, whose changes are tracked |

A dependency on an input names the Input Resource, used to find what a new observation affects, and
the Input Observation actually used, used to reproduce past content. A dependency on a Fact names
the Fact and the Fact Revision used. A locator such as a URL or table name never stands alone as the
record of an input. Each `accept` states the complete set of dependency references in effect after
it, replacing the set of earlier acceptances, so an additional acceptance restates every dependency
it keeps. A Fact Revision shows the set in effect, with the validation result and provenance of the
same acceptance. Cascades follow the set at the latest confirmation, so a dependency dropped after
confirmation still cascades until the Fact is confirmed again.

A dependency on another Fact is one of two kinds. A **premise dependency** points to a Fact at
another Fact Address, such as the existence of the element or of a correspondence: the candidate
holds only if
that Fact holds. A **replacement dependency** points to an earlier Fact at the same Fact Address:
the candidate was made taking that Fact into account, which is what lets it replace that Fact once
confirmed. Confirmation waits only for premise dependencies, and only premise dependencies cascade
rejection and retirement, so retiring a Fact that has been replaced leaves its replacement standing.

Three invariants hold. Any Input Observation a check references is also a dependency, so a candidate
is re-examined when that input changes. A Fact Revision a check references need not be one, because
that would change confirmation and selection. Dependencies never form a cycle among Facts, because
two candidates waiting on each other's confirmation would never be judged.
