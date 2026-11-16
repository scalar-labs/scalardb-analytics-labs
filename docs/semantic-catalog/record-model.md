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

A Dataset is the logical object meaning is given to. It holds a **Physical Catalog reference** to the
Physical Catalog table its data comes from, and a Field holds one to its column. It names the table by the Physical
Catalog's registration unit, its data-source registration, namespace, and table name, rather than
by the Physical Catalog's resource identifier. One physical-catalog-reference Fact points to one
table or column and never moves to another: a renamed table or column becomes a new element, as a
renamed table gets a new resource identifier in the Physical Catalog (see
[Identity and URNs](#identity-and-urns)).

## Elements and predicates

The closed core defines, per kind of element, the predicates Generators produce candidates for. The
list says what construction aims at; it is not a set of conditions an element must meet before use.
Whether a piece of knowledge matters depends on how the element is used: a field's unit matters once
the field is aggregated.

| Node element | Predicates |
| --- | --- |
| Dataset | Physical Catalog reference, concept a row represents, row kind, row grain, row inclusion, key, uniqueness of a set of Fields |
| Field | Physical Catalog reference, meaning, value kind, unit, meaning of null, represented concept, time meaning and time zone |
| Concept | Meaning |
| Measure | Meaning, base Dataset, aggregation rule, filter conditions, reference time Field, default time granularity |
| Dimension | Meaning, Dimension kind, source Field and classification expression, source time Field, hierarchy, calendar rule |
| Business term | Written form, referenced element |

| Edge element | Ends and direction | Identifying part | Predicates |
| --- | --- | --- | --- |
| Identifier correspondence | Field set to Field set, directed | Matching condition: pairs of corresponding Fields, each side optionally normalized, direct or through the Fields of intermediate Datasets | Set relationship, business definition that both denote the same object |
| Concept relationship | Concept to concept; undirected when both denote the same object | Relationship kind | Multiplicity |

Every element also has an **existence** predicate, which states that the element is a target of the
catalog. Every other Fact about an element depends on its existence Fact. A Field holds a reference
to its Dataset.

Each predicate is named in records by an identifier:

| Element | Predicate identifiers |
| --- | --- |
| Every element | `existence` |
| Dataset | `physical-catalog-reference`, `row-concept`, `row-kind`, `row-grain`, `row-inclusion`, `key`, and `uniqueness:<Field URNs, sorted and joined by commas>` |
| Field | `physical-catalog-reference`, `meaning`, `value-kind`, `unit`, `null-meaning`, `represented-concept`, `time-meaning` (with the time zone) |
| Concept | `meaning` |
| Measure | `meaning`, `base-dataset`, `aggregation-rule`, `filter`, `reference-time-field`, `default-time-granularity` |
| Dimension | `meaning`, `dimension-kind`, `classification` (the source Field and classification expression), `source-time-field`, `hierarchy`, `calendar-rule` |
| Business term | `written-form`, `referenced-element` |
| Identifier correspondence | `set-relationship`, `business-definition` |
| Concept relationship | `multiplicity` |

A uniqueness predicate names its Field set, so it cannot be listed before a Field set is proposed.
Every other identifier above is a **closed-core predicate** of its kind: a request for all
predicates of an element reports each one without a Fact Address as missing knowledge (see
[`public-api.md`](public-api.md#requesting-knowledge)).

There is no separate join element. Joining two Datasets correctly rests on knowing that values on
both sides denote the same object, which is what an identifier correspondence states, from an order
line's customer ID to the customer table as much as from e-commerce customers to POS members. What a
join would add is already elsewhere: how many rows on one side match a row on the other follows from
the uniqueness of each side's Field set, and whether every row takes part is the set relationship.
A foreign key is not an element either. It is a declaration in a physical schema observation and
supports a correspondence with `declared-enforced` or `declared-unenforced`.

Each end of a correspondence is a non-empty set of Fields of one Dataset, because a Field set is
what identifies an object in its Dataset. A matching condition through a mapping table is a chain of
steps, each pairing the Fields of one Dataset with the Fields of the next, and each step starts from
the Dataset the previous one arrived at. A proposal that breaks either rule is rejected at
acceptance.

A correspondence's matching condition is part of its identity rather than a predicate, because a
different condition is a different correspondence, not another value of the same one. Whether the
condition holds is judged on the edge's existence Fact. A side of a pair may be normalized before
comparison, for example when e-mail addresses match only when case is ignored. Normalization steps
are chosen from a closed list the product defines (lower-casing, trimming, Unicode normalization
form NFKC, conversion to text, and removing non-digits), never written as free expressions: two
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
  As written by schema interpretation, a key's value is `{"fields": [...]}`, the URNs of its Fields
  sorted, and two keys are the same when their Field sets are. A uniqueness Fact's values are
  `does-not-repeat` and `repeats`.
- A Field's **value kind** is identifier, classification, amount, quantity, time or date, string, or
  boolean. The value is one of `identifier`, `classification`, `amount`, `quantity`,
  `time-or-date`, `string`, and `boolean`; schema interpretation proposes `time-or-date` and
  `boolean` from the Analytics type. It is separate from the physical type and tells Generators which further predicates to
  pursue.
- A Measure's **aggregation rule** is an aggregate function, chosen from a closed list, over a value
  expression on its base Dataset's Fields. A period that changes per question belongs to the
  question, not to the Measure. Its default time granularity refers to a level of a calendar
  Dimension, because "monthly" may mean calendar or fiscal month.
- **Expression values**, the value expression of an aggregation rule, a Measure's filter
  conditions, and a Dimension's classification expression, are SQL expression strings in the Query
  Engine's dialect over the column names of the base Dataset's table, such as
  `status <> 'cancelled'`. The string is the reference representation of the definition: the
  product's Query Engine runs it as written, and running it on another engine means translating
  it. At acceptance the Semantic Catalog parses it with the Query Engine's parser and adds the
  Fields it references as premise dependencies, so a column that disappears cascades to the
  definitions that use it. An expression that does not parse or names a missing column is not
  accepted.
  [`../design/semantic-catalog-expression-values.md`](../design/semantic-catalog-expression-values.md)
  records why.
- An identifier correspondence's **set relationship** is equal, subset, partial overlap, or
  disjoint, read from the end it starts from, so subset means that end's values are among the
  other's. Without it, nobody can tell whether an inner join drops members who appear on one side
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
existence; a Dataset's Physical Catalog reference, represented concept, row kind, key, and uniqueness; a
Field's Physical Catalog reference, value kind, and represented concept; a Measure's base Dataset and
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
  by Physical Catalog reference, or for an edge by kind, ends, direction, and identifying part. A new
  element is written in the same commit as the acceptance of a Fact stating its existence. A new
  Dataset or Field also comes with the acceptance of its physical-catalog-reference Fact, because that Fact
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
creates records. A URN is unique, never changes, and is never reused. The domain comes first after
the namespace so that cross-domain references can use the same form.

The identifier is built so that a person reading a URN can tell roughly what it names. Most of what
a catalog holds is about tables and columns, and opaque numbers would make every review item,
citation, and log entry unreadable without a lookup. The form is a construction choice, not a
contract. Callers never parse a URN, programs read exact names from Facts such as Physical Catalog
references and written forms, and the form of newly issued URNs may change. The text of a URN is
never knowledge either: meaning is read only from Facts.
[`../design/semantic-catalog-urn-form.md`](../design/semantic-catalog-urn-form.md) records why.

The record kind and identifier of each record, with full URNs for a domain `sales`:

| Record | Record kind | Identifier | Example | A new one appears |
| --- | --- | --- | --- | --- |
| Catalog Snapshot | `catalog-snapshot` | Commit number | `urn:scalardb:analytics:sales:catalog-snapshot:3` | At each commit |
| Log record | `log-record` | Log position: `c<commit>.<order within the commit>` | `urn:scalardb:analytics:sales:log-record:c2.14` | For each record |
| Dataset | `dataset` | `<data-source registration>.<namespace>.<table>` | `urn:scalardb:analytics:sales:dataset:shop.public.orders` | When a Fact stating its existence is first accepted |
| Field | `field` | Its table's identifier and `.<column>` | `urn:scalardb:analytics:sales:field:shop.public.orders.amount` | When a Fact stating its existence is first accepted |
| Concept, Measure, Dimension, business term | `concept`, `measure`, `dimension`, `business-term` | The slug the proposing Generator gives | `urn:scalardb:analytics:sales:measure:ec-sales` | When a Fact stating its existence is first accepted |
| Edge element | `identifier-correspondence`, `concept-relationship` | `<one end>,<other end>`, each end the identifiers of its elements, sorted and joined by `+`; an undirected edge's ends in a fixed order | `urn:scalardb:analytics:sales:identifier-correspondence:shop.public.orders.customer_id,shop.public.customers.id` | When no existing one is the same under the rules below |
| Fact Address | `fact-address` | `<element kind>:<element identifier>/<predicate>` | `urn:scalardb:analytics:sales:fact-address:field:shop.public.orders.amount/meaning` | When no existing one is the same under the rules below |
| Fact | `fact` | `<Fact Address identifier>/<sequence number from 1>` | `urn:scalardb:analytics:sales:fact:field:shop.public.orders.amount/meaning/1` | When no existing one is the same under the rules below |
| Fact Revision | `fact-revision` | `<Fact identifier>@<log position of the producing Fact Event>` | `urn:scalardb:analytics:sales:fact-revision:field:shop.public.orders.amount/meaning/1@c2.32` | Each time a Fact Event applies |
| Input Resource, Input Observation | `input-resource`, `input-observation` | Log position of the creating record | `urn:scalardb:analytics:sales:input-observation:c2.10` | On a new source, on each observation |
| Build task | — | Issued value | — | For each build task |

Where an identifier built this way is already taken, `~<n>` is appended with the first free `n`
from 2, in log order: a second Measure proposed with the slug `ec-sales` is
`urn:scalardb:analytics:sales:measure:ec-sales~2`, and a third, in a later commit, `~3`. Two edges
between the same ends with different identifying parts are told apart the same way. A Fact Address
with a non-empty applicability scope, or of a free-description predicate, always has one
(`…/meaning~2`), so the plain identifier stays with the Fact Address found by element and
predicate. A Fact Address carries its element's kind as well as its identifier, because elements of
different kinds may share a slug: `concept:ec-sales` and `measure:ec-sales` have different Fact
Addresses. Facts at one Fact Address are numbered in log order, across commits.

Names are percent-encoded as UTF-8 bytes, leaving only ASCII letters, digits, `-`, and `_` as
written, so a name never contains a separator (`.`, `/`, `~`, `@`, `,`, `+`, `:`). A column
`unit price` gives `field:shop.public.orders.unit%20price`, a column `売上` gives
`field:shop.public.orders.%E5%A3%B2%E4%B8%8A`, and a table `sales.v2` gives
`dataset:shop.public.sales%2Ev2`. A slug is lowercase words of letters and digits joined by single
hyphens, at most 63 characters, so it needs no encoding.

A slug is a short business name such as `ec-sales`, never a definitional attribute such as tax
treatment, because a slug stays as issued. The proposing Generator, which for these kinds always
calls a model, is given the slugs in use and avoids them. The Semantic Catalog guarantees uniqueness
at acceptance, inside the serialized write, by appending a sequence number.

Sameness is decided by rules per kind, never from URNs. Meaning is fuzzy, so mechanical decisions
are limited to cases that are easy to identify:

- **Datasets and Fields** are the same element when their physical-catalog-reference Facts that are neither
  rejected nor retired point to the same physical object. A renamed table or column is another
  physical object and so a new element; a URN built from the old name would otherwise misname it,
  and names cannot tell a rename from a drop and an add anyway. A column dropped and re-added under
  the same name is the same Field, because observations cannot tell them apart.
- **Concepts, Measures, Dimensions, and business terms** are the same element only when the proposer
  points to the existing one, never because their slugs match. Two concepts later found to denote
  the same object are linked by a concept-relationship edge, which a deciding party or a document
  must support. A correction that contradicts what an element's slug itself says is proposed as a
  new element with a retirement proposal for the old one; any other correction replaces values on
  the same element.
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
| `evaluate-policy` | One Fact | Policy version, what is evaluated (confirmation or retirement), result (machine judgement or request consent), how it was made (inline evaluation or the policy evaluation model), deciding party, reason; for a judgement by the policy evaluation model, its version and, when it requests consent, the review material it built |
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
| `confirmed` | Adopted as knowledge to use | `accept`, `propose-retire`, `evaluate-policy`, `answer`, `retire` |
| `rejected` | Judged not to be adopted | `accept` with a new dependency reference (back to `candidate`), `answer` (no change) |
| `retired` | Adopted knowledge judged no longer usable | Same as `rejected` |

Confirmation means adoption, not proof. A Fact is confirmed when the deciding party consents, or
when machine judgement under the user policy the organization chose adopts it. Either way the
checks behind it are signals (see
[Validation results and dependencies](#validation-results-and-dependencies)), and its confirmation
path shows which kind of decision adopted it.

Transitions need the events they rest on:

- `confirm` requires every premise dependency to be `confirmed`, and either a latest policy
  evaluation that chose machine judgement under the user policy version in force or a consent
  answer.
- `retire` requires an open retirement proposal with a machine-judgement evaluation under the
  version in force or a consent answer, or the retirement of a premise dependency.
- A retirement proposal against a `candidate` rejects it instead.

A retirement proposal ends with retirement, rejection, or a rejection or correction answer. An
unknown answer does not end it, but the proposal is not presented again until a new dependency
reference arrives. A consent to a candidate whose premise dependencies are not all confirmed is
recorded without a transition and takes effect in the commit that confirms the last of them, as an
adoption pending its premises does; a rejected or retired premise instead rejects the candidate by
cascade.

A candidate's **current judgement** starts with the acceptance that made it a candidate, the first
one or one that returned it from `rejected` or `retired`. Policy evaluations and answers made
earlier belong to an earlier judgement, and none of them can be the basis of a transition. An
additional acceptance does not start a new judgement, but the policy is evaluated again after it,
and a consent given before it no longer stands, because it may bring what changes the judgement. A
candidate is evaluated for confirmation once every premise dependency is `confirmed` or is a
candidate whose latest evaluation requested consent under the version in force, so a candidate and the premises it waits on can
reach the deciding party together. The confirmation itself still waits until every premise is
`confirmed`. A confirmation, and a rejection resting on an answer, rest only on an evaluation or
answer of the current judgement made after the current judgement of every premise dependency began;
a premise that has since returned to `candidate` starts a new judgement of its own, and its
dependents are evaluated again. A rejection resting on a retirement proposal needs a proposal made
within the current judgement. A policy evaluation, unlike an answer, stands only while the user
policy version it names is in force; setting another version sends the candidates and retirement
proposals it judged back to policy evaluation (see
[`construction.md`](construction.md#choosing-the-confirmation-path)).

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

A **validation result** records the checks made on a candidate. A check is a signal, not proof. It
holds its validation method, the validation items it examined, a result of supported, refuted, or
undecidable, a reference to the input it was checked against (an Input Observation or a Fact
Revision), and its basis. A `document` check quotes the relevant source text. A `data-check`
references a data observation, which records when it was read and whether it read the whole table or
a sample, and states whether its result rests on a counterexample found or on none found, because
data can refute a rule but not establish one. An `inference` check records the basis the model
stated, because otherwise nothing about an inference survives for the deciding party to judge; it
may have no reference.

Even checks that all support a value show only that the evidence points to it. Data shows what held
when it was read, usually in a sample; a declaration shows what the source states now; a document or
a model's reading can be wrong. No predicate has a set of validation items known in advance to be
sufficient for its value to hold, so no range is computed that would call the rest of a value
confirmed. Where the parts of a value are known, its **validation items** name them. For most
predicates the item is the value itself. An identifier correspondence's existence has one item per
pair of corresponding Fields, per normalization step, and per intermediate step of its matching
condition, and a Measure's aggregation rule has the aggregate function and the value expression.
Schema interpretation writes a pair's item as `{"pair": [<from Field URN>, <to Field URN>]}`, and
the business definition it proposes is text naming the tables and columns of the correspondence.
Items that no supporting check examined are shown as information, never as the measure of whether a
Fact holds.

| Validation method | Meaning |
| --- | --- |
| `declared-enforced` | Declared and enforced by the source: tables and columns, types, nullability, enforced constraints |
| `declared-unenforced` | Declared but not enforced by the source |
| `data-check` | A deterministic check of an observation of the data |
| `document` | The relevant part of a document the semantics owner registered |
| `inference` | The Generator's inference, including from names or a model's prior knowledge |

`declared-enforced` and `data-check` are the **deterministic validation methods**; a user policy
version can add `declared-unenforced`. They let machine judgement adopt a candidate without a model
(see [`construction.md`](construction.md#choosing-the-confirmation-path)). A model's prior knowledge
and name-based inference are the weakest signals: they can produce candidates, and their checks are
always `inference`. A human answer is never part of a validation result. Inputs read, validation
results, and human consent are recorded separately and never substitute for one another.

A candidate answers three separate questions:

| Question | Record | Content |
| --- | --- | --- |
| How was it produced? | Provenance | Generator and version, build task, and the inputs read |
| What signals bear on it? | Validation result | Each check's method, items, result, reference, and basis |
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
