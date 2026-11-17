# Semantic Catalog public API

The public API is the set of operations and reads the `semantic-catalog` module exposes to the
application. The application reaches it from the CLI, the Agent Skill, and later interfaces. The
Query Agent never calls it directly, but what it receives is shaped by these reads. This document
explains the structure of the API and the contract of each call. Java signatures, wire formats, and
presentation on user surfaces belong to the code and the application. What each operation writes is
explained in [`construction.md`](construction.md#operations-that-add-records).

## What is exposed

**[Planned]** Operations change the log, a build task, or a review presentation record. Reads change nothing.
Every call targets one domain, named by an identifier the application supplies and the Semantic
Catalog treats as opaque.

**[Planned]** The application calls these operations: registering an observation, submitting answers, resolving a
conflict, setting a user policy version, running build tasks, resuming a build task, and registering
a review presentation. Recording a Generator's results, evaluation by the policy evaluation model,
and policy re-evaluation run only inside a build task and are not exposed. The Semantic Catalog owns
Generator output and the versions that produced it, and policy evaluation is its own judgement with
nothing for the application to supply.

**[Planned]** Running build tasks is separate from the operations that create them, because the application
decides when they run. Every operation returns the build tasks it created or added a trigger to.
Running them may call back into capabilities the application provides: SQL execution with scope and
limits, name resolution from a Physical Catalog table to its current name, and re-retrieval of
table metadata.

**[Planned]** Operations on one domain are serializable; reads run concurrently with them and see only committed
commits.

## Common contract

**[Planned]** **URNs** pass as opaque strings. Callers never parse them. A caller passes only URNs obtained from
the API. Their text is built so a person can tell roughly what they name, but it is not a contract
and carries no knowledge; exact names come from Facts (see
[`record-model.md`](record-model.md#identity-and-urns)).

**[Planned]** **Points.** A read takes either a Catalog Snapshot URN, meaning that commit, or "latest", meaning
the last commit when the read is received. Every read returns the Catalog Snapshot URN it read, and
the same URN always returns the same knowledge. Every operation returns the Catalog Snapshot URN of
the commit it wrote, or says it wrote nothing, so a Query Agent can move to the point where
knowledge it just confirmed is visible.

**[Planned]** **Using party.** The application attaches the authenticated principal behind each call: a person,
or a Query Agent registered by an administrator. Operations record the using party as
the deciding party of answers and conflict resolutions and as the setter of policy versions. Reads
and operation outputs mask dependencies and check references that Governance denies the using party:
neither their URN nor their content is returned, only the fact that a hidden reference exists.
Withholding URNs keeps the existence and names of hidden objects from leaking, without making the
rest of the knowledge unusable.

**[Planned]** **Language.** A read takes, optionally, the language to read in, one of the domain's
declared languages (see [`languages.md`](languages.md#reading-in-a-language)). The application fills it
in from the using party's preferred language when the caller names none, and without either the
domain's primary language is used; a language the domain does not declare is invalid input. Every
text a read returns, in Fact values, scopes, evaluations, answers, and feedback, comes as its
original with the language it was written in. When that differs from the language read in, the
text comes with its translation, marked as a translation, or marked as pending when the translation
task has not run yet (see [`languages.md`](languages.md#translations)). A translation never
replaces the original, so a caller can always show which wording was judged. Business-term names
are not translated: each language's name is returned at its own Fact Address. Reading the domain's
languages returns the declared languages and the primary one, so an agent can map a reader's
request, such as "show it in English", to a language it can pass.

**[Planned]** **Authority check.** Submitting answers and resolving a conflict take a reference to the result
of checking the deciding party's authority. The value is opaque, recorded without interpretation,
and required to be present. The application issues it (see
[`../application.md`](../application.md#callers-and-authority)).

**[Planned]** **Call identifiers.** The application attaches a call identifier to every operation. A resend with
the same identifier and input writes nothing and returns the first output, so an operation whose
response was lost can be retried without applying it twice. The same identifier with a different
input is invalid input. The record of call identifiers is written in the same transaction as the
operation.

**[Planned]** **Fact Revision content** is the shape every read returns for one Fact Revision:

- **[Planned]** The Fact Revision, Fact, and Fact Address URNs, the value, the state at that version, and the
  applicability scope as a description plus entity URNs.
- **[Planned]** Dependency references. A Fact dependency is a Fact Revision URN, marked premise or replacement.
  An input dependency is an Input Resource URN with an Input Observation URN, plus, for a physical
  schema, the Physical Catalog table identifier and version that produced it.
- Inputs read and validation results. Each check carries its method, the items it examined, its
  result, its reference, and its basis. Validation items that no supporting check examined are
  listed as information; no range is reported that would call the rest of the value confirmed.
- **[Planned]** Provenance: Generator, version, and build task.
- **[Planned]** Confirmation path: inline machine judgement, the policy evaluation model with its version, or
  human consent; the event it rests on; and the policy version.
- **[Planned]** The deciding party when a person consented, the commit at which the Fact was last confirmed or
  checked, and whether references are masked.

Reads at a point add whether new candidates exist at the same Fact Address and whether any
depended-on input has a newer observation. Where a read returns Fact Revision content as material
for a judgement or for composing a query, the records behind it come with their content expanded:
for each input read, check reference, and dependency, its content; for a rejected or retired Fact
among them, its value and the answers given to it, with their wording and deciding party. A
corrected candidate's feedback dependency and the rejected Fact it corrected thus show what was
said and what it was said about. Expansion goes one level: the references of an expanded record
stay URNs, which a caller retrieves when it needs them, so a read stays bounded however long a
chain of corrections grows.

**[Planned]** **Errors.** A failed operation writes nothing, neither in the log nor outside it. A Generator
failing inside a build task is not an error; the task is returned as `failed`. Errors have a kind,
the identifier of their target, and a reason:

| Kind | When |
| --- | --- |
| Invalid input | **[Planned]** A required input is missing or malformed, the predicate is not valid for the element's kind, or a call identifier is reused with a different input |
| Target not found | **[Planned]** An input identifier points to no record |
| Point out of range | **[Planned]** A Catalog Snapshot URN names a commit not yet written |
| No authority check result | **[Planned]** An answer or conflict resolution lacks the reference |
| Not accepted in this state | **[Planned]** A state condition of the operation is not met, such as running build tasks while another process runs them |
| Storage failure | **[Planned]** Storage could not be read or written |

Registering an observation that records nothing because the content is unchanged is a success whose
output says so.

## Operations

### Registering an observation

**[Planned]** Registering an observation has two forms.

- *Table metadata*, passed by the application for each retrieved table, with the Physical Catalog
  table identifier and its current table metadata version. With it comes the table's Physical Catalog
  reference, its data-source registration, namespace, and table name, which the first registration
  of the table keeps on its Input Resource and by which schema interpretation names its Dataset and
  Fields; a table whose name path is not a namespace and a table name has none, and is observed but
  not interpreted. The whole record is passed exactly as
  the connector returned it, not the part the Physical Catalog stored: the connector's source
  metadata, the table metadata, the read time, whether the table was read completely or found not
  to exist, the retrieval information, and the content digest, with the source metadata schema the
  connector publishes and its digest. The source metadata is the
  observation; the rest is the version's derived record, which names the schema by its digest. A version no newer than the latest one
  already received for that table records nothing, so a retrieval that arrives late cannot roll the
  table back. A new version whose source metadata is unchanged records only a derived record. Each is a success, and the output says which was recorded, with the Input Resource
  and Input Observation URNs. The application passes unchanged tables at every retrieval, so a
  failure between the Physical Catalog's update and this call is repaired by the next retrieval.
- *Documents*, which the semantics owner wrote. The input is the Input Resource (an existing
  document's URN, or none for a new document) and the content: the document's name and text. Text
  equal to the document's latest observation records nothing. The output returns the Input Resource
  and the latest Input Observation URNs, whether anything was recorded, and the build tasks a new
  observation created for the Generators that read documents.
- **[Planned]** *Usage feedback*. The input is the content with its session purpose and a summary of
  the session's context, and can carry related Fact URNs; feedback is always a new Input Resource.
  The output returns the Input Resource and Input Observation URNs. Today feedback is registered
  only as the wording of a correction when answers are submitted.

### Submitting answers

**[Planned]** Submitting answers records one or more answers from one deciding party in one commit. Each answer
names the Fact, whether it answers a confirmation or a retirement proposal, the answer type, the
wording (with the given value or condition for a correction), and the review presentation answered.
The output gives, per answer, its event and resulting transition or the reason for none. The reason
is an unknown answer, a rejection or correction of a retirement proposal, unconfirmed dependencies,
or a judgement already made by another path. The output also lists the feedback registered for each
correction and the Facts moved by cascade. If any answer fails, the whole submission fails and
identifies it, so part of a submission is never recorded. A review presentation is registered first,
so answers can reference it.

### Resolving a conflict

**[Planned]** Resolving a conflict names the conflicting Fact Address, the chosen Fact, the passed-over Facts,
the authority-check reference, the wording, and the review presentation. It returns the
`resolve-conflict` record and the resulting selected Fact. If the eligible Facts changed through
replacement or retirement since the presentation, it is not accepted, and the deciding party reads
the new point before choosing.

### Setting a user policy version

**[Planned]** Setting a user policy version takes the preset, the treatment of `declared-unenforced`, and the
semantics owner's reason, and returns the version's log record identifier and the re-evaluation
build task. Beforehand, the application shows the semantics owner what the check below reports.

### Running build tasks and resuming a failed one

**[Planned]** Running build tasks takes the domain's builder semaphore (see
[`storage.md`](storage.md#writes-and-reads)): while one call holds it, another is not accepted. It first returns every task left `running` to `created`, since a stopped run wrote
nothing, then runs `created` tasks one at a time, including those created while it runs. It reports
each task as it finishes, `succeeded` or `failed`, with its result, the missing inputs when there
was no candidate, or the reason for failure. It returns when no `created` task remains or, if the
caller asked to keep waiting, when the caller stops it. A Generator run, an evaluation by the policy
evaluation model, or a re-evaluation records its result in one commit; a Generator run whose output
is the same as what is recorded writes none. **[Planned]** An impact judgement creates
build tasks for the Generators of the Facts it selects. Resuming a build task returns a `failed`
task to `created`, to run on the latest state.

### Registering a review presentation

**[Planned]** Registering a review presentation records that a judgement was requested: the presentation kind, the
target Facts with their Fact Addresses (several Fact Addresses may be presented together), the
person asked, which need not be the using party, the time, and a reference to the screen or dialogue
shown. It returns an identifier that answers and conflict resolutions reference. The Semantic
Catalog fills in the resulting events when they are recorded. The interface decides how to present
a review item; this operation records what was presented.

## Reads

### Reading along the hierarchy

Reading along the hierarchy follows the levels the Physical Catalog uses, data source, namespace,
table, and column, because that is how people and agents locate what a catalog holds. It takes a
point and a position: the domain root, a data source, a namespace, or a table.

- Datasets are tables and Fields are columns. A Measure belongs to the table of the Dataset its
  selected base-dataset Fact names, so a candidate never moves it, and an identifier
  correspondence to the tables at its ends. Concepts, business terms, concept relationships, and
  Dimensions belong to the domain root, as does a Measure with no selected base Dataset. A
  Dimension has no base-Dataset predicate, and the value of its classification, which names its
  source Field, has no defined shape yet.
- A table is read as one unit: the Dataset, every Field, the Measures based on it, and the
  correspondences it is an end of, each element with the result of requesting all its predicates
  (see [Requesting knowledge](#requesting-knowledge)), confirmed knowledge by default or with
  unconfirmed knowledge when the caller asks.
- Above a table, the caller chooses the position with its direct children and their counts, or
  recursive, every table unit below the position. A read at the root adds the domain-wide elements.
- Every level carries counts of Fact Addresses with a selected Fact, of items awaiting review as
  the review list returns them, and of closed-core predicates with missing knowledge: those with no
  Fact Address, and those whose every Fact Address has missing knowledge. An element at the ends of
  a correspondence in two tables counts in each table and once in the levels above them. The counts
  are the same whichever knowledge is asked for, so a confirmed-only read still shows how much
  awaits review.

The data source, namespace, and table of a Dataset come from its Physical Catalog reference. Until a
method for searching the catalog exists, a recursive read from the root returns the whole catalog,
which is small enough at first to hand to a language model; reading from a lower position keeps a
read bounded as the catalog grows.

### Finding elements

Finding elements returns the URN and kind of elements whose existence Fact is selected at the point,
optionally filtered by element kind, by parent element (the Fields of a Dataset), or by the written
form of a business term, which returns the elements its selected referenced-element Facts point to.
It does not interpret question wording; finding elements from a question is the Query Agent's work.
**[Planned]** A written form matches the names of every declared language unless the call names
one, because a reader may use a name from a language other than the one they read in. Each result
says which language's name matched and carries the term's names in the other languages.

### Requesting knowledge

Requesting knowledge takes a point and a sequence of requests. **[Planned]** It also takes,
optionally, one set of entity URNs the question mentions. A request is a target element and one of its kind's predicates, or all of
them. An edge whose
URN is unknown can be given by its kind and its ends in direction order (any order if
undirected), each end as the URNs of all its elements in any order, without its identifying part. For each request the Semantic Catalog:

1. Resolves the target elements: the given element, or every edge of that kind between those ends up
   to the point.
2. Collects every Fact Address of the predicate on each element, whatever its applicability scope.
3. Judges each Fact Address at the point as described in
   [`catalog-snapshot.md`](catalog-snapshot.md#what-the-query-agent-receives) and names its status:
   `selected`, `conflict`, `awaiting-judgement`, or `missing-knowledge`. By default it returns only
   confirmed knowledge, the Fact Addresses with a selected Fact; when the caller asks for
   unconfirmed knowledge too, it returns every Fact Address with its judgement material, the
   selected Fact with its new candidates or the candidates awaiting judgement, each saying whether
   it awaits consent or its dependencies.
4. **[Planned]** If entity URNs were given, reports for each Fact Address whether its applicability scope overlaps
   them, does not overlap, or is unrestricted. Non-overlapping Fact Addresses are still returned.

Requesting all predicates of an element judges every closed-core predicate of its kind as above.
**[Planned]** A language-qualified predicate is judged once per declared language, so a business
term without an English name reports its written form as missing knowledge for `en`.
It also returns every other Fact Address of the element, such as those of free-description and
Generator-declared predicates, which cannot be listed in advance.

Whichever knowledge is asked for, each answer lists the requested predicates with missing
knowledge: those with no Fact Address on the element, and those whose every Fact Address has
missing knowledge. A gap in core knowledge is thus visible without asking for unconfirmed
knowledge. An unknown element URN is a target-not-found error. A nonexistent edge has no Fact
Address and nothing to list. Free-description predicates cannot be requested by
name, because they have no identity of their own; requesting all predicates returns them.

### Retrieving a Fact Revision

**[Planned]** Retrieving a Fact Revision takes a Fact Revision URN, or a Fact URN and a point, and returns its
content, whether or not the Fact is currently selected. The citation for an answer to a question is
the Fact Revision URNs used and the Catalog Snapshot URN read. The Query Agent returns these with
its answer, and everything else, such as validation results and confirmation path, is retrievable
from them.

### Retrieving the review list

Retrieving the review list takes a point and a position in the hierarchy, the domain root, a data
source, a namespace, or a table, optionally filtered by presentation kind, element, or Fact Address.
It returns the review items below that position (see
[`construction.md`](construction.md#review)), grouped per table as in
[Reading along the hierarchy](#reading-along-the-hierarchy), with domain-wide items in a group of
their own:

- **[Planned]** Candidates whose latest evaluation requested consent, including those whose premises still await
  consent. Such an item is marked as resting on its premises and points to their items.
- **[Planned]** Open retirement proposals whose latest evaluation requested consent.
- **[Planned]** Conflicting Fact Addresses.

**[Planned]** Items answered unknown without a new dependency reference since are excluded. Each item carries the
evaluation that requested the judgement, with its review material, any earlier presentations of it,
and the commit at which the need for judgement arose. Within a group, items are ordered by that
commit, which also lets a caller tell new items from ones it has already seen; a re-evaluation gives
an item a newer commit, because its basis changed. The order is not a presentation order, which the
interface decides.

### Retrieving build tasks

**[Planned]** Retrieving build tasks returns, by identifier or filtered by state and kind, each task's state,
kind, triggers, Generator and version, input versions, evaluation version, and result.

### Checking a user policy version

**[Planned]** Checking a user policy version evaluates a proposed setting at the latest point and returns the
current version, the number and URNs of candidates that would be adopted inline, those that would go
to the policy evaluation model, whose decisions are known only by running it, and the point
evaluated. A commit between this read and setting the version can change the actual outcome.

## One question, end to end

1. **[Planned]** **Fix the point.** The Query Agent finds elements at "latest": the sales Measure that the
   business term "sales" refers to, and the e-commerce order and POS purchase Datasets. It keeps the
   returned Catalog Snapshot URN for the whole question.
2. **[Planned]** **Request knowledge.** At that point it requests the Measure's aggregation rule and reference
   time, and the set relationship and business definition of the customer-to-member correspondence,
   given by edge kind and its two Field sets.
3. **[Planned]** **Receive.** The Measure's predicates come back as selected Facts. The business definition cannot
   be checked against data, so under `auto` it is not confirmed yet: a confirmed-only read leaves it
   out, and the Query Agent, which requested that predicate and did not receive it, asks for
   unconfirmed knowledge and receives it as awaiting judgement with the candidate awaiting consent.
4. **[Planned]** **Judge.** If the user is a deciding party, the Query Agent registers a review presentation and
   the user consents through a call made as themselves, since the agent's own principal cannot
   confirm; the Query Agent then rereads at the returned point. Otherwise confirmation waits for the
   semantics owner, and the Query Agent proceeds with unconfirmed knowledge only if the user
   explicitly asks.
5. **[Planned]** **Cite.** The Query Agent returns the Fact Revision URNs it used and the Catalog Snapshot URN it
   read with its answer.

**[Planned]** When requested knowledge is missing, the Query Agent registers usage feedback whose purpose is
composing a query, with a summary of what the question needed, and the application runs the build
tasks returned, so Generators produce candidates on the spot.
