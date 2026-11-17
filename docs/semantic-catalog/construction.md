# Semantic Catalog construction

Construction turns inputs into confirmed knowledge. An input arrives, Generators produce candidates,
the Semantic Catalog accepts them, the user policy chooses a confirmation path, and the deciding
party judges what the policy sends to a person. Each step only appends to the log. This document
explains that cycle and the records each step writes. The records themselves are described in
[`record-model.md`](record-model.md), and the individual Generators in
[`generators.md`](generators.md).

## What starts construction

Construction starts from new or changed input, not from questions:

- Registration of a data source and re-retrieval of its table metadata. Each table's **source
  metadata**, what the connector read about the table in a shape only that connector defines,
  becomes an Input Observation of that table's physical schema Input Resource, which holds the
  Physical Catalog's identifier for the table; a table found not to exist becomes an observation
  that says so. Source metadata is the material for interpreting meaning, and it holds every
  declaration the table metadata states, such as names, columns, and constraints, because the
  connector derives the table metadata from it. The table metadata, with whether it is complete,
  the read time, the retrieval information, the content digest, and the digest of the source
  metadata schema, is kept as a **derived record** of the table metadata version, which is neither
  an Input Observation nor a Fact. The **source metadata schema** is the JSON Schema the connector
  publishes for its source metadata documents, whose descriptions state what each item means; the
  domain keeps each schema once under its digest. Whether an observation is new is judged by
  comparing the source metadata with the previous observation, so a change only in the connector's
  mapping, or only in its schema, adds a derived record and no observation. The derived
  record holds the table metadata only until the Physical Catalog keeps the table metadata it is
  built from; the Semantic Catalog then reads it there.
- Documents the semantics owner registers or changes. A document observation starts concept
  inference for the Datasets it names and analytical semantics inference; meaning inference also
  reads the domain's documents when its other inputs start it.
- Usage feedback: corrections given in review, and what a user's LLM session registers, such as
  missing knowledge met while composing a query. **[Planned]** Feedback without related Facts
  starts no Generator yet.

**[Planned]** Generators also aim at predicates that have no Fact yet. Questions are one input among others,
neither the only trigger nor a gate. Registering the e-commerce order database starts candidates for
the row meaning, key, and uniqueness of order lines without waiting for a question that needs them.

The Semantic Catalog does not poll sources or documents. A change is detected at the moment
registering an observation records a new Input Observation.

## Build tasks

A **build task** tracks one Generator run, one impact judgement, one evaluation by the policy
evaluation model (see [Choosing the confirmation path](#choosing-the-confirmation-path)), one
re-evaluation after a user policy change, or **[Planned]** one translation (see
[`languages.md`](languages.md#translations)), with its trigger, inputs, and result.

| State | Meaning | Next |
| --- | --- | --- |
| `created` | Not yet run | `running` |
| `running` | Being run | `succeeded`, `failed`; `created` when a later run finds it abandoned |
| `succeeded` | Result recorded, including the result that there was no candidate | None |
| `failed` | Failed, with its reason | `created`, by resuming |

A build task records its kind, its triggers (log records), the Fact an evaluation evaluates, the
Generator a run runs, and the observation judged with the Facts selected for re-examination for an
impact judgement. The versions of the inputs a Generator read are recorded on the acceptances and
retirement proposals it produced. Its result holds the commits it wrote, the
model version of an evaluation, the policy version a re-evaluation ran under, the missing inputs
when there was no candidate, and any duplicate-check matches with the original values.
**[Planned]** Impact judgements are not implemented yet; Generator runs, evaluations by the policy
evaluation model, and policy re-evaluations are. A Generator run records the Generator's version and
the inputs a target lacked in its result, and the run's commit, if it wrote one.

No Generator suspends to wait. An answer or new input creates a new build task.
A Generator lacking input records that there was no candidate and which inputs were
missing, and succeeds. An unavailable model fails the task, and resuming runs it again on the latest
state. Whether construction is
in progress, blocked, or awaiting judgement is derived: in progress means some task is `created` or
`running`, blocked means some task is `failed`, and awaiting judgement means the review list is
non-empty.

When a new trigger arrives for work that already has a `created` task of the same kind, such as a
Generator, the Fact an evaluation evaluates, or the domain's re-evaluation, the trigger is added to
that task instead of creating another, so several reports of one change do not repeat a run. A task
that has started running takes no more triggers, because the run has not read the new input.
**[Planned]** Each new observation gets its own impact judgement. Every acceptance references the
build task it came from in its provenance.

The application decides when build tasks run. Running them is a separate public API operation from
the operations that create them (see [`public-api.md`](public-api.md)). Only one process runs a
domain's build tasks at a time, and a second attempt is refused while one runs. A task's result is
written in one commit at its end, so a task left `running` by a process that stopped has written
nothing; when a process starts running a domain's tasks, it returns every such task to `created`.
Exclusivity makes that reset safe, because no other process can be running them.

## Acceptance and the duplicate check

A Generator returns **raw candidates**, each with its target, value, inputs read,
dependency references, and checks. Raw candidates are temporary and never recorded. New elements and
candidates in one output refer to each other by temporary numbers, replaced by issued URNs at
acceptance. Validating its own output is the Generator's responsibility; output naming a missing
column or an invalid predicate is remade or discarded before it reaches the Semantic Catalog. When
no useful clue exists, the Generator records that no candidate was produced and which inputs were
missing, rather than guessing.

Every output goes through the **duplicate check** before acceptance, because the same claim piling
up as candidates would make the deciding party judge it twice. A match becomes an additional
acceptance of the existing Fact instead of a new Fact.

- For a closed-range predicate with an empty applicability scope, values are compared at acceptance,
  inside the domain's serialized write, so Generators running concurrently see each other's Facts. A
  match is still recorded as an additional acceptance, because the added check may be what lets the
  policy confirm the Fact, as when a data check adds `data-check` to uniqueness taken from an
  unenforced declaration.
- **[Planned]** Otherwise a model compares the output with the confirmed and `candidate` Facts of
  the same element (of the same predicate, or of all free-description predicates) inside the build
  task, before the write. This also catches applicability scopes that differ only in wording.
  Matches are kept in the build task result with the original value, so a false match, which would
  keep a claim from the deciding party, can be found later. Concurrent Generators can still produce
  the same claim twice this way; the deciding party then judges both. A proposal of a new element
  skips the check because there is nothing yet to compare with.

**[Planned]** Every Generator receives the rejected and retired Facts of its target Fact Address
with their answers, and points to a past Fact instead of creating a new one when it judges the claim
the same.

An additional acceptance never depends on its own Fact. Its effect depends on the Fact's state:

- On a `candidate` that can be evaluated, it triggers a new policy evaluation.
- On a `confirmed` Fact, it changes nothing in selection.
- On a `rejected` or `retired` Fact, it is recorded only if it brings a **new dependency
  reference**, one absent from the Fact Revision at the latest rejection, retirement, or unknown
  answer, or an observation newer than the one depended on. It then returns the Fact to `candidate`.
  Otherwise it records nothing and succeeds. A rejected candidate therefore never returns to the
  deciding party without new input.

An acceptance whose dependency references and validation result equal those of the Fact's latest
acceptance records nothing and succeeds, so a Generator run on unchanged input or a resumed build
task does not pile up records.

At acceptance the Semantic Catalog checks only what a Generator running outside the serialized write
cannot know: that dependencies form no cycle among Facts, that every referenced record exists, and
that every Input Observation a check references is also a dependency. It also makes the slug of a
new element unique (see [`record-model.md`](record-model.md#identity-and-urns)). It also parses
expression values with the Query Engine's parser and adds the Fields they reference as premise
dependencies, before comparing the acceptance with the Fact's latest one, so producing the same
expression again records nothing (see
[`record-model.md`](record-model.md#elements-and-predicates)). An acceptance of an expression value
depends on exactly one base-dataset Fact of its Measure, over whose table the expression is read.
It also reads a Dimension's values in the form of the kind its dimension-kind Facts state, which
must be exactly one defined kind, parses a calendar level's expression, which may read only the
time value, and requires a Measure's default time granularity to depend on its Dimension's
dimension-kind Fact stating a calendar.
If any check fails, nothing is written and the task fails with the reason.

## Choosing the confirmation path

A candidate whose premise is rejected or retired can never be confirmed, so it is rejected (see
[`record-model.md`](record-model.md#validation-results-and-dependencies) for premise and replacement
dependencies). **[Planned]** Its Generator is run again with that outcome as input. Otherwise a
candidate is evaluated once each premise dependency is confirmed or is a candidate awaiting consent,
and its confirmation waits until every premise is confirmed. A new Measure's existence and its
aggregation rule therefore reach the deciding party together, instead of the aggregation rule
appearing only in a later round after the existence is consented to.
[`../design/semantic-catalog-premise-review.md`](../design/semantic-catalog-premise-review.md)
records why.

The **user policy** decides, by **policy evaluation**, whether machine judgement adopts the
candidate or the deciding party must consent. Retirement proposals are decided the same way. A
user policy version holds a preset and whether `declared-unenforced` counts as a deterministic
validation method, with the semantics owner who set it and their reason. A new domain starts with
`auto`, not counting `declared-unenforced`;
[`../design/semantic-catalog-default-user-policy.md`](../design/semantic-catalog-default-user-policy.md)
records why. **[Planned]** A later design models and quantifies the policy, so that an organization
can configure how far it delegates judgement to the machine; those quantities will themselves be a
model's fuzzy assessments.

| Preset | Machine judgement |
| --- | --- |
| `full-auto` | Always adopts |
| `auto` | Adopts inline on deterministic signals; otherwise the policy evaluation model decides |
| `manual` | Never adopts |

Generators propose with models, so deciding whether to accept what they propose is a fuzzy
judgement too: no fixed test over validation items says when a value holds. Under `auto`, policy
evaluation takes one of two paths. **Inline evaluation**, on deterministic signals and without a
model, runs in the commit that makes the candidate evaluable; it adopts the candidate inline,
requests consent for a refuted candidate or a second Fact, and leaves every other candidate to the
policy evaluation model.

**Inline adoption.** A candidate is adopted at once, without a model, when all of these hold:

1. For a confirmation, every validation item is supported by a deterministic check. For a
   retirement, the reason is supported by one.
2. For a confirmation, no item rests only on data checks that found no counterexample. An item that
   restricts what the data may hold, such as a Field set that does not repeat or every value on one
   side of a correspondence appearing on the other, is also supported by a declaration, enforced or
   not.
3. No check refutes it.
4. For a confirmation, no other Fact is selected at the same Fact Address in the Catalog Snapshot
   just before the evaluating commit.

These conditions do not claim that a value is true. They mark where deterministic signals bear on
the whole value, which covers most of a catalog's structure, such as existence, Physical Catalog
references, and enforced constraints, so the policy evaluation model is not called for it. Condition
2 holds because data can refute a rule but not establish one. A sample, or even a whole table, in
which each member has bought once shows purchase rows whose member IDs do not repeat; adopting that
would let a Query Agent omit fan-out handling and double-count once a member buys again. The
opposite value rests on a counterexample, and erring there only makes a query more cautious. A
declaration supplies the intent that data cannot, so an unenforced unique constraint with no
duplicates found is still adopted inline. A column that disappeared from the physical schema is
supported by `declared-enforced`, so retiring its Field is adopted inline.

**The policy evaluation model.** Every other candidate under `auto` is evaluated by a
language model kept separate from the Generators, so producing and accepting candidates stay apart.
It reads the same material a review item shows: the value with its inputs read, checks, and
dependencies, their content expanded. It weighs these signals as a whole, then either adopts the
candidate or sends it to the deciding party with review material it builds (see [Review](#review)).
Its reason is recorded on `evaluate-policy`, with its version, the model and the prompt, as a
Generator's version is recorded. A candidate that a check refutes, or that would confirm a second
Fact where one is selected, always goes to the deciding party. An evaluation by the policy
evaluation model runs as its own build task, one per Fact, because it calls a model and must not
hold up the operation that made the candidate evaluable, such as an answer submission.

The model runs outside any transaction, so its result is reconciled with the state when it is
recorded. If the Fact no longer waits for it, because it was judged, answered, or is no longer
evaluable, nothing is recorded. If inline evaluation now decides it, that inline evaluation is
recorded instead. If the Fact's latest acceptance, its open retirement proposal, the policy version in force, or the
Fact selected at its Fact Address changed while the model ran, the evaluation was made on stale material; nothing
is recorded and a new evaluation task takes its place.
[`../design/semantic-catalog-machine-judgement.md`](../design/semantic-catalog-machine-judgement.md)
records why the judgement is fuzzy and why a separate model makes it.

So under `auto`, a Field meaning on which the column name, sample values, and a registered glossary
agree can be adopted without a person, while a business definition that only a name suggests, such
as whether a sales amount includes tax, reaches the deciding party. Under `full-auto`, a confirmed
candidate made from the selected Fact replaces it, and one made without it becomes a conflict for a
person to resolve.

The policy is evaluated, and an `evaluate-policy` event recorded, when a candidate becomes evaluable
(or at acceptance if it already is), when an evaluable candidate gets an additional acceptance, when
a confirmed Fact receives a retirement proposal, and when the policy version changes. An evaluation
that adopts a candidate whose premises are not all confirmed yet takes effect in the commit that
confirms the last of them, without evaluating again; a premise that is rejected or retired instead
rejects the candidate by cascade.

Setting a user policy version sends every judgement not yet carried out back to policy evaluation,
because otherwise loosening the policy would not relieve the candidates already waiting and
tightening it would let an earlier adoption still take effect. An evaluation stands only under the
version it names. Once another version is set, a candidate awaiting consent or adopted pending its
premises awaits policy evaluation again, and so does an open retirement proposal awaiting consent.
The candidates resting on such a candidate are not evaluable until it is evaluated again. Answers
are not policy evaluations and stand across versions, so a consent pending its premises still takes
effect. The version change creates a build task that evaluates all of these inline under the
version in force, in one commit; under `auto`, those it leaves undecided get evaluation tasks for
the policy evaluation model. Loosening can therefore confirm many candidates at once without a
person, so before applying a version the application can read which candidates it would confirm.
Confirmed Facts are not re-evaluated.

The product fixes no condition under which consent is mandatory. Instead, every Fact shows its
confirmation path and policy version to consumers, so knowledge confirmed under a loose policy is
used knowing so.

## Commits and cascades

The following each write one commit, so a half-written state is never a readable point:

- One Generator run: all acceptances and retirement proposals from it, the inline evaluations of
  those that can be evaluated, and the confirmations and retirements they adopt, with the data
  observations it read.
- One evaluation by the policy evaluation model: its `evaluate-policy`, the confirmation or
  retirement it adopts, and the transitions they cause. **[Planned]** Any data observations it read.
- One submission of answers with the transitions they cause. Answers apply in dependency order, and
  independent answers in submission order, so the log records a premise's answer before its
  dependents'. The order always exists because dependencies have no cycles. A consent to a candidate
  whose premise awaits consent takes effect once the premise is confirmed, so consenting to a
  premise and its dependents at once confirms them all in the one commit. Grouping also means
  candidates consented to together never have their selection decided by record order.
- One conflict resolution.
- One user policy version. The re-evaluation it triggers runs later as a build task, whose
  evaluations and confirmations form one commit, written only when inline evaluation decided
  something.

In any commit where a Fact becomes `confirmed`, `rejected`, or `retired`, the dependent Facts move
next in the same commit:

- A `candidate` whose premise dependencies are now all confirmed is confirmed if its evaluation
  already adopted it or the deciding party already consented.
- A `candidate` that has just become evaluable is evaluated inline. One that inline evaluation
  leaves undecided gets an evaluation build task for the policy evaluation model, in the same
  transaction.
- A `candidate` whose premise is rejected or retired is rejected.
- A `confirmed` Fact whose premise is retired is retired.

An evaluation that requests consent likewise makes the candidates resting on that candidate
evaluable, in the same commit.

Replacement dependencies do not cascade. A Fact that replaced a now-retired Fact stays as it is, and
a Fact whose replacement is retired becomes eligible again under the selection rules.

Facts rejected or retired by cascade record that cause. **[Planned]** Their Generators remake them
as build tasks.

## Review

Review is split in two. Deciding what needs a person happens in construction, not in the review
session: inline evaluation decides in the commit that makes a candidate evaluable. The policy
evaluation model decides, and prepares review material, inside build tasks. An interactive
review session, an agent working with the semantics owner or a Web UI, only fetches what is
prepared, presents it, and sends the answers.

A **review item** is what the review list returns for one candidate or retirement proposal needing
consent, or one conflict. The Semantic Catalog decides what it contains; the interface that
presents it decides its format, such as tables in an agent session or a comparison screen in a Web
UI. A review item carries:

- **What is claimed:** the target, with exact table and column names from its physical-catalog-reference
  Facts, the predicate, the value, and the applicability scope.
- **The records behind it, with their content expanded:** inputs read, including
  rejected or retired Facts at the same Fact Address with their answers; checks, with the quotation,
  the query and its result and range read, or the model's stated basis; and dependencies, including
  the feedback a correction left. Pointing to them by URN alone would make the deciding party look
  each one up.
- **What consent changes:** the selected Fact it would replace, the premise items it rests on with
  their content, and the items a rejection would cascade to.
- **Review material,** when the policy evaluation model sent it to a person: what is to be decided,
  why it was not adopted and what remains uncertain, the evidence for and against, and a suggested
  answer with its reasons. The material is kept with the evaluation. It is written in the domain's
  recording language, its primary language. **[Planned]** It reaches a deciding party who reads
  another language as a translation (see [`languages.md`](languages.md#translations)).
  **[Planned]** The policy
  evaluation model may sample values through the application's SQL execution within its build
  task's limits to build it, and it sorts the items of each table into those likely to follow the
  suggestion and those that need judgement.

**[Planned]** The material is selected for judgement rather than dumped from the stored context: too
little makes the deciding party investigate alone, too much makes them search for what matters, and
anything left out stays reachable for those allowed to see it. When material is in place, a
consent-or-reject choice is preferred to writing an answer from scratch, but those two options are
not forced when they cannot express the judgement. Several Facts can be presented together, while
each judgement is still recorded per Fact. Building it in the policy evaluation model rather than in
each interface means the same judgement shows the same material whatever interface presents it, and
the reading that decided not to adopt a candidate is the one the deciding party sees. Presentation
priority weighs how far a judgement unblocks further construction against its impact on data use,
how far a mistake would spread, and the load on the deciding party, never the count of follow-on
candidates alone.

What was presented, to whom, and when is kept as a **review presentation record**, outside the log,
because presenting changes no Fact's state. It holds the presentation kind, the target Facts and
Fact Address, the recipient and time, a reference to the context shown, and the resulting events. A
candidate whose premises await consent is presented together with them and marked as resting on
them, so the deciding party sees that rejecting a premise rejects it too. Nothing answered unknown
that has gained no new dependency reference since is presented.

An answer records the deciding party's own wording as context, without converting conditions,
exceptions, or certainty into fixed fields such as confidence scores.

| Answer | On a confirmation judgement | On a retirement proposal |
| --- | --- | --- |
| Consent | `answer`, `confirm` (human consent); becomes `confirmed` | `answer`, `retire`; becomes `retired` |
| Rejection | `answer`, `reject`; becomes `rejected` | `answer` only; stays `confirmed` |
| Correction | `answer` with the given value or condition, `reject`; becomes `rejected` | `answer` only; stays `confirmed` |
| Unknown | `answer` only; stays `candidate` | `answer` only; stays `confirmed` |

A corrected candidate is rejected because it will never be confirmed with its original value and
scope. Keeping it would make the deciding party judge one point twice. The same commit registers the
correction's wording as usage feedback related to that candidate.

Correction interpretation then produces the corrected candidate, which is evaluated like any
other. The correction's wording is among its inputs read, so the policy evaluation model and, if it
is sent to a person, the deciding party see what was actually said, and a misread
correction shows as a mismatch with it. When the answer to "every e-commerce customer is a POS
member" is "correct, except for corporate members", the original candidate is rejected and a new
candidate scoped to exclude corporate members follows.

Every answer and conflict resolution carries the deciding party and a reference to an authority
check, an opaque value issued by whoever checked, which the Semantic Catalog records without
interpreting and only requires to be present. The Semantic Catalog therefore does not itself verify
that the person answering is the deciding party; the separation between producing candidates and
confirming them holds as far as the issuing party checks authority. **[Planned]** The application
issues the reference after checking that the authenticated caller is a semantics owner of the domain
(delegates are not designed yet), and a Query Agent calls as its own registered principal, which is
never a semantics owner, so an agent cannot confirm what it relays (see
[`../application.md`](../application.md#callers-and-authority)). An answer to a Fact already
confirmed or rejected by another path after presentation is recorded without a transition.

## Operations that add records

No operation sets the selected Fact directly. Selection changes only as the rules in
[`catalog-snapshot.md`](catalog-snapshot.md) derive it from appended records.

| Operation | Writes in one commit | Not accepted when | Creates build tasks for |
| --- | --- | --- | --- |
| Register an observation | Input Observation and Observation Event | Content equals the previous observation, which records nothing; a physical schema whose source metadata is unchanged records only a derived record; a table metadata version no newer than one received records nothing | Generators that read that input kind; **[Planned]** an impact judgement for a new physical schema or document observation |
| Record a Generator's results (inside a build task) | Data observations; acceptances, retirement proposals, inline evaluations, machine-judgement transitions, cascades | A data observation equal to its predecessor apart from when it was read, which is not recorded again; a dependency cycle, a missing referenced record, a check reference that is not a dependency, an expression value that does not parse, or a Dimension value or default time granularity not in its form or without the Facts it rests on, which fails the task | Evaluations by the policy evaluation model; Generators that read the newly confirmed or accepted predicates |
| Record an evaluation by the policy evaluation model (inside a build task) | Evaluation with its reason and review material, machine-judgement transitions, cascades; **[Planned]** data observations it read | The Fact no longer waits for the model, which records nothing; inline evaluation now decides it, which records that inline evaluation instead; or the material it read changed, which creates a new evaluation task | Evaluations of dependents left undecided; Generators that read the newly confirmed predicates |
| Submit answers | Answers, resulting transitions, cascades, and feedback for each correction | An answer lacks an authority-check reference | Evaluations left undecided; Generators that read newly confirmed predicates; Generators that read feedback with related Facts; **[Planned]** Generators of rejected, corrected, retired, and cascaded Facts |
| Resolve a conflict | `resolve-conflict` | The chosen and passed-over Facts were not the eligible Facts at the previous commit, or no authority-check reference | **[Planned]** Generators of Facts depending on Facts no longer selected |
| Set a user policy version | Policy version | None | A policy re-evaluation |
| Re-evaluate the policy (inside a build task) | Evaluations, machine-judgement confirmations, cascades | None | Evaluations left undecided; Generators that read newly confirmed predicates |
| Resume a build task | Nothing in the log | The task is not `failed` | None; the task reruns on the latest state |

**[Planned]** Whenever replacement, a conflict resolution, or a retirement changes the selected
Fact, build tasks are created for the Generators of Facts that depended on the Fact no longer
selected, so knowledge built on it is remade on the new premise.

Build tasks, review presentation records, derived records, and public API call identifiers are kept
outside the log because they change no Fact's state, and none is part of a Catalog Snapshot.
Everything one operation writes, in the log and outside it, is written in one transaction. That
includes operations that write no commit, such as a derived-record-only observation, a review
presentation, a build task finishing without candidates, and a resume. A stopped process therefore
never leaves outside records disagreeing with commits.
**[Planned]** Translations are kept outside the log too, because they change no Fact's state either.

## Following input changes

**[Planned]** Impact judgement is not implemented yet. Changes fixed by declarations, handled by
schema Generators, are.

When a physical schema or document gets a new observation, an **impact judgement** build task
compares the old and new observations against the Facts that depend on that Input Resource and
selects which to re-examine. Whether a change affects a Fact's meaning is itself a judgement of
meaning, so a model makes it: revising one section of a document need not touch Facts based on
another. The Generators of the selected Facts are run again, and the impact judgement produces no
candidates itself. If it misses something, an outdated Fact stays confirmed, but the Query Agent
still sees that a depended-on input has a newer observation.

Changes fixed by declarations, such as a table or column disappearing, are handled by schema
Generators without a model. They propose retiring the element's existence Fact, and the user policy
decides the proposal like any other. An input change alone never changes a selected Fact or stops
its presentation.

A renamed column or table is a new element. If `amount` disappears and `gross_amount` appears, the
old Field is retired and a new Field is created, and the retirement cascades into the
correspondences and Measures built on the old Field (see
[`generators.md`](generators.md#column-changes)). The new Field's own predicates come from its
Generators as for any new column. **[Planned]** The Generators of the retired Facts then propose
them again on the new premise, reading the retired Facts as inputs read. Knowledge carries over through ordinary evaluation, which the
policy evaluation model can adopt, rather than by moving a Physical Catalog reference. A table rename
appears as "does not exist" for the old name, and the new name becomes a new Dataset once it is
registered.

## Usage feedback

Almost everything a user tells the catalog outside review arrives through an LLM session: the Query
Agent meets missing knowledge while composing a query, the user explains what a column means, or a
semantics owner says something while reviewing. When the Query Agent meets missing knowledge or a
conflict:

1. The application registers what the session found worth keeping as usage feedback. Conflicts are
   derivable from the log and are not registered.
2. Generators produce candidates on the spot, and the policy confirms what machine judgement can.
   Feedback with related Facts starts correction interpretation. **[Planned]** Feedback without
   related Facts starts the Generators that aim at what it says is missing.
3. If the user is a deciding party, they consent or resolve a conflict on the spot through a review
   presentation. **[Planned]** Corrections from any other user are registered as usage feedback;
   their consent cannot confirm, because confirmation would then fall outside the user policy and
   the record of the deciding party.
4. Confirmed knowledge appears at a later commit, and the Query Agent reads again at that commit.

**Usage feedback** holds:

- its content;
- the purpose of the session it came from, composing a query or constructing the catalog, because
  the same statement means a premise of an answer in one and a judgement on a candidate in the
  other;
- a summary of the session's context, written by the party that registers it, so that a statement
  such as "that one is tax-exclusive" can be interpreted;
- optionally, related Facts.

The registering principal is recorded on the commit. Each registration gets a new Input Resource,
because feedback is never revised. Context is a summary rather than the conversation itself, because
the log cannot erase what it holds and a raw conversation would keep everything said in it. Related
Facts are clues, not commands: feedback never replaces a selected Fact directly, and the candidates
produced from it go through the same acceptance and confirmation. A correction answer registers its
wording as usage feedback whose purpose is constructing the catalog, whose context summarizes what
was presented, and whose related Fact is the corrected candidate.

The Semantic Catalog does not depend on the product's tracing of use (see
[`../architecture.md`](../architecture.md#tracing-use)) and receives no trace records. The knowledge
behind an answer can still be traced afterwards, because the Query Agent cites the Catalog Snapshot
and Fact Revisions it used and every past Fact Revision stays retrievable by URN. Use is never
evidence that knowledge is correct.

**[Planned]** A user organization can stop passing usage feedback to Generators. Feedback-triggered candidates
then stop, but the Query Agent still receives missing knowledge and conflicts, and the log,
confirmation paths, and review presentation records remain. Which inputs reach Generators and models
is set per domain and checked where the Semantic Catalog hands input to a Generator (see
[`generators.md`](generators.md#input-passed-to-external-models)).
