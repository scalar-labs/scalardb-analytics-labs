# Generators and generation strategy

A Generator holds one interpretation or check rule and produces candidates, with validation results,
from what it reads. The generation strategy decides what each run aims at and why construction
stops. This document explains the contract every Generator follows, the Generators the design runs,
and how runs are ordered and bounded. Acceptance and confirmation of their output are explained in
[`construction.md`](construction.md).

Some values here are **initial behavior**: starting points such as search limits and candidate
counts that are expected to be tuned from use of the product.

## One rule per Generator

The schema-interpretation Generators are implemented. **[Planned]** The data-check and
meaning-inference Generators are not.

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
| Schema interpretation | Correspondence from foreign key | Existence of an identifier correspondence and its business definition; **[Planned]** support for the containment of the referencing side in its set relationship |
| Schema interpretation | Value kind from source type | Time-or-date and boolean value kinds |
| Data checks | Uniqueness check | Uniqueness of a Field set |
| Data checks | Candidate key discovery | Uniqueness, and a key for a Dataset without a primary key |
| Data checks | Inclusion discovery | Existence of an undeclared identifier correspondence |
| Data checks | Set relationship check | An identifier correspondence's set relationship |
| Meaning inference | Row meaning inference | The four row-meaning predicates |
| Meaning inference | Field meaning inference | Meaning, value kind, unit, meaning of null, represented concept, time meaning and time zone |
| Meaning inference | Concept inference | Concepts, their meaning, and concept relationships with multiplicity |
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
declarations. A commit that records a new observation or a confirmation creates, in the same
transaction, a run of each Generator that reads it, or adds the commit's log record as a trigger to
the run of that Generator not started yet. **[Planned]** Document and usage-feedback inputs have no
Generator yet.

| Input kind | Creates build tasks when |
| --- | --- |
| Physical schema | Registering an observation records a new physical schema observation |
| Document | Registering an observation records a new document observation |
| Usage feedback | Registering an observation records it under a new Input Resource |
| Confirmed Fact, per predicate | A Fact of that predicate is confirmed |
| Data observation | Never; data observations are written with the results that read them |

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

**[Planned]** No implemented Generator reads answers or proposes new nodes yet. An answer is an
input read, referenced by its log record. It is neither a dependency, because it
never changes, nor a check reference, so a deciding party's wording never counts as support for a
new candidate. A candidate made from a correction is therefore never adopted inline, and the
original correction can be traced and shown from its inputs read.

A Generator that proposes a new Concept, Measure, Dimension, or business term gives it a slug, a
short business name from which its URN is built, and is given the slugs in use so it avoids them
(see [`record-model.md`](record-model.md#identity-and-urns)). These Generators always call a
model, so the slug is the model's choice.

When a candidate targets a Fact Address that has a selected Fact, the Generator reads that Fact and
depends on it, so its candidate replaces the selection once confirmed. A later run keeps that dependency
after the replaced Fact is retired, so producing again does not change the candidate.

**Data observations.** **[Planned]** A Generator that reads data values, through data checks or as statistics
passed to a model, does so through the application's SQL execution. It records what it read as a
**data observation** in the same commit as its results, because a `data-check` needs an observation
to reference. Data observations have one Input Resource per kind and target of check (a Field set or
a correspondence), because one check can read several tables and different queries in one Input
Resource could not be compared with their predecessors. An observation holds the query, its result,
the range it read (a sample or the whole table, how many rows, and when), the physical schema
observations of the tables read, and the read provenance the application returns. The interim
scanner path returns no read provenance, so an observation read through it records that there is
none, together with the path's implementation and the names and versions of its DuckDB extensions. A
result equal to the previous observation of the same Input Resource is not recorded again.

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

**[Planned]** A foreign key on non-null columns also declares that every referencing value appears on the
referenced side, which is one validation item of the set relationship, so the Generator supports
that item, once the set relationship check proposes a set relationship to support, with the key's validation method. The set relationship check decides the other direction,
whether every referenced row is referenced, and schema interpretation never decides whether the
referencing side repeats; the uniqueness check observes that. With both, an enforced key whose
referenced side has unreferenced rows yields `subset` by machine judgement under `auto`.

Under the default policy, existence, Physical Catalog references, value kinds, enforced constraints, and
correspondences from enforced foreign keys are adopted inline. Uniqueness from an unenforced
declaration is adopted inline once the uniqueness check supports the same Fact. Keys and
correspondences from unenforced declarations go to the policy evaluation model, because the absence
of duplicates does not show that a set is a business identifier.

## Data checks

**[Planned]** This section describes design that is not implemented yet.

Data checks run aggregate queries, counts and set comparisons only, and never read data rows.

| Generator | Triggered by | Query | Method |
| --- | --- | --- | --- |
| Uniqueness check | Confirmed Field existence (declared sets containing it), confirmed key, confirmed correspondence existence (the Field set at each end) | Row count, distinct count of the set, rows with null | `data-check` |
| Candidate key discovery | Confirmed Field existence in a Dataset without a primary key | Minimal unique column combinations | `data-check` for uniqueness, `inference` for the key |
| Inclusion discovery | Confirmed Field existence, confirmed uniqueness | Whether one side's values are contained in a unique Field set | `inference`; the inclusion observation is an input read, not a check reference |
| Set relationship check | Confirmed identifier correspondence existence | Anti-join row counts on the matching condition, after its normalization, both directions | `data-check` |

How many rows match across a correspondence comes from the uniqueness check on each end: a member ID
that repeats among purchase rows makes customers to purchases one-to-many. Whether every row takes
part comes from the set relationship check.

**Sampling.** Every read of data values, by a data check or for statistics passed to a model, is
bounded by a sample size the domain's settings fix, because scanning whole large tables is not
practical. A table within the bound is read whole. The observation records which happened, how many
rows were read, and when. A comparison between two sides samples only the side whose values are
checked. Each sampled value is looked up in the whole of the other side, because a value missing
from a sample of the other side would look like a counterexample that is not one. A query that
exceeds the application's limits on rows, bytes, or time leaves its items undecidable.

**What data establishes.** Data can refute a rule but not establish one, and a sample can do even
less than a whole table:

| Result | Example | Rests on | Under `auto` |
| --- | --- | --- | --- |
| Loosens what the data may hold | A Field set repeats; some values on one side have no match | A counterexample, which a sample shows as surely as a whole table as long as the other side is looked up whole | Adopted inline |
| Restricts what the data may hold | A Field set does not repeat; every value on one side has a match | No counterexample found | Adopted inline only when a declaration also supports it; otherwise the policy evaluation model decides, with the range read in view |

[`construction.md`](construction.md#choosing-the-confirmation-path) gives the rule and its reason.

Each also runs on a new physical schema observation of its table and re-examines the confirmed Facts
it supported. Data that changes while the schema stays the same is not re-examined. A uniqueness
that has since broken stays selected, and a query relying on it can double-count. The Query Agent
can see from dependencies when the data was observed and how much of it was read.

When uniqueness declaration ingestion and the uniqueness check reach the same value, or inclusion
discovery and correspondence inference propose the same correspondence, the second becomes an
additional acceptance. A result that disagrees with a candidate is a refuting check, and a new
observation that refutes a confirmed Fact produces a retirement proposal with the reason that the
confirmation was wrong.

The column-set sizes that candidate key discovery and inclusion discovery explore, and the sample
size, are initial behavior.

Inclusion never supports a correspondence's existence with `data-check`, because columns whose
values happen to overlap need not denote the same objects; otherwise `auto` could confirm a
coincidental correspondence. For the same reason, keys from candidate key discovery are `inference`.

## Meaning inference

**[Planned]** This section describes design that is not implemented yet.

Meaning inference uses a model for predicates that declarations and data cannot decide. For a target
element it passes names and types, confirmed Facts, relevant document parts, feedback, and permitted
data statistics, and asks for predicate values with what supports them. An item that cites a
registered document is `document`, with a quotation the Generator verifies against the document;
anything else is `inference`, with the basis the model stated. Neither is deterministic, so no
meaning-inference candidate is adopted inline; under `auto` the policy evaluation model decides
each.

| Generator | Triggered by |
| --- | --- |
| Row meaning inference | Confirmed Dataset existence, documents, feedback without related Facts |
| Field meaning inference | Confirmed Field existence, documents, feedback without related Facts |
| Concept inference | Confirmed represented concepts of rows and Fields, documents, feedback without related Facts |
| Analytical semantics inference | Documents, feedback without related Facts, confirmed Field meaning |
| Identifier correspondence inference | Confirmed Field meaning and represented concept, documents, feedback without related Facts |
| Correction interpretation | Feedback with related Facts, including corrections from review |

All of them also run when a Fact they depend on stops being selected. They create a free-description
predicate only when the input states something the closed core cannot express.

Concept inference stays separate from row meaning inference although both name concepts. Row meaning
reads one Dataset, while the relationships between concepts, including the multiplicity the business
allows, need the confirmed row meanings of several. Fewer model calls come from passing several
targets in one call, not from merging rules.

Apart from correction interpretation, meaning inference does not propose uniqueness or set
relationships, which data checks decide. It also does not propose a Fact
Address that schema interpretation is filling from a current declaration, or the key of a Dataset
without a primary key, which candidate key discovery proposes. An inferred candidate beside a
checked one would add judgement and, if both were confirmed, a conflict. Correction interpretation
can reach any predicate, because a correction may revise any value.

Undeclared identifier correspondences are central to combining sources. Identifier correspondence
inference proposes, for example, that e-commerce customers and POS members correspond through the
e-mail address given at registration, compared after lower-casing because the two systems store it
in different case. Once its existence is confirmed, the set relationship check examines it. Its
business definition cannot be checked against data and is judged as a separate candidate.

Currency, code meanings, tax treatment, and the posting date of sales rarely have support in data or
declarations. Without documents, meaning inference can offer only `inference`, and the deciding
party's judgement becomes the main source: a tax treatment that only a column name suggests is a
signal too weak for the policy evaluation model to adopt alone.

Correction interpretation gives the model the feedback's content and context and the related Facts'
content, and asks for a revised value or applicability scope for the same element and predicate. Its
candidate always depends on the feedback observation. If the related Fact was a rejected candidate,
the new candidate does not depend on it, since a rejected candidate is nothing to replace, but it
carries over the dependencies it still needs, such as the correspondence's existence. If the related
Fact is the selected confirmed Fact, the new candidate depends on it and replaces it once confirmed.
When the wording yields no value or scope, the build task records that and the unreadable
correction.

## Generation strategy

What concerns deterministic Generators is implemented: order through declared inputs, targets from
observations and confirmations, and producing for every target. **[Planned]** The rest, including
the conditions and bounds for non-deterministic Generators, is not.

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
represented concepts, documents, and feedback. Measures, Dimensions, and business terms come from
documents and feedback. Datasets and Fields come only from table and column ingestion. Creating
Measures from confirmed Facts alone would let each confirmation trigger another inference and grow
elements without bound.

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
**[Planned]** Meaning inference, impact judgement, and the duplicate check call it the same way. A
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
model, and how long one answer may take as settings. **[Planned]** Which adapter and model a domain
uses is set in the workspace configuration, and the application passes it to the Semantic Catalog.

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

An unavailable model fails the build task, which can be resumed. So do a model that gives no answer
in time and an answer not in the form the caller asked for: the policy evaluation model fails rather
than reading such an answer as a consent request, so a model that misreads its instructions is
noticed.

## Input passed to external models

**[Planned]** This section describes design that is not implemented yet.

The policy evaluation model's subject holds Fact content and what the candidate's Generator recorded
as read; it expands no observation content. The settings below apply wherever observation content is
passed to a model, that subject included.

Meaning inference, impact judgement, the duplicate check, and the policy evaluation model may send
input to an external model through the adapter. Usage feedback and data values reach a model only as
far as the domain's settings allow:

| Input | Content | Default |
| --- | --- | --- |
| Physical schema | Table and column names, source types, constraints | Passed |
| Confirmed Facts | Values and scopes of selected Fact Revisions | Passed |
| Documents | Relevant parts | Passed |
| Data count statistics | Row count, null ratio, distinct count, from a bounded sample | Passed; can be stopped |
| Value distributions and samples | Length and shape distributions, some distinct values | Not passed unless the domain opts in |
| Usage feedback | Content, session purpose, context summary, related Facts | Passed; can be stopped |

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
