# Generators and generation strategy

A Generator holds one interpretation or check rule and produces candidates, with validation results,
from what it reads. The generation strategy decides what each run aims at and why construction
stops. This document explains the contract every Generator follows, the Generators the design runs,
and how runs are ordered and bounded. Acceptance and confirmation of their output are explained in
[`construction.md`](construction.md).

Some values here are **initial behavior**: starting points such as search limits and candidate
counts that are expected to be tuned from use of the product.

## One rule per Generator

Each interpretation or check rule is its own Generator. The rule that derives a key from a primary
key and the rule that derives a join from a foreign key are separate Generators.
[`../design/semantic-catalog-generator-granularity.md`](../design/semantic-catalog-generator-granularity.md)
records why.

Generators fall into three **input routes** by what they mainly read. The routes differ in how they
read and in the conditions for passing input to an external model. Whether `auto` can confirm a
candidate depends on the validation methods of its checks, never on its route.

| Route | Reads | Determinism | Main validation methods |
| --- | --- | --- | --- |
| Schema interpretation | Physical schema observations | Deterministic | `declared-enforced`, `declared-unenforced` |
| Data checks | Aggregate queries through the application's SQL execution | Deterministic | `data-check` |
| Meaning inference | Physical schema, confirmed Facts, documents, missing knowledge, feedback, and data statistics passed to a model | Non-deterministic | `document`, `inference` |

| Route | Generator | Produces |
| --- | --- | --- |
| Schema interpretation | Table and column ingestion | Existence and technical references of Datasets and Fields; retirement proposals for elements that disappeared |
| Schema interpretation | Key from primary key | A Dataset's key |
| Schema interpretation | Uniqueness declaration ingestion | Uniqueness of a Field set |
| Schema interpretation | Join from foreign key | Existence of a join |
| Schema interpretation | Participation from foreign key | Participation of the referencing side |
| Schema interpretation | Value kind from source type | Time-or-date and boolean value kinds |
| Schema interpretation | Column rename inference | Moving a technical reference to a new column; after rejection, retirement of the old Field and a Field for the new column |
| Data checks | Uniqueness check | Uniqueness of a Field set |
| Data checks | Candidate key discovery | Uniqueness, and a key for a Dataset without a primary key |
| Data checks | Inclusion discovery | Existence of an undeclared join |
| Data checks | Participation check | Participation of each side of a join |
| Data checks | Multiplicity check | A join's multiplicity |
| Data checks | Set relationship check | An identifier correspondence's set relationship |
| Meaning inference | Row meaning inference | The four row-meaning predicates |
| Meaning inference | Field meaning inference | Meaning, value kind, unit, meaning of null, represented concept, time meaning and time zone |
| Meaning inference | Concept inference | Concepts, their meaning, and concept relationships with multiplicity |
| Meaning inference | Analytical semantics inference | Measures, Dimensions, business terms, and their predicates |
| Meaning inference | Join and identifier correspondence inference | Undeclared joins; identifier correspondences and their business definitions |
| Meaning inference | Correction interpretation | A revised value or applicability scope for a related Fact |

Further Generators can be added under the same contract. Each addition needs its own input kind or
predicate to be defined first. One such form applies an explicit derivation rule to confirmed Fact
Revisions to propose other Facts. The rule is then an input whose changes are tracked, so revising
or withdrawing it identifies what to rerun. Being able to follow a relationship in the graph is
never by itself a basis for derivation.

## The Generator contract

**Input kinds.** Each Generator declares what it may read, and build tasks are created from these
declarations:

| Input kind | Creates build tasks when |
| --- | --- |
| Physical schema | Registering an observation records a new physical schema observation |
| Document | Registering an observation records a new document observation |
| Missing knowledge at question time | Registering an observation records it under the question URN |
| Usage feedback | Registering an observation records it under a new Input Resource |
| Confirmed Fact, per predicate | A Fact of that predicate is confirmed |
| Data observation | Never; data observations are written with the results that read them |

Derived records are not an input kind. A dependency can point only to an Input Observation or a Fact
Revision, so a candidate built from a derived record could not track changes.

**Validation capability.** Each Generator declares which validation items of which predicates it can
examine with which validation methods; the tables in this document are those declarations. A
Generator being deterministic is separate from its methods being deterministic. Column rename
inference and inclusion discovery always give the same output for the same input, yet their
candidates are `inference`.

**Version.** Provenance records the Generator and its version. Any configuration change that can
change output for the same input makes a new version, and for model-based Generators the version
includes the model and the prompt. Facts produced under an earlier model can therefore be found from
provenance.

**Output and dependencies.** For each candidate, a Generator returns what it read in two groups:

- Input Observations and the Fact Revisions of selected confirmed Facts are inputs read and also
  dependency references.
- Rejected or retired Facts, and related Facts of feedback that are not selected, are inputs read
  only. As dependencies they would make the candidate unconfirmable and reject it by cascade.

An answer is an input read, referenced by its log record. It is neither a dependency, because it
never changes, nor a check reference, so a deciding party's wording never counts as support for a
new candidate. A candidate made from a correction therefore needs consent under `auto`, and the
original correction can be traced and shown from its inputs read.

When a candidate targets a Fact Address that has a selected Fact, the Generator reads that Fact and
depends on it, so its candidate replaces the selection once confirmed.

**Data observations.** A Generator that reads data values, through data checks or as statistics
passed to a model, does so through the application's SQL execution. It records what it read as a
**data observation** in the same commit as its results, because a `data-check` needs an observation
to reference. Data observations have one Input Resource per kind and target of check (a Field set, a
join, or a correspondence), because one check can read several tables and different queries in one
Input Resource could not be compared with their predecessors. An observation holds the query, its
result, the physical schema observations of the tables read, and the read provenance the application
returns. A result equal to the previous observation of the same Input Resource is not recorded
again.

## Schema interpretation

Schema interpretation maps what the source declares to candidates. Table and column ingestion runs
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
| Join from foreign key | Foreign key | Same, except as noted for string columns |
| Participation from foreign key | An enforced foreign key on non-null columns | `declared-enforced` |
| Value kind from source type | Date, time, and boolean types | `declared-enforced` |
| Column rename inference | A column disappeared and one of the same source type was added to the table | `inference` for the move; `declared-enforced` for the retirement and new Field |

When the table metadata does not say whether the source enforces a constraint, it is
`declared-unenforced`. Treating an enforced constraint as unenforced costs only a data check, while
the opposite could confirm a duplicated key from its declaration alone. A unique constraint on
nullable columns yields no uniqueness candidate, because rows containing null may repeat. Numeric
and string types cannot tell identifiers from amounts, so their value kinds come from Field meaning
inference.

A foreign key on string columns leaves the join condition's validation item undecidable unless
equality under the column's collation is known to match byte comparison, because a key can reference
a row that an equality join does not match. Such joins need consent under `auto`.

Schema interpretation never produces multiplicity. A foreign key shows at most one referenced row
per referencing row, not whether the referencing side repeats; the multiplicity check decides it.

Under the default policy, existence, technical references, value kinds, and enforced constraints are
confirmed by machine judgement. Uniqueness from an unenforced declaration is confirmed once the
uniqueness check supports the same Fact. Keys and joins from unenforced declarations need consent,
because the absence of duplicates does not show that a set is a business identifier.

## Data checks

Data checks run aggregate queries, counts and set comparisons only, and never read data rows.

| Generator | Triggered by | Query | Method |
| --- | --- | --- | --- |
| Uniqueness check | Confirmed Field existence (declared sets containing it), confirmed key | Row count, distinct count of the set, rows with null | `data-check` |
| Candidate key discovery | Confirmed Field existence in a Dataset without a primary key | Minimal unique column combinations | `data-check` for uniqueness, `inference` for the key |
| Inclusion discovery | Confirmed Field existence, confirmed uniqueness | Whether one side's values are contained in a unique Field set | `inference`; the inclusion observation is an input read, not a check reference |
| Participation check | Confirmed join existence | Anti-join row counts on the condition, both sides | `data-check` |
| Multiplicity check | Confirmed join existence | Uniqueness of the join Fields on both sides | `data-check` |
| Set relationship check | Confirmed identifier correspondence existence | Anti-join row counts on the matching condition, both directions | `data-check` |

Each also runs on a new physical schema observation of its table and re-examines the confirmed Facts
it supported. Data that changes while the schema stays the same is not re-examined. A uniqueness
that has since broken stays selected, and a join on the duplicated key can double-count. The Query
Agent can see from dependencies when the data was observed.

When uniqueness declaration ingestion and the uniqueness check reach the same value, or inclusion
discovery and join inference propose the same join, the second becomes an additional acceptance. A
result that disagrees with a candidate is a refuting check, and a new observation that refutes a
confirmed Fact produces a retirement proposal with the reason that the confirmation was wrong.

A query that exceeds the application's limits on rows, bytes, or time leaves its items undecidable,
so a partial read never supports a whole table. The column-set sizes that candidate key discovery
and inclusion discovery explore are initial behavior.

Inclusion never supports a join's existence with `data-check`, because columns whose values happen
to overlap need not correspond; otherwise `auto` could confirm a coincidental join. For the same
reason, keys from candidate key discovery are `inference`.

## Meaning inference

Meaning inference uses a model for predicates that declarations and data cannot decide. For a target
element it passes names and types, confirmed Facts, relevant document parts, feedback, and permitted
data statistics, and asks for predicate values with what supports them. An item that cites a
registered document is `document`, with a quotation the Generator verifies against the document;
anything else is `inference`. Neither is deterministic, so every meaning-inference candidate needs
consent under `auto`.

| Generator | Triggered by |
| --- | --- |
| Row meaning inference | Confirmed Dataset existence, documents, missing knowledge, feedback without related Facts |
| Field meaning inference | Confirmed Field existence, documents, missing knowledge, feedback without related Facts |
| Concept inference | Confirmed represented concepts of rows and Fields, documents, missing knowledge, feedback without related Facts |
| Analytical semantics inference | Documents, missing knowledge, feedback without related Facts, confirmed Field meaning |
| Join and identifier correspondence inference | Confirmed Field meaning and represented concept, documents, missing knowledge, feedback without related Facts |
| Correction interpretation | Feedback with related Facts, including corrections from review |

All of them also run when a Fact they depend on stops being selected. They create a free-description
predicate only when the input states something the closed core cannot express.

Apart from correction interpretation, meaning inference does not propose uniqueness, multiplicity,
participation, or set relationships, which data checks decide. It also does not propose a Fact
Address that schema interpretation is filling from a current declaration, or the key of a Dataset
without a primary key, which candidate key discovery proposes. An inferred candidate beside a
checked one would add judgement and, if both were confirmed, a conflict. Correction interpretation
can reach any predicate, because a correction may revise any value.

Undeclared joins and identifier correspondences are central to combining sources. Join and
identifier correspondence inference proposes, for example, that e-commerce customers and POS members
correspond through the email address given at registration. Once its existence is confirmed, the set
relationship check examines it. Its business definition cannot be checked against data and needs
consent as a separate candidate.

Currency, code meanings, tax treatment, and the posting date of sales rarely have support in data or
declarations. Without documents, meaning inference can offer only `inference`, and the deciding
party's judgement becomes the main source.

Correction interpretation gives the model the feedback's content and context and the related Facts'
content, and asks for a revised value or applicability scope for the same element and predicate. Its
candidate always depends on the feedback observation. If the related Fact was a rejected candidate,
the new candidate does not depend on it, since that would reject it by cascade, but it carries over
the dependencies it still needs, such as the join's existence. If the related Fact is the selected
confirmed Fact, the new candidate depends on it and replaces it once confirmed. When the wording
yields no value or scope, the build task records that and the unreadable correction.

## Generation strategy

**Order.** The strategy defines no global order. Order emerges because a later Generator declares as
input the confirmation of what an earlier one produces. When the e-commerce database is registered:

1. The physical schema observation triggers table and column ingestion, whose existence and
   technical-reference candidates are confirmed by machine judgement.
2. Confirmed existence triggers key from primary key, uniqueness declaration ingestion, join from
   foreign key, the uniqueness check, inclusion discovery, and row and Field meaning inference.
3. If the table metadata does not say whether the primary key is enforced, the uniqueness check
   supports the declared uniqueness with `data-check` and it is confirmed, while the key waits for
   consent.
4. Row and Field meaning candidates wait for consent.

Registering the POS database repeats this, after which join and identifier correspondence inference
proposes the customer-to-member correspondence. Declaring confirmed existence as input avoids
producing candidates that could not reach the deciding party anyway.

**Targets of a run.** A run aims at the Fact Addresses its triggers point to:

| Trigger | Targets |
| --- | --- |
| An observation or a confirmed Fact | The Generator's predicates for the elements involved |
| Feedback with related Facts | Fact Addresses with a related Fact's element and predicate, in the scope the feedback indicates, that the model chooses to address; only correction interpretation produces for them, so one correction yields one candidate and remains in provenance |
| A Fact no longer selected | The Fact Addresses of Facts that depended on it |
| An impact judgement | The Fact Addresses of Facts selected for re-examination |
| A rejection, retirement, or cascade | That Fact's Fact Address |
| A document or feedback without related Facts | The Generator's predicates for elements the model selects from the content |

A deterministic Generator produces for every target. Identical acceptances record nothing, so
producing again costs no records. It outputs a previously rejected value again only when the
declaration or check target behind it changed, so an unrelated column addition does not resend a
rejected candidate.

A non-deterministic Generator produces only for targets that meet all of these conditions:

- No Fact is selected there, or the trigger concerns the selected Fact.
- No `candidate` is pending there.
- The latest candidate was not answered unknown, or a depended-on Input Resource has a newer
  observation since.

Meaning inference runs only when it has a target and only with input that meets the handling
conditions below. Input that does not is left out and recorded as missing.

Meaning inference creates new elements only from specific inputs. Concepts come from confirmed
represented concepts, documents, missing knowledge, and feedback. Measures, Dimensions, and business
terms come from documents, missing knowledge, and feedback. Datasets and Fields come only from table
and column ingestion and column rename inference. Creating Measures from confirmed Facts alone would
let each confirmation trigger another inference and grow elements without bound.

**Why construction stops.** Build tasks arise only from observations, confirmations, answers,
changes in selection, impact judgements, and policy versions. Deterministic Generators reproduce
identical output from identical input, and identical acceptances and data observations are not
recorded. Without new input, construction therefore produces no new records. Non-deterministic
Generators are bounded by initial behavior:

- One candidate per Fact Address per run.
- After a rejection, as opposed to a correction, at most one further candidate with another value
  until a depended-on input gets a newer observation.
- One candidate per correction.

Starting with one candidate at a time is deliberate. Whether comparable options help or burden the
deciding party varies with data and person.

## Column changes

When a column disappears from a physical schema observation:

| Observation | Candidates | Path under the default policy |
| --- | --- | --- |
| A column disappeared | Retirement of that Field's existence (`declared-enforced`) | Machine judgement |
| A column disappeared and one of the same source type appeared in the table | No retirement and no new Field from ingestion; rename inference proposes moving the technical reference (`inference`) | Consent |
| That move was rejected | Rename inference proposes retiring the old Field and a Field for the new column | Machine judgement |

While the move waits, the old Field stays selected and the Query Agent sees that its input has a
newer observation. No predicate represents a rename; it is a replacement of the technical reference,
the same form a Dataset uses for a renamed table.

## Input passed to external models

Meaning inference, impact judgement, and the duplicate check may send input to an external model.
Using question text, trace records, feedback, or data values as input requires the conditions for
acquisition, purpose, retention, disclosure, and audit to hold.

| Input | Content | Initial behavior |
| --- | --- | --- |
| Physical schema | Table and column names, source types, constraints | Passed |
| Confirmed Facts | Values and scopes of selected Fact Revisions | Passed |
| Documents | Relevant parts | Passed |
| Data count statistics | Row count, null ratio, distinct count | Passed when the conditions hold |
| Value distributions and samples | Length and shape distributions, some distinct values | Not passed unless the user organization permits it and the conditions hold |
| Missing knowledge, usage feedback | The parts recorded as observations; feedback content, related Facts, and context | Passed when the conditions hold; the organization can stop it |

Value distributions and samples are withheld by default because values that must not leave the
organization could reach an external model before the organization decides, and even shape
distributions can reveal values with fixed formats. The cost of withholding them is weaker meaning
candidates for coded columns. Everything passed to a model is recorded as inputs read, so provenance
shows what an external model saw.
