# Semantic Catalog construction

**[Planned]** This document describes accepted design that is not implemented yet.

Construction turns inputs into confirmed knowledge. An input arrives, Generators produce candidates,
the Semantic Catalog accepts them, the user policy chooses a confirmation path, and the deciding
party judges what the policy sends to a person. Each step only appends to the log. This document
explains that cycle and the records each step writes. The records themselves are described in
[`record-model.md`](record-model.md), and the individual Generators in
[`generators.md`](generators.md).

## What starts construction

Construction starts from new or changed input, not from questions:

- Registration of a data source and re-retrieval of its table metadata. Each table's source-declared
  part (names, table kind, source-side identifier, columns without Analytics types, constraints,
  completeness and missing items, and "does not exist") becomes an Input Observation of that table's
  physical schema Input Resource, which holds the Physical Catalog's identifier for the table. The
  connector-derived part (Analytics types, retrieval information, and content digest) is kept as a
  **derived record** that is neither an Input Observation nor a Fact. Whether an observation is new
  is judged by comparing its source-declared part with the previous observation, so a change only in
  the connector's mapping adds a derived record and no observation.
- Documents the semantics owner registers or changes.
- Usage feedback: missing knowledge and statements from a user's LLM session, and corrections given
  in review.

Generators also aim at predicates that have no Fact yet. Questions are one input among others,
neither the only trigger nor a gate. Registering the e-commerce order database starts candidates for
the row meaning, key, and uniqueness of order lines without waiting for a question that needs them.

The Semantic Catalog does not poll sources or documents. A change is detected at the moment
registering an observation records a new Input Observation.

## Build tasks

A **build task** tracks one Generator run, one impact judgement, or one re-evaluation after a user
policy change, with its trigger, inputs, and result.

| State | Meaning | Next |
| --- | --- | --- |
| `created` | Not yet run | `running` |
| `running` | Being run | `succeeded`, `failed` |
| `succeeded` | Result recorded, including the result that there was no candidate | None |
| `failed` | Failed, with its reason | `running`, by resuming |

A build task records its kind, its triggers (log records), the Generator and
version and the versions of inputs read, the policy version for a re-evaluation, and the observation
judged with the Facts selected for re-examination for an impact judgement. Its result holds the
commits it wrote, the missing inputs when there was no candidate, and any duplicate-check matches
with the original values.

No Generator suspends to wait. An answer or new input creates a new build task. A Generator lacking
input records that there was no candidate and which inputs were missing, and succeeds. An
unavailable model fails the task, and resuming retries with the same input. Whether construction is
in progress, blocked, or awaiting judgement is derived: in progress means some task is `created` or
`running`, blocked means some task is `failed`, and awaiting judgement means the review list is
non-empty.

When a new trigger arrives for a Generator that already has a `created` task of the same kind, the
trigger is added to that task instead of creating another, so several reports of one change do not
repeat a run. A task that has started running takes no more triggers, because the run has not read
the new input. Each new observation gets its own impact judgement. Every acceptance references the
build task it came from.

The application decides when and how many build tasks run. Running one is a separate public API
operation from the operations that create them (see [`public-api.md`](public-api.md)).

## Acceptance and the duplicate check

A Generator returns **raw candidates**, each with its target, value, inputs read, dependency
references, and checks. Raw candidates are temporary and never recorded. New elements and candidates
in one output refer to each other by temporary numbers, replaced by issued URNs at acceptance.
Validating its own output is the Generator's responsibility; output naming a missing column or an
invalid predicate is remade or discarded before it reaches the Semantic Catalog. When no useful clue
exists, the Generator records that no candidate was produced and which inputs were missing, rather
than guessing.

Every output goes through the **duplicate check** before acceptance, because the same claim piling
up as candidates would make the deciding party judge it twice. A match becomes an additional
acceptance of the existing Fact instead of a new Fact.

- For a closed-range predicate with an empty applicability scope, values are compared at acceptance,
  inside the domain's serialized write, so Generators running concurrently see each other's Facts. A
  match is still recorded as an additional acceptance, because the added check may be what lets the
  policy confirm the Fact, as when a data check adds `data-check` to uniqueness taken from an
  unenforced declaration.
- Otherwise a model compares the output with the confirmed and `candidate` Facts of the same element
  (of the same predicate, or of all free-description predicates) inside the build task, before the
  write. This also catches applicability scopes that differ only in wording. Matches are kept in the
  build task result with the original value, so a false match, which would keep a claim from the
  deciding party, can be found later. Concurrent Generators can still produce the same claim twice
  this way; the deciding party then judges both. A proposal of a new element skips the check because
  there is nothing yet to compare with.

Every Generator receives the rejected and retired Facts of its target Fact Address with their
answers, and points to a past Fact instead of creating a new one when it judges the claim the same.

An additional acceptance never depends on its own Fact. Its effect depends on the Fact's state:

- On a `candidate` whose dependencies are confirmed, it triggers a new policy evaluation.
- On a `confirmed` Fact, it changes nothing in selection.
- On a `rejected` or `retired` Fact, it is recorded only if it brings a **new dependency
  reference**, one absent from the Fact Revision at the latest rejection, retirement, or unknown
  answer, or an observation newer than the one depended on. It then returns the Fact to `candidate`.
  A rejected candidate therefore never returns to the deciding party without new input.

An acceptance whose dependency references and validation result equal those of an existing Fact
Revision records nothing and succeeds, so resuming a failed build task does not pile up records.

At acceptance the Semantic Catalog checks only what a Generator running outside the serialized write
cannot know: that dependencies form no cycle among Facts, that every referenced record exists, and
that every Input Observation a check references is also a dependency. If any check fails, nothing is
written and the task fails with the reason.

## Choosing the confirmation path

A candidate goes neither to policy evaluation nor to review until every premise dependency is
confirmed (see [`record-model.md`](record-model.md#validation-results-and-dependencies) for premise
and replacement dependencies). Asking a person to judge knowledge whose premises are unjudged wastes
the judgement if a premise is later rejected. A candidate whose premise is rejected or retired can
never be confirmed, so it is rejected, and its Generator is run again with that outcome as input.

The **user policy** then decides whether machine judgement confirms the candidate or the deciding
party must consent. Retirement proposals are decided the same way. A user policy version holds a
preset and whether `declared-unenforced` counts as a deterministic validation method, with the
semantics owner who set it and their reason. A new domain starts with `auto`, not counting
`declared-unenforced`;
[`../design/semantic-catalog-default-user-policy.md`](../design/semantic-catalog-default-user-policy.md)
records why.

| Preset | Proceeds by machine judgement |
| --- | --- |
| `full-auto` | Always |
| `auto` | When all four conditions below hold |
| `manual` | Never |

Under `auto`:

1. For a confirmation, every validation item is supported by a deterministic check. For a
   retirement, the reason is supported by one.
2. For a confirmation, no item rests only on data checks that found no counterexample. An item that
   restricts what the data may hold, such as a Field set that does not repeat or every value on one
   side of a correspondence appearing on the other, is also supported by a declaration, enforced or
   not.
3. No check refutes it.
4. For a confirmation, no other Fact is selected at the same Fact Address in the Catalog Snapshot
   just before the evaluating commit.

So under `auto`, anything that data cannot verify, such as a Field's meaning or the business
definition of an identifier correspondence, needs consent. Condition 2 holds because data can refute
a rule but not establish one. A sample, or even a whole table, in which each member has bought once
shows purchase rows whose member IDs do not repeat. Confirming that would let a Query Agent omit
fan-out handling and double-count once a member buys again. The opposite value rests on a
counterexample: a member ID that repeats settles that the Field set repeats, and erring there only
makes a query more cautious. A declaration supplies the intent that data cannot, so an unenforced
unique constraint with no duplicates found is still confirmed by machine judgement. A column that
disappeared from the physical schema is supported by `declared-enforced`, so retiring its Field
proceeds by machine judgement. Under `full-auto`, a confirmed candidate made from the selected Fact
replaces it, and one made without it becomes a conflict for a person to resolve.

The policy is evaluated, and an `evaluate-policy` event recorded, when a candidate's dependencies
all become confirmed (or at acceptance if they already are), when an evaluable candidate gets an
additional acceptance, when a confirmed Fact receives a retirement proposal, and when the policy
version changes. A version change creates a build task that re-evaluates every evaluable
`candidate`, because otherwise loosening the policy would not relieve the candidates already
waiting. Loosening can therefore confirm many candidates at once without a person, so before
applying a version the application can read which candidates it would confirm. Confirmed Facts are
not re-evaluated.

The product fixes no condition under which consent is mandatory. Instead, every Fact shows its
confirmation path and policy version to consumers, so knowledge confirmed under a loose policy is
used knowing so.

## Commits and cascades

The following each write one commit, so a half-written state is never a readable point:

- One Generator run: the data observations it read, all acceptances and retirement proposals from
  it, the policy evaluations of those that can be evaluated, and machine-judgement confirmations and
  retirements.
- One submission of answers with the transitions they cause. Answers apply in dependency order, and
  independent answers in submission order. An answer applied before the Fact it depends on would
  cause no transition and the deciding party would be asked again. The order always exists because
  dependencies have no cycles. Grouping also means candidates consented to together never have their
  selection decided by record order.
- One conflict resolution.
- One user policy version. The re-evaluation it triggers runs later as a build task, whose
  evaluations and confirmations form one commit.

In any commit where a Fact becomes `confirmed`, `rejected`, or `retired`, the dependent Facts move
next in the same commit:

- A `candidate` whose premise dependencies are now all confirmed has its policy evaluated.
- A `candidate` whose premise is rejected or retired is rejected.
- A `confirmed` Fact whose premise is retired is retired.

Replacement dependencies do not cascade. A Fact that replaced a now-retired Fact stays as it is, and
a Fact whose replacement is retired becomes eligible again under the selection rules.

Facts rejected or retired by cascade record that cause, and their Generators remake them as build
tasks.

## Review

**Review orchestration** decides what to present, when, to whom, and in which unit and context, for
candidates needing consent, retirement proposals needing consent, and conflicts needing a resolution.
It is separate from Generators, so presentation can be improved to lower the deciding party's load
without changing how candidates are produced. It selects the information needed for judgement rather
than dumping the stored context: too little makes the deciding party investigate alone, too much
makes them search for what matters, and anything left out stays reachable for those allowed to see
it. When material is in place, a consent-or-reject choice is preferred to writing an answer from
scratch, but those two options are not forced when they cannot express the judgement. Several Facts
can be presented together, while each judgement is still recorded per Fact. Presentation priority
weighs how far a judgement unblocks further construction against its impact on data use, how far a
mistake would spread, and the load on the deciding party, never the count of follow-on candidates
alone.

The Semantic Catalog does not define presentation conditions, grouping, presentation content, or the
answer format. The consuming side assembles presentations from the review list and sends judgements
by submitting answers (see [`public-api.md`](public-api.md)).

What was presented, to whom, and when is kept as a **review presentation record**, outside the log,
because presenting changes no Fact's state. It holds the presentation kind, the target Facts and
Fact Address, the recipient and time, a reference to the context shown, and the resulting events.
Candidates still waiting for their dependencies are not presented. Neither is anything answered
unknown that has gained no new dependency reference since.

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
correction's wording as usage feedback related to that candidate. Correction interpretation then
produces the corrected candidate, which also needs consent under `auto`, so a misread correction
does not become confirmed knowledge unseen. When the answer to "every e-commerce customer is a POS
member" is "correct, except for corporate members", the original candidate is rejected and a new
candidate scoped to exclude corporate members follows.

Every answer and conflict resolution carries the deciding party and a reference to an authority
check, an opaque value issued by whoever checked, which the Semantic Catalog records without
interpreting and only requires to be present. The Semantic Catalog therefore does not itself verify
that the person answering is the deciding party; the separation between producing candidates and
confirming them holds as far as the issuing party checks authority. The application issues the
reference after checking that the authenticated caller is a semantics owner of the domain (delegates
are not designed yet), and a
Query Agent calls as its own registered principal, which is never a semantics owner, so an agent
cannot confirm what it relays (see [`../application.md`](../application.md#callers-and-authority)).
An answer to a Fact already confirmed or rejected by another path after presentation is recorded
without a transition.

## Operations that add records

No operation sets the selected Fact directly. Selection changes only as the rules in
[`catalog-snapshot.md`](catalog-snapshot.md) derive it from appended records.

| Operation | Writes in one commit | Not accepted when | Creates build tasks for |
| --- | --- | --- | --- |
| Register an observation | Input Observation and Observation Event | Content equals the previous observation, which records nothing; a physical schema whose source-declared part is unchanged records only a derived record | Generators that read that input kind; an impact judgement for a new physical schema or document observation |
| Record a Generator's results (inside a build task) | Data observations, acceptances, retirement proposals, evaluations, machine-judgement transitions, cascades | A dependency cycle, a missing referenced record, or a check reference that is not a dependency; the task fails | Generators that read the newly confirmed predicates |
| Submit answers | Answers, resulting transitions, cascades, and feedback for each correction | An answer lacks an authority-check reference | Generators of rejected, corrected, retired, and cascaded Facts; Generators that read the feedback; Generators that read newly confirmed predicates |
| Resolve a conflict | `resolve-conflict` | The chosen and passed-over Facts were not the eligible Facts at the previous commit, or no authority-check reference | Generators of Facts depending on Facts no longer selected |
| Set a user policy version | Policy version | None | A policy re-evaluation |
| Re-evaluate the policy (inside a build task) | Evaluations, machine-judgement confirmations, cascades | None | Generators that read newly confirmed predicates |
| Resume a build task | Nothing in the log | The task is not `failed` | None; the task reruns on the latest state |

Whenever replacement, a conflict resolution, or a retirement changes the selected Fact, build tasks
are created for the Generators of Facts that depended on the Fact no longer selected, so knowledge
built on it is remade on the new premise.

Build tasks, review presentation records, derived records, and public API call identifiers are kept
outside the log because they change no Fact's state, and none is part of a Catalog Snapshot.
Everything one operation writes, in the log and outside it, is written in one transaction. That
includes operations that write no commit, such as a derived-record-only observation, a review
presentation, a build task finishing without candidates, and a resume. A stopped process therefore
never leaves outside records disagreeing with commits.

## Following input changes

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

Detecting a change and judging identity across it are separate. If `amount` disappears and
`gross_amount` appears, names cannot tell a rename from a drop and an add. The existing Field stays
selected, a candidate moves its technical reference to the new column, and only if that candidate is
rejected does retirement of the old Field and a new Field follow (see
[`generators.md`](generators.md#column-changes)). Retiring at once would cascade into the
correspondences and Measures built on the Field and make the deciding party consent to them again. A
table rename appears as "does not exist" for the old name. Once the new table is registered, a
candidate technical reference to it switches the Dataset by replacement.

## Usage feedback

Almost everything a user tells the catalog outside review arrives through an LLM session: the Query
Agent meets missing knowledge while composing a query, the user explains what a column means, or a
semantics owner says something while reviewing. When the Query Agent meets missing knowledge or a
conflict:

1. The application registers what the session found worth keeping as usage feedback. Conflicts are
   derivable from the log and are not registered.
2. Generators produce candidates on the spot, and the policy confirms what machine judgement can.
3. If the user is a deciding party, they consent or resolve a conflict on the spot through a review
   presentation. Corrections from any other user are registered as usage feedback; their consent
   cannot confirm, because confirmation would then fall outside the user policy and the record of
   the deciding party.
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

A user organization can stop passing usage feedback to Generators. Feedback-triggered candidates
then stop, but the Query Agent still receives missing knowledge and conflicts, and the log,
confirmation paths, and review presentation records remain. Which inputs reach Generators and models
is set per domain and checked where the Semantic Catalog hands input to a Generator (see
[`generators.md`](generators.md#input-passed-to-external-models)).
