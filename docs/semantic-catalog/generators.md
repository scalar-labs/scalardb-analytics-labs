# Generators and generation strategy

A Generator holds one interpretation or check rule and produces candidates, with validation results,
from what it reads. The generation strategy decides what each run aims at and why construction
stops. This document explains the contract every Generator follows, the Generators the design runs,
and how runs are ordered and bounded. Acceptance and confirmation of their output are explained in
[`construction.md`](construction.md).

Some values here are **initial behavior**: starting points such as search limits and candidate
counts that are expected to be tuned from use of the product.

## One rule per Generator

The schema-interpretation and data-check Generators, row and Field meaning inference, concept
inference, analytical semantics inference for Measures and business terms, identifier
correspondence inference, and correction interpretation are implemented. Analytical semantics
inference also proposes calendar and classification Dimensions and a Measure's default time
granularity. **[Planned]** Other kinds of Dimension are not defined yet.

Each interpretation or check rule is its own Generator. The rule that derives a key from a primary
key and the rule that derives an identifier correspondence from a foreign key are separate
Generators.
[`../design/semantic-catalog-generator-granularity.md`](../design/semantic-catalog-generator-granularity.md)
records why.

Generators fall into three **input routes** by what they mainly read. The routes differ in how they
read and in the conditions for passing input to an external model. Whether `auto` adopts a
candidate inline depends on the validation methods of its checks, never on its route.

| Route | Reads | Determinism | Main validation methods |
| --- | --- | --- | --- |
| Schema interpretation | Physical schema observations | Deterministic | `declared-enforced`, `declared-unenforced` |
| Data checks | Aggregate queries through the application's SQL execution | Deterministic | `data-check` |
| Meaning inference | Physical schema, confirmed Facts, documents, usage feedback, and data statistics passed to a model | Non-deterministic | `document`, `inference` |

| Route | Generator | Produces |
| --- | --- | --- |
| Schema interpretation | Table and column ingestion | Existence and Physical Catalog references of Datasets and Fields; retirement proposals for elements that disappeared |
| Schema interpretation | Key from primary key | A Dataset's key |
| Schema interpretation | Uniqueness declaration ingestion | Uniqueness of a Field set |
| Schema interpretation | Correspondence from foreign key | Existence of an identifier correspondence and its business definition; support for the containment of the referencing side in its set relationship |
| Schema interpretation | Value kind from source type | Time-or-date and boolean value kinds |
| Data checks | Uniqueness check | Uniqueness of a Field set |
| Data checks | Candidate key discovery | Uniqueness, and a key for a Dataset without a primary key |
| Data checks | Inclusion discovery | Existence of an undeclared identifier correspondence |
| Data checks | Set relationship check | An identifier correspondence's set relationship |
| Meaning inference | Row meaning inference | Row kind, row grain, and row inclusion |
| Meaning inference | Field meaning inference | Meaning, value kind, unit, meaning of null, time meaning and time zone |
| Meaning inference | Concept inference | Concepts, their meaning, the concepts rows and Fields represent, and concept relationships with multiplicity |
| Meaning inference | Analytical semantics inference | Measures, Dimensions, business terms, and their predicates |
| Meaning inference | Identifier correspondence inference | Undeclared identifier correspondences, including normalization, and their business definitions |
| Meaning inference | Correction interpretation | A revised value or applicability scope for a related Fact |

Further Generators can be added under the same contract. Each addition needs its own input kind or
predicate to be defined first. One such form applies an explicit derivation rule to confirmed Fact
Revisions to propose other Facts. The rule is then an input whose changes are tracked, so revising
or withdrawing it identifies what to rerun. Being able to follow a relationship in the graph is
never by itself a basis for derivation.

## The Generator contract

**Input kinds.** Each Generator declares what it may read, and build tasks are created from these
declarations. A commit that records a new observation, a confirmation, or an acceptance creates, in
the same transaction, a run of each Generator that reads it, or adds the commit's log record as a
trigger to the run of that Generator not started yet. Concept inference, analytical semantics
inference, and identifier correspondence inference declare document input. **[Planned]** No
Generator declares usage feedback without related Facts yet, so that feedback creates no build
tasks. Row and Field meaning inference read the domain's current documents when their other inputs
start them.

| Input kind | Creates build tasks when |
| --- | --- |
| Physical schema | Registering an observation records a new physical schema observation |
| Document | Registering an observation records a new document observation |
| Usage feedback with related Facts | Registering an observation records it under a new Input Resource |
| Usage feedback without related Facts | **[Planned]** Registering an observation records it under a new Input Resource |
| Confirmed Fact, per predicate | A Fact of that predicate is confirmed |
| Accepted Fact, per predicate | An acceptance of a Fact of that predicate is recorded |
| Data observation | Never; data observations are written with the results that read them |

The uniqueness predicates, which each name a Field set, are declared together as one predicate. An
accepted Fact lets a Generator add its support to a candidate another Generator proposed: correspondence
from foreign key supports a set relationship only once the set relationship check has proposed one
(see [Schema interpretation](#schema-interpretation)). Its own acceptance triggers it again, which
records nothing because the acceptance is unchanged.

Derived records are not an input kind. A dependency can point only to an Input Observation or a Fact
Revision, so a candidate built from a derived record could not track changes. Schema interpretation
reads the table metadata of the latest version received, which the derived record still holds,
as the source-independent form of what the observed source metadata declares, and depends on the
latest Input Observation; see [Schema interpretation](#schema-interpretation).

**Validation capability.** Each Generator declares which validation items of which predicates it can
examine with which validation methods; the tables in this document are those declarations. A
Generator being deterministic is separate from its methods being deterministic. Inclusion discovery
always gives the same output for the same input, yet its candidates are `inference`.

**Version.** Provenance records the Generator and its version. Any configuration change that can
change output for the same input makes a new version, and for model-based Generators the version
includes the model and the prompt. Facts produced under an earlier model can therefore be found from
provenance.

**Output and dependencies.** For each candidate, a Generator returns what it read in two groups:

- Input Observations and the Fact Revisions of selected confirmed Facts are inputs read and also
  dependency references. A dependency on a Fact names the Fact Revision of its latest acceptance,
  which later evaluations and confirmations leave unchanged, so producing again from the same input
  states the same dependencies.
- Rejected or retired Facts, and related Facts of feedback that are not selected, are inputs read
  only. As a premise they would make the candidate unconfirmable and reject it by cascade, and as a
  replacement dependency they would give it nothing presentable to replace. A replacement
  dependency stated while the replaced Fact was confirmed is the exception: it is kept after that
  Fact is retired, so producing again does not change a candidate already judged.

A deciding party's wording is never support for a new candidate. Correction interpretation reads a
correction through the usage feedback it registers: the feedback's observation is an input read and
a dependency, but never a check reference. A candidate made from a correction is therefore never
adopted inline, and the original correction can be traced and shown from its inputs read.
Analytical semantics inference proposes new nodes: Measures, calendar and classification
Dimensions, and business terms. **[Planned]** No
implemented Generator reads answers yet. An answer is an
input read, referenced by its log record. It is neither a dependency, because it never changes, nor
a check reference.

A Generator that proposes a new Concept, Measure, Dimension, or business term gives it a slug, a
short business name from which its URN is built, and is given the slugs in use so it avoids them
(see [`record-model.md`](record-model.md#identity-and-urns)). These Generators always call a
model, so the slug is the model's choice.

When a candidate targets a Fact Address that has a selected Fact, the Generator reads that Fact and
depends on it, so its candidate replaces the selection once confirmed. A later run keeps that dependency
after the replaced Fact is retired, so producing again does not change the candidate.

**Several Generators on one Fact.** Each acceptance states the whole validation result and
dependency set in effect after it (see
[`record-model.md`](record-model.md#validation-results-and-dependencies)). A Generator that accepts
a Fact another Generator also supports therefore restates the other's checks and dependencies from
the Fact's latest acceptance and replaces only its own. Schema interpretation owns the declaration
checks and the dependencies on physical schema observations; a data check owns the checks that name
it and the dependencies on its own data observations. Otherwise a data check's acceptance would drop
the declaration that lets uniqueness from an unenforced constraint be adopted inline, and a later
schema observation would drop the data check again.

**Data observations.** A Generator that reads data values does so through the application's SQL
execution: the data checks do, and so do the Generators that pass statistics to a model. It records
what
it read as a **data observation** in the same commit as its results, because a `data-check` needs
an observation to reference. Data observations have one Input Resource per kind and target of check
(a Field set or a correspondence), because one check can read several tables and different queries
in one Input Resource could not be compared with their predecessors. An observation holds the
queries, their results, the range each read (a sample or the whole table, how many rows, and when),
the physical schema observations of the tables read, and the read provenance the application
returns. A result equal to the previous observation of the same Input Resource, apart from when it
was read, is not recorded again; the results refer to the previous observation instead.

A data-reading run holds no transaction while its queries run. It plans its queries from the latest
commit, runs them outside any transaction, and interprets the reads inside the transaction that
records its results, so what it proposes rests on the state it is recorded on; a collision repeats
only that last step, with the same reads (see [`storage.md`](storage.md#running-build-tasks)).

## Schema interpretation

Schema interpretation maps what the source declares to candidates. It reads the source-independent
table metadata the connector derived, never a connector's own source metadata, so it interprets
every connector's tables the same way; source metadata, whose shape each connector defines, is read
by meaning inference. It names each Dataset and Field by the table's Physical Catalog reference,
which the application passes with the table's first registration; a table without one is observed
but not interpreted, and the run records the reference as missing input. Each candidate depends on
the table's latest physical schema observation and on the existence Facts it rests on, and its
checks reference that observation. Table and column ingestion runs
on each new physical schema observation. The others also declare the confirmed existence of their
target element as input, so any element they point to exists. When the declaration a Fact came from
disappears, they propose retiring the Fact with a `declared-enforced` check, unless a `data-check`
also supports it, since what the data confirmed does not change when a declaration goes. A table
that a foreign key references and that is registered later also triggers them. Items the table
metadata marks as missing are not treated as disappeared.

| Generator | Based on | Validation method |
| --- | --- | --- |
| Table and column ingestion | Tables or views and columns, and elements now marked "does not exist" | `declared-enforced` |
| Key from primary key | Primary key | `declared-enforced` if the source enforces it, else `declared-unenforced` |
| Uniqueness declaration ingestion | Primary keys; unique constraints and unique indexes on non-null columns | Same; a unique index is `declared-enforced` |
| Correspondence from foreign key | Foreign key | Same, except as noted for string columns |
| Value kind from source type | Date, time, and boolean types | `declared-enforced` |

When the table metadata does not say whether the source enforces a constraint, it is
`declared-unenforced`. Treating an enforced constraint as unenforced costs only a data check, while
the opposite could confirm a duplicated key from its declaration alone. A unique constraint on
nullable columns yields no uniqueness candidate, because rows containing null may repeat. Numeric
and string types cannot tell identifiers from amounts, so their value kinds come from Field meaning
inference.

A foreign key states that the referencing values denote the referenced rows' objects, so it is
support for an identifier correspondence rather than an element of its own. When the source enforces
the key, the correspondence holds within one database by construction, and the Generator also
proposes its business definition with `declared-enforced`. An unenforced key gives both
`declared-unenforced`. A foreign key on string columns leaves the matching condition's validation
item undecidable unless equality under the column's collation is known to match byte comparison,
because a key can reference a row that an equality comparison does not match. Collation is not an
item of table metadata yet, so every pair of text columns is undecidable. Such correspondences are
never adopted inline.

A foreign key on non-null columns also declares that every referencing value appears on the
referenced side, which is one validation item of the set relationship. Once the set relationship
check proposes a set relationship that claims it (`equal` or `subset`), the Generator, which reads
accepted set relationships, supports that item with the key's validation method, undecidable on the
same terms as the key's pairs. The set relationship check decides the other direction, whether every
referenced row is referenced, and schema interpretation never decides whether the referencing side
repeats; the uniqueness check observes that. With both, an enforced key whose referenced side has
unreferenced rows yields `subset` by machine judgement under `auto`.

Under the default policy, existence, Physical Catalog references, value kinds, enforced constraints, and
correspondences from enforced foreign keys are adopted inline. Uniqueness from an unenforced
declaration is adopted inline once the uniqueness check supports the same Fact. Keys and
correspondences from unenforced declarations go to the policy evaluation model, because the absence
of duplicates does not show that a set is a business identifier.

## Data checks

Data checks run aggregate queries, counts and set comparisons only, and never read data rows. No
data value they read is passed to a model; the counts they record in their checks are, as part of
a candidate the policy evaluation model evaluates.

| Generator | Triggered by | Query | Method |
| --- | --- | --- | --- |
| Uniqueness check | Confirmed Field existence (declared sets containing it), confirmed key, confirmed correspondence existence (the Field set at each end) | Row count, rows with null in the set, and values of the set that repeat, by grouping on the set | `data-check` |
| Candidate key discovery | Confirmed Field existence in a Dataset without a primary key | Minimal unique column combinations | `data-check` for uniqueness, `inference` for the key |
| Inclusion discovery | Confirmed Field existence, confirmed uniqueness | Whether one side's values are contained in a unique Field set | `inference`; the inclusion observation is an input read, not a check reference |
| Set relationship check | Confirmed identifier correspondence existence | Anti-join row counts on the matching condition, after its normalization, both directions | `data-check` |

How many rows match across a correspondence comes from the uniqueness check on each end: a member ID
that repeats among purchase rows makes customers to purchases one-to-many. Whether every row takes
part comes from the set relationship check.

**Uniqueness check.** A repeating value of the set is a counterexample and proposes `repeats`. No
repetition proposes `does-not-repeat` as an absence. More than one row with null in the set, or no
rows read, decides nothing, because rows containing null cannot be compared.

**Candidate key discovery.** It explores a table whose metadata reports its constraints and no
primary key; nothing is concluded from constraints the metadata does not report. It explores every
single column and every pair of columns, once each column has a Field whose existence is
confirmed, in two statements so that a pair statement over a limit leaves the single columns
decided. A set is unique when no row read has null in it and no value repeats; a pair counts only
when neither of its columns is unique alone. Each minimal set gets a uniqueness candidate, and the
smallest ones, the single columns if any, each get a key candidate. A set whose value repeats
refutes the uniqueness Facts already proposed for it.

**Inclusion discovery.** It explores single Fields against single-Field sets confirmed not to
repeat, when both columns have the same mapped Analytics type, the type is not a boolean, date,
time, or interval, and the correspondence does not exist yet. The Fields of one table paired with
one unique set are checked in one statement that reads the table once, and one run plans at most
100 such statements, an initial bound; the pairs past it are recorded as missing input, because
otherwise the queries of one run would grow with the square of the catalog and hold the other build
tasks behind them. When every value read of a Field is found, it proposes the correspondence's
existence from the contained Field to the unique one.

**Set relationship check.** It examines a direct correspondence, one whose matching condition pairs
the Fields of its two ends, and leaves unexamined a condition with a normalization step the Query
Engine has no form for (Unicode NFKC). Rows whose end contains null take no part. The value follows
from whether every value of each end is matched and whether any is: `equal`, `subset`, `superset`,
`partial-overlap`, or `disjoint`. An unmatched value or a match found is a counterexample; that
every value of an end is matched, or that none is, is an absence.

**Sampling.** Every read of data values, by a data check or for statistics passed to a model, is
bounded by a sample size the domain's settings fix, because scanning whole large tables is not
practical. A table within the bound is read whole. The observation records which happened, how many
rows were read, and when. A comparison between two sides samples only the side whose values are
checked. Each sampled value is looked up in the whole of the other side, because a value missing
from a sample of the other side would look like a counterexample that is not one. When both sides
are in one table, the table is read whole for the same reason. A query that exceeds the
application's limits on rows, bytes, or time leaves its items undecidable: the observation records
the limit, and the uniqueness check and the set relationship check give each candidate at the
target an undecidable check. A statement the application rejects or that fails examines nothing of
its target, which the run records as missing input while it records its other targets.

**What data establishes.** Data can refute a rule but not establish one, and a sample can do even
less than a whole table:

| Result | Example | Rests on | Under `auto` |
| --- | --- | --- | --- |
| Loosens what the data may hold | A Field set repeats; some values on one side have no match | A counterexample, which a sample shows as surely as a whole table as long as the other side is looked up whole | Adopted inline |
| Restricts what the data may hold | A Field set does not repeat; every value on one side has a match | No counterexample found | Adopted inline only when a declaration also supports it; otherwise the policy evaluation model decides, with the range read in view |

[`construction.md`](construction.md#choosing-the-confirmation-path) gives the rule and its reason.

The uniqueness check and the set relationship check also run on a new physical schema observation of
a table they read and examine again what they examined there, a correspondence whichever of its ends
the table holds; the uniqueness check also examines the sets the table declares. A column or table
the latest observation no longer has is not read, even while its Field's retirement waits. The discovery Generators run only on new confirmations. Data that changes
while the schema stays the same is not re-examined. A uniqueness
that has since broken stays selected, and a query relying on it can double-count. The Query Agent
can see from dependencies when the data was observed and how much of it was read.

When uniqueness declaration ingestion and the uniqueness check reach the same value, or correspondence
from foreign key states a correspondence inclusion discovery proposed, the second becomes an
additional acceptance. Inclusion discovery and identifier correspondence inference propose nothing
for a correspondence that already exists, so whichever comes second adds no support to it. A result that disagrees with a candidate is a refuting check, and a new
observation that refutes a confirmed Fact produces a retirement proposal with the reason that the
confirmation was wrong.

The column-set sizes that candidate key discovery and inclusion discovery explore, and the sample
size, are initial behavior. Candidate key discovery explores sets of one and two columns, and
inclusion discovery single Fields against single-Field unique sets. Larger sets multiply the
queries or the aggregates of one query by the combinations of columns, so they wait until use shows
that composite keys left undiscovered matter.

Inclusion never supports a correspondence's existence with `data-check`, because columns whose
values happen to overlap need not denote the same objects; otherwise `auto` could confirm a
coincidental correspondence. For the same reason, keys from candidate key discovery are `inference`.

## Meaning inference

Row and Field meaning inference, with documents as input and `document` checks, concept inference
and identifier correspondence inference, with the trigger of documents, analytical semantics
inference for Measures and business terms, triggered by documents and confirmed Field meaning and,
for a missing name, by a confirmed business term, and correction interpretation are implemented.
Analytical semantics inference proposes calendar and classification Dimensions with Measures, and
a Measure's default time granularity may refer to a calendar it proposes in the same run.
**[Planned]** Other kinds of Dimension, and the trigger of feedback without related Facts, are not.

Meaning inference uses a model for predicates that declarations and data cannot decide. For a target
element it passes names and types, the table's source metadata with the source metadata schema it
was written against, confirmed Facts, relevant document parts, feedback, and permitted data
statistics, and asks for predicate values with what supports them. An item that cites a
registered document is `document`, with a quotation the Generator verifies against the document;
anything else is `inference`, with the basis the model stated. Neither is deterministic, so no
meaning-inference candidate is adopted inline; under `auto` the policy evaluation model decides
each.

| Generator | Triggered by |
| --- | --- |
| Row meaning inference | Confirmed Dataset existence, documents, feedback without related Facts |
| Field meaning inference | Confirmed Field existence, documents, feedback without related Facts |
| Concept inference | Confirmed row kind, Field meaning, and `identifier` value kind, documents, feedback without related Facts |
| Analytical semantics inference | Documents, feedback without related Facts, confirmed Field meaning, confirmed business-term existence and name (for a missing name) |
| Identifier correspondence inference | Confirmed represented concept of a Field, confirmed concept relationship, documents, feedback without related Facts |
| Correction interpretation | Feedback with related Facts, including corrections from review |

All of them also run when a Fact they depend on stops being selected. They create a free-description
predicate only when the input states something the closed core cannot express.

The concept a row or a Field represents refers to a Concept element, so concept inference, which
creates Concepts, also proposes these references; row and Field meaning inference do not. Deciding
that two tables' rows or two Fields stand for the same business concept, such as e-commerce
customers and POS members, needs several Datasets in view at once, and Concepts created by
several Generators would duplicate one another. The relationships between concepts, including the
multiplicity the business allows, likewise need the confirmed meanings of several Datasets. Fewer
model calls come from passing several targets in one call, not from merging rules.

Concept inference targets a Dataset's row concept once its row kind is confirmed, and a Field's
represented concept once its meaning is confirmed. Whether a Field identifies an object is judged
from how the business uses its values, not from whether it is a key: an e-mail address, a phone
number, or a membership card number identifies the customer even when the table has a surrogate key,
while a name or a birth date only describes one. Identifier correspondence inference compares only
Fields with a confirmed represented concept, so this judgement decides which matches it can find. An
inferred value kind may be confirmed only after the meaning, so the confirmation of an `identifier`
value kind also triggers a run for the Field's Dataset; another value kind does not. One call
passes the targets of several Datasets with their confirmed Facts, the Concepts in use (candidates and confirmed ones) with their meaning,
the relationships between them, every slug taken, and the relevant document parts. The model refers
to a Concept in use or declares a new one with a slug, so registering the POS database after the
e-commerce one refers members to the customer concept the customers table already has. A new
Concept is created only when a row, a Field, or a relationship refers to it; a slug declared again in
a later call of the same run refers to the Concept the earlier call created, and a slug already
taken, even by a rejected Concept, creates nothing. Calls of one run cannot see one another's new
Concepts otherwise, so two calls may still create two Concepts for one business object; the
duplicate check is expected to catch them. A reference to a Concept depends on the Concept's
existence, and a relationship on the existence of both ends.

Apart from correction interpretation, meaning inference does not propose uniqueness or set
relationships, which data checks decide. It also does not propose a Fact
Address that schema interpretation is filling from a current declaration, or the key of a Dataset
without a primary key, which candidate key discovery proposes. An inferred candidate beside a
checked one would add judgement and, if both were confirmed, a conflict. Correction interpretation
can reach any predicate, because a correction may revise any value.

Each predicate whose value a model reads or writes has one value rule: the form its value takes,
how a value a model gives is read into the value stored and its identity key, and whether the
value names other elements. The model-backed Generators that propose a predicate and correction
interpretation read values through the same rule, so a corrected value is read exactly as a
proposed one. A key, uniqueness, and a set relationship, which schema interpretation and data
checks build without a model, have rules in the same form for correction interpretation. Each
caller still decides which elements a value may name, since only it knows what its call passed or
created. A calendar's kind, rule, and hierarchy, a classification's rule, classified Field, and
hierarchy, and a Measure's default time granularity have rules too, since analytical semantics
inference proposes them. Predicates no Generator proposes
yet, such as those of a Dimension kind not defined yet, get their rule with the Generator that
proposes them; until then no Fact of them exists to correct.

Undeclared identifier correspondences are central to combining sources. Identifier correspondence
inference proposes, for example, that e-commerce customers and POS members correspond through the
e-mail address given at registration, compared after lower-casing because the two systems store it
in different case. Once its existence is confirmed, the set relationship check examines it. Its
business definition cannot be checked against data and is judged as a separate candidate.

Fields stand side by side when they represent the same object: the Fields whose represented concept
is confirmed are grouped by Concept, and Concepts a confirmed `same-object` relationship links form
one group. One call passes a group's Fields with the confirmed Facts of them and their Datasets, the
correspondences already among them, and the relevant document parts; a group within one Dataset
asks nothing, and the Fields past 50 in one group, an initial bound, are recorded as missing input.
The model answers pairs of Fields from one Dataset to another, each side with normalization steps
from the closed list, the support for the existence, and a business definition. The existence
depends on each Field's existence and represented concept, on the `same-object` relationships of
the group when its ends stand for different Concepts, and on the documents read; its check covers
every pair and normalization step. A pair naming a Field the call did not pass, a step not on the
list, or ends that are not the Fields of one Dataset each in two different Datasets is recorded as
missing input. Nothing is proposed for a correspondence whose existence, in the same or the reverse
direction, is a candidate, confirmed, or rejected, since the reverse states the same objects.

Only direct correspondences are inferred, and only between Fields whose represented concept is
confirmed. These are initial behavior: a correspondence through a mapping table, or one whose
composite identifier has a column that represents no concept, waits until use shows it matters.

Currency, code meanings, tax treatment, and the posting date of sales rarely have support in data or
declarations. Without documents, meaning inference can offer only `inference`, and the deciding
party's judgement becomes the main source: a tax treatment that only a column name suggests is a
signal too weak for the policy evaluation model to adopt alone.

Analytical semantics inference reads the domain's documents, the confirmed Datasets with their
confirmed Fields and Field meanings, the Measures, Dimensions, and business terms that are neither
rejected nor retired with their values, and the slugs in use. It runs only when documents exist and a Dataset is
confirmed. The model either points to an existing element, which gets values only at its open Fact
Addresses, or proposes a new one under a slug not in use; an answer whose new slug is not one fails
the task. A Measure's aggregation rule and filter conditions are parsed as acceptance parses them
and run once through the application's SQL execution on a sample of the base table before anything
is recorded. A new Measure is proposed only with a base Dataset and an aggregation rule that passed
both; otherwise the build task records it as missing. A new Dimension is proposed only with a
defined kind, its rule's wording, and a hierarchy; anything else is recorded as missing. A calendar
level's expression is parsed as acceptance parses it and run on a sample of a column whose value
kind is confirmed as time or date; one that fails is dropped and recorded as missing, and its level
stays. With no such column, expressions are only parsed. A classification names its classified
Field as `<table ref>.<column>` of a confirmed column of the input. Its level expressions are
parsed as acceptance parses them over that column's table and run grouped by the class they give
on a sample of it; one that fails, or one given without a classified Field, is dropped and
recorded as missing, and its level stays, as does a level whose expression only references a
column, such as prefectures below regions. A classification whose every level expression only
references a column groups by that column's own values and is not proposed; the model is also
told not to propose one. A default time granularity names a calendar that stands or is proposed
in the same answer, which is why calendars are recorded before Measures. Each candidate depends on
the documents the call read and on the existence of its element; a Measure's expression values,
reference time Field, and default time granularity, a Dimension's rule, classified Field, and
hierarchy, and a business term's referenced element also depend on what they refer to: a
granularity and a Dimension's rule, classified Field, and hierarchy on the Dimension's kind, a
classified Field on its Field's existence, and a classification hierarchy with expressions on the
classified Field. The existence and the aggregation rule of a new Measure therefore reach the
deciding party together. A confirmed business term that
has no selected name and no pending candidate in some declared language gets a separate call that
asks only for those names and needs neither a document nor a confirmed Dataset (see
[`languages.md`](languages.md#names-one-fact-per-language)).

Correction interpretation gives the model the feedback's content and context and the related Facts'
content, and asks for a revised value or applicability scope for the same element and predicate. Its
candidate always depends on the feedback observation. If the related Fact was a rejected candidate,
the new candidate does not depend on it, since a rejected candidate is nothing to replace, but it
carries over the dependencies it still needs, such as the correspondence's existence: the Facts it
rested on at other Fact Addresses that are neither rejected nor retired. Its dependencies on inputs
stay behind, because the revision rests on the deciding party's wording rather than on what produced
the corrected value. If the related Fact is the selected confirmed Fact, the new candidate depends
on it and replaces it once confirmed. A revised value goes to the related Fact's Fact Address and
depends on every confirmed Fact there; a revised scope goes to a new Fact Address with that scope's
description. When the wording yields no value or scope, the build task records that and the
unreadable correction.

The call states each related Fact's value form. A value that names other elements, such as the
Concept a Field represents, a key's Fields, or a Measure's base Dataset, is shown by name with the
names a revision may choose from: the Concepts whose existence stands, the Dataset's columns whose
Field is confirmed, or the confirmed Datasets. The revision depends on the existence of what it
names instead of what the corrected value named; a name not listed, such as a new Concept, creates
nothing and is recorded as missing input. A default time granularity is shown with its calendar's
name and chooses among the calendars that stand, with their levels; it depends on the chosen
calendar's kind rather than its existence, as acceptance requires. A classified Field is shown as
`<table ref>.<column>` and chooses among the confirmed columns of the confirmed Datasets. A revised
hierarchy's level expressions are parsed as acceptance parses them in the form of the Dimension's
kind; a classification's rest on its classified Field that stands and read only the confirmed
Fields of its table. A revision with one that would be refused is
not proposed and is recorded as missing input, unlike analytical semantics inference, which keeps
the level without the expression, because a revision states the whole hierarchy the wording asks
for. A revised aggregation rule or filter condition is parsed
over its base Dataset's Fields and run once on a sample of the base table with the Measure's other
expression, as analytical semantics inference does, and is recorded as missing input when either
fails. A revised name keeps its language. A correction of an element's existence or of a Physical
Catalog reference is recorded on the build task as not revised: a correction of an existence
rejects the element or names another one, which a revised value cannot state, and a Physical
Catalog reference follows from what the source declares. **[Planned]** A revised scope for a
confirmed Fact, which a candidate at another Fact Address cannot replace, and a correction of a
Fact at a scoped Fact Address are recorded on the build task as not interpreted yet.

## Generation strategy

What concerns deterministic Generators is implemented: order through declared inputs, targets from
observations and confirmations, and producing for every target. The conditions and bounds for
non-deterministic Generators are implemented for row and Field meaning inference, concept
inference, and identifier correspondence inference, which confirmations trigger, for analytical
semantics inference, which documents, confirmed Field meaning, and a confirmed business term's
existence or name trigger, and for correction interpretation, which feedback with related Facts
triggers. Concept inference and identifier
correspondence inference also run on a new document observation. **[Planned]** The other triggers
of non-deterministic Generators are not: feedback without related Facts, a Fact no longer selected,
an impact judgement, and a rejection, retirement, or cascade.

**Order.** The strategy defines no global order. Order emerges because a later Generator declares as
input the confirmation of what an earlier one produces. When the e-commerce database is registered:

1. The physical schema observation triggers table and column ingestion, whose existence and
   physical-catalog-reference candidates are adopted inline.
2. Confirmed existence triggers key from primary key, uniqueness declaration ingestion,
   correspondence from foreign key, the uniqueness check, inclusion discovery, and row and Field
   meaning inference.
3. If the table metadata does not say whether the primary key is enforced, the uniqueness check
   supports the declared uniqueness with `data-check` and it is adopted inline, while the key goes
   to the policy evaluation model.
4. Row and Field meaning candidates go to the policy evaluation model, which adopts them or sends
   them to the deciding party.

Registering the POS database repeats this, after which identifier correspondence inference proposes
the customer-to-member correspondence. Declaring confirmed existence as input avoids producing
candidates that could not reach the deciding party anyway.

**Targets of a run.** A run aims at the Fact Addresses its triggers point to:

| Trigger | Targets |
| --- | --- |
| An observation, a confirmed Fact, or an accepted Fact | The Generator's predicates for the elements involved |
| Feedback with related Facts | Fact Addresses with a related Fact's element and predicate, in the scope the feedback indicates, that the model chooses to address; only correction interpretation produces for them, so one correction yields one candidate and remains in provenance |
| A Fact no longer selected | The Fact Addresses of Facts that depended on it |
| An impact judgement | The Fact Addresses of Facts selected for re-examination |
| A rejection, retirement, or cascade | That Fact's Fact Address |
| A document | For concept inference, the confirmed Datasets the document names by table or column, matched as the relevant parts of documents are, with their Fields; for identifier correspondence inference, the groups with a Field of such a Dataset |
| Feedback without related Facts | **[Planned]** The Generator's predicates for elements the model selects from the content |

A deterministic Generator produces for every target. Identical acceptances record nothing, so
producing again costs no records. It outputs a previously rejected value again only when the
declaration or check target behind it changed, so an unrelated column addition does not resend a
rejected candidate.

A non-deterministic Generator produces only for targets that meet all of these conditions:

- No Fact is selected there, or the trigger concerns the selected Fact.
- No `candidate` is pending there.
- The latest candidate was not answered unknown, or a depended-on Input Resource has a newer
  observation since.

A candidate answered unknown stays a `candidate`, so row and Field meaning inference treat it as
pending. **[Planned]** Producing again for it once a depended-on input has a newer observation needs
the observation triggers that are not implemented yet.

Meaning inference runs only when it has a target and only with input that meets the handling
conditions below. Input that does not is left out and recorded as missing.

Meaning inference creates new elements only from specific inputs. Concepts come from confirmed row
and Field meaning, documents, and feedback. Measures, Dimensions, and business terms come from
documents and feedback. Datasets and Fields come only from table and column ingestion. Creating
Measures from confirmed Facts alone would let each confirmation trigger another inference and grow
elements without bound.

**Why construction stops.** Build tasks arise only from observations, confirmations, answers,
changes in selection, impact judgements, and policy versions. Deterministic Generators reproduce
identical output from identical input, and identical acceptances and data observations are not
recorded. Without new input, construction therefore produces no new records. Non-deterministic
Generators are bounded by initial behavior:

- One candidate per Fact Address per run.
- One model call asks about at most 25 targets, those of one table for row and Field meaning
  inference and of several Datasets for concept inference, so a large catalog becomes several calls
  a model can answer in time rather than one that fails each time it is resumed.
- Analytical semantics inference makes one model call per run over every confirmed Dataset, with
  the documents' sections in document order up to 20,000 characters; the rest is recorded as
  missing, because table and column names do not tell which glossary sections define a Measure.
  A run that a confirmed business term starts makes at most one further call, for every missing
  name of the terms its confirmations are about, with the same document sections.
- After a rejection, as opposed to a correction, at most one further candidate with another value
  until a depended-on input gets a newer observation.
- One candidate per correction.

Starting with one candidate at a time is deliberate. Whether comparable options help or burden the
deciding party varies with data and person.

## Column changes

A column that disappears from a physical schema observation is retired, and a column that appears
is a new Field, whether or not the two are a rename:

| Observation | Candidates | Path under the default policy |
| --- | --- | --- |
| A column disappeared | Retirement of that Field's existence (`declared-enforced`) | Adopted inline |
| A column appeared | Existence and Physical Catalog reference of a new Field (`declared-enforced`) | Adopted inline |

Names cannot tell a rename from a drop and an add, and an element's URN is built from its name, so
no Generator judges renames. The retirement cascades to the Facts that rest on the old Field, and
**[Planned]** their Generators propose them again on the new premise (see
[`construction.md`](construction.md#following-input-changes)). No predicate represents a rename,
and a Physical Catalog reference never moves; a renamed table likewise becomes a new Dataset.

## Reaching a language model

The policy evaluation model (see
[`construction.md`](construction.md#choosing-the-confirmation-path)) calls a language model through
an **adapter**, the only part of the Semantic Catalog that knows how a model is reached.
The implemented meaning-inference Generators and translation build tasks (see
[`languages.md`](languages.md#translations)) call it the same way. **[Planned]** So will the
other meaning-inference Generators, impact judgement, and the duplicate check. A
caller passes a prompt and receives the model's answer; it never depends on a particular provider,
so the provider can change without changing a Generator.

The first adapters run a locally installed, already signed-in coding-agent CLI (`claude -p` or
`codex exec`) as a subprocess in an empty temporary directory, so the CLI picks up no repository
context, with the tools and the user's customizations turned off as far as the CLI's options allow.
A third adapter pipes the prompt to a configured command, to connect another harness. The adapters
are interim; the later phase below replaces the CLIs.

- `claude -p` runs with no tools, in safe mode, and with no settings files or MCP servers, so it
  reads no `CLAUDE.md`, memory, skills, plugins, or hooks. The CLI still adds a short note of its
  environment, such as the working directory and the signed-in account.
- `codex exec` runs in a read-only sandbox, ignoring the user's `config.toml` and execpolicy rules,
  with every tool its feature flags can remove removed and no skills, plugins, apps, sub-agents,
  memories, or web search. Codex has no option that removes every tool or skips its home directory's
  instructions, so `apply_patch` and the code-mode `exec` tool stay declared, kept from writing or
  running anything by the sandbox and the disabled code-mode host, and `AGENTS.md` in the Codex
  home directory still reaches the model.

The subprocess inherits the process environment, which is how a CLI finds its own sign-in,
so the product holds and passes no provider credential. The Semantic Catalog takes the adapter, the
model, and how long one answer may take as settings. Which adapter and model a domain uses is set
in the workspace configuration, and the application passes it to the Semantic Catalog when it runs
build tasks.

A caller's version includes the model, so an adapter is configured with a concrete model and reports
it. A model alias that the provider may point elsewhere later, such as `sonnet`, is refused, because
provenance could then not say which model produced a Fact. The `claude` adapter refuses bare
aliases, accepting only model IDs that start with `claude-` and do not end in `-latest`; it cannot
tell other moving IDs from fixed ones, so the configured ID is recorded as given. The `codex` adapter
requires a model and uses it as given, and the command adapter requires the identity of the model
its command reaches, which the product cannot learn from the command.

An adapter adds no wording of its own: a caller gives its instructions and its input, and the
instructions, given as the system prompt where the CLI has one, are the whole fixed part of what the
model reads. The policy evaluation model's version is therefore the adapter's identity and its
instructions, named and hashed, so changing either makes a new version.

**[Planned]** After the first release, a later phase moves from coding-agent CLIs to a harness the
product owns, such as one built on Pi or on a DeepSeek harness. The adapter boundary is what keeps
that change away from the Generators.

Analytical semantics inference records each business-term name in the language the model says it
is written in, among the domain's declared languages (see [`languages.md`](languages.md)).
Every caller instructs the model to write its descriptions in the domain's recording language, its
primary language (see [`languages.md`](languages.md#descriptions-one-recording-language)). The
language travels in each call's input as `recordingLanguage`, and the fixed instructions tell the
model to write in the language it names, so the instructions, and with them a caller's version, are
the same in every domain.

An unavailable model fails the build task, which can be resumed. So do a model that gives no answer
in time and an answer not in the form the caller asked for: the policy evaluation model fails rather
than reading such an answer as a consent request, so a model that misreads its instructions is
noticed.

## Input passed to external models

Row and Field meaning inference apply the settings for data count statistics and value samples, pass
the relevant parts of documents, and record what they pass as inputs read. Analytical semantics
inference passes no data values; it passes documents and the confirmed Facts it reads, and records
them as inputs read. Concept inference reads
no data values; besides confirmed Facts and documents, it passes the Concepts in use and their
relationships, candidates included, because a Concept another call proposed must be visible to be
referred to rather than created again.

The source metadata schema is the one the latest derived record names, so the model reads each
document with the schema it was written against. A candidate records the schema's URN as read but
does not depend on it: a schema describes the shape of source metadata, so a new one leaves what was
inferred from a document standing, and no Generator reads schemas as an input kind. Reads of a Fact
do not expand the schema's URN, so the schema is not repeated with every Fact inferred from it. **[Planned]** Length and
shape distributions, the usage feedback setting, and turning data checks off are not implemented.

The policy evaluation model's subject holds Fact content and what the candidate's Generator recorded
as read; it expands no observation content. The settings below apply wherever observation content is
passed to a model, that subject included.

Meaning inference, impact judgement, the duplicate check, the policy evaluation model, and
translation may send input to an external model through the adapter. Translation sends only the
recorded text of the records a reader asked to have translated, which includes usage feedback's
content and context summary when it is asked for, so the usage feedback setting applies to it too. Usage feedback and data values reach a model only as
far as the domain's settings allow:

| Input | Content | Default |
| --- | --- | --- |
| Physical schema | Table and column names, source types, constraints, the connector's source metadata as the connector wrote it, and the source metadata schema the connector publishes | Passed |
| Confirmed Facts | Values and scopes of selected Fact Revisions | Passed |
| Documents | Relevant parts | Passed |
| Data count statistics | Row count, null ratio, distinct count, from a bounded sample | Passed; can be stopped |
| Value distributions and samples | Length and shape distributions, some distinct values (up to five per column, each cut to 100 characters) | Not passed unless the domain opts in |
| Usage feedback | Content, session purpose, context summary, related Facts | Passed; can be stopped |

The relevant parts of documents are found without a model. A document is split into sections at its
Markdown headings. When all the domain's documents fit within a bound on the text one model call
reads, they are passed whole. Otherwise only the sections that name the table the call asks about or
one of its columns are passed, as far as the bound allows, and the documents with parts left out are
recorded as missing input. Each passed document's observation is an input read and a dependency of
the call's candidates. A quotation counts as found when it appears in the cited observation's text
with runs of white space treated as one space; a quotation that does not, or one citing a document
the call did not read, leaves the value an `inference` with the basis the model stated. A read of
the candidate, including the policy evaluation model's subject, shows the document by name only; the
quotation is the part of the document the candidate rests on.

Value distributions and samples are withheld by default because values that must not leave the
organization could reach an external model before the organization decides, and even shape
distributions can reveal values with fixed formats. The cost of withholding them is weaker meaning
candidates for coded columns.

Data checks also read data values but send nothing to a model. They are on by default, within the
sample size and the application's limits on time and concurrency, and a domain can turn them off.
Uniqueness that no declaration states, and set relationships beyond what foreign keys declare, then
stay unknown, and the Query Agent receives them as missing knowledge.

These settings belong to the domain and are kept in the workspace configuration. Input is still
registered and kept in the log; the Semantic Catalog applies the settings where it hands input to a
Generator, and input withheld is recorded as missing.
Everything passed to a model is recorded as inputs read, so provenance shows what an external model
saw; that record is the audit. No retention or disclosure control beyond it exists yet.
