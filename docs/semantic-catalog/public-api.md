# Semantic Catalog public API

**[Planned]** This document describes accepted design that is not implemented yet.

The public API is the set of operations and reads the `semantic-catalog` module exposes to the
application. The application reaches it from the CLI, the Agent Skill, and later interfaces. The
Query Agent never calls it directly, but what it receives is shaped by these reads. This document
explains the structure of the API and the contract of each call. Java signatures, wire formats, and
presentation on user surfaces belong to the code and the application. What each operation writes is
explained in [`construction.md`](construction.md#operations-that-add-records).

## What is exposed

Operations change the log, a build task, or a review presentation record. Reads change nothing.
Every call targets one domain.

The application calls these operations: registering an observation, submitting answers, selecting in
a conflict, setting a user policy version, running a build task, resuming a build task, and
registering a review presentation. Recording a Generator's results and policy re-evaluation run only
inside a build task and are not exposed. The Semantic Catalog owns Generator output and the versions
that produced it, and policy evaluation is its own judgement with nothing for the application to
supply.

Running a build task is separate from the operations that create build tasks, because the
application decides when and how many run. Every operation returns the build tasks it created or
added a trigger to, and the application passes them to running a build task. Running one may call
back into capabilities the application provides: SQL execution with scope and limits, name
resolution from a Physical Catalog table to its current name, and re-retrieval of table metadata.

Operations on one domain are serializable; reads run concurrently with them and see only committed
commits.

## Common contract

**URNs** pass as opaque strings. Callers never parse them. A caller passes only URNs obtained from
the API or issued elsewhere, such as question URNs from the tracing module.

**Points.** A read takes either a Catalog Snapshot URN, meaning that commit, or "latest", meaning
the last commit when the read is received. Every read returns the Catalog Snapshot URN it read, and
the same URN always returns the same knowledge. Every operation returns the Catalog Snapshot URN of
the commit it wrote, or says it wrote nothing, so a Query Agent can move to the point where
knowledge it just confirmed is visible.

**Using party.** The application attaches the identifier of the person or Query Agent behind each
call, and the question URN when the call belongs to a question. Operations record the using party as
the deciding party of answers and selections and as the setter of policy versions. Reads and
operation outputs mask dependencies and check references that Governance denies the using party:
neither their URN nor their content is returned, only the fact that a hidden reference exists.
Withholding URNs keeps the existence and names of hidden objects from leaking, without making the
rest of the knowledge unusable.

**Authority check.** Submitting answers and selecting in a conflict take a reference to the result
of checking the deciding party's authority. The value is opaque, recorded without interpretation,
and required to be present.

**Call identifiers.** The application attaches a call identifier to every operation. A resend with
the same identifier and input writes nothing and returns the first output, so an operation whose
response was lost can be retried without applying it twice. The same identifier with a different
input is invalid input. The record of call identifiers is written in the same transaction as the
operation.

**Fact Revision content** is the shape every read returns for one Fact Revision:

- The Fact Revision, Fact, and Fact Address URNs, the value, the state at that version, and the
  applicability scope as a description plus entity URNs.
- Dependency references. A Fact dependency is a Fact Revision URN. An input dependency is an Input
  Resource URN with an Input Observation URN, plus, for a physical schema, the Physical Catalog
  table identifier and version that produced it.
- Inputs read, validation results with each check's method, items, result, and reference, and the
  range that could not be confirmed.
- Provenance: Generator, version, and build task.
- Confirmation path: machine judgement or human consent, the event it rests on, and the policy
  version.
- The deciding party when a person consented, the commit at which the Fact was last confirmed or
  checked, and whether references are masked.

Reads at a point add whether new candidates exist at the same Fact Address and whether any
depended-on input has a newer observation.

**Errors.** A failed operation writes nothing, neither in the log nor outside it. A Generator
failing inside a build task is not an error; the task is returned as `failed`. Errors have a kind,
the identifier of their target, and a reason:

| Kind | When |
| --- | --- |
| Invalid input | A required input is missing or malformed, the predicate is not valid for the element's kind, or a call identifier is reused with a different input |
| Target not found | An input identifier points to no record |
| Point out of range | A Catalog Snapshot URN names a commit not yet written |
| No authority check result | An answer or selection lacks the reference |
| Not accepted in this state | A state condition of the operation is not met |
| Acquisition conditions not met | An observation at question time fails the conditions for acquisition and disclosure |
| Storage failure | Storage could not be read or written |

Registering an observation that records nothing because the content is unchanged is a success whose
output says so.

## Operations

### Registering an observation

Registering an observation has two forms.

- *Table metadata*, passed by the application for each retrieved table, with the Physical Catalog
  table identifier and its current table metadata version. The whole metadata is passed exactly as
  the connector returned it, not the part the Physical Catalog stored. A version no newer than the
  latest one already received for that table records nothing, so a retrieval that arrives late
  cannot roll the table back. A new version whose source-declared part is unchanged records only a
  derived record. Both are successes, and the output says what was recorded. The application passes
  unchanged tables at every retrieval, so a failure between the Physical Catalog's update and this
  call is repaired by the next retrieval.
- *Other observations*: documents, missing knowledge at question time, and usage feedback. The input
  is the observation kind, the Input Resource (an existing document's URN or none for a new one, the
  question URN for missing knowledge, none for feedback), and the content. Feedback can also carry
  related Fact URNs, free context, and a question URN. The output returns the Input Resource and
  Input Observation URNs and whether anything was recorded. Observations that fail the acquisition
  conditions are refused.

### Submitting answers

Submitting answers records one or more answers from one deciding party in one commit. Each answer
names the Fact, whether it answers a confirmation or a retirement proposal, the answer type, the
wording (with the given value or condition for a correction), and the review presentation answered.
The output gives, per answer, its event and resulting transition or the reason for none. The reason
is an unknown answer, a rejection or correction of a retirement proposal, unconfirmed dependencies,
or a judgement already made by another path. The output also lists the feedback registered for each
correction and the Facts moved by cascade. If any answer fails, the whole submission fails and
identifies it, so part of a submission is never recorded. At question time a review presentation is
registered first, so answers can reference it and, through it, the question.

### Selecting in a conflict

Selecting in a conflict names the conflicting Fact Address, the Fact selected, the Facts not
selected, the authority-check reference, the wording, and the review presentation. It returns the
`select` event and the resulting selected Fact. If the eligible Facts changed through replacement or
retirement since the presentation, it is not accepted, and the deciding party reads the new point
before choosing.

### Setting a user policy version

Setting a user policy version takes the preset, the treatment of `declared-unenforced`, and the
semantics owner's reason, and returns the version's log record identifier and the re-evaluation
build task. Beforehand, the application shows the semantics owner what the check below reports.

### Running and resuming a build task

Running a build task takes a `created` build task, runs it to the end, and returns `succeeded` or
`failed` with its result, the missing inputs when there was no candidate, or the reason for failure.
A Generator run or re-evaluation records its result in one commit. An impact judgement creates build
tasks for the Generators of the Facts it selects. Resuming a build task does the same for a `failed`
task on the latest state.

### Registering a review presentation

Registering a review presentation records that a judgement was requested: the presentation kind, the
target Facts with their Fact Addresses (several Fact Addresses may be presented together), the
person asked, which need not be the using party, the time, and a reference to the screen or dialogue
shown. It returns an identifier that answers and selections reference. The Semantic Catalog fills in
the resulting events when they are recorded. Review orchestration decides what to present; this
operation records what was presented.

## Reads

### Finding elements

Finding elements returns the URN and kind of elements whose existence Fact is selected at the point,
optionally filtered by element kind, by parent element (the Fields of a Dataset), or by the written
form of a business term, which returns the elements its selected referenced-element Facts point to.
It does not interpret question wording; finding elements from a question is the Query Agent's work.

### Requesting knowledge

Requesting knowledge takes a point, a sequence of requests, and optionally one set of entity URNs
the question mentions. A request is a target element and one of its kind's predicates. An edge whose
URN is unknown can be given by its kind and the URNs of its ends in direction order (any order if
undirected), without its identifying part. For each request the Semantic Catalog:

1. Resolves the target elements: the given element, or every edge of that kind between those ends up
   to the point.
2. Collects every Fact Address of the predicate on each element, whatever its applicability scope.
3. Judges each Fact Address at the point as described in
   [`catalog-snapshot.md`](catalog-snapshot.md#what-the-query-agent-receives), returning the
   selected Fact with its new candidates, or a status with its judgement material. Candidates
   awaiting judgement say whether they await consent or their dependencies.
4. If entity URNs were given, reports for each Fact Address whether its applicability scope overlaps
   them, does not overlap, or is unrestricted. Non-overlapping Fact Addresses are still returned.

An unknown element URN is a target-not-found error. A nonexistent edge, or an element with no Fact
Address for the predicate, is missing knowledge. Free-description predicates cannot be requested by
name, because they have no identity of their own.

### Retrieving a Fact Revision

Retrieving a Fact Revision takes a Fact Revision URN, or a Fact URN and a point, and returns its
content, whether or not the Fact is currently selected. The citation for an answer to a question is
the Fact Revision URNs used and the Catalog Snapshot URN read. The Query Agent reports these to the
trace record, and everything else, such as validation results and confirmation path, is retrievable
from them.

### Retrieving the review list

Retrieving the review list returns, at a point and optionally filtered by presentation kind, element
or Fact Address, or question, the items awaiting a person:

- Candidates with confirmed dependencies whose latest evaluation requested consent.
- Open retirement proposals whose latest evaluation requested consent.
- Conflicting Fact Addresses.

Items answered unknown without a new dependency reference since are excluded. Each item carries the
evaluation that requested the judgement and any earlier presentations of it. Items are ordered by
the commit at which the need for judgement arose. That is not a presentation order, which review
orchestration decides.

### Retrieving build tasks

Retrieving build tasks returns, by identifier or filtered by state and kind, each task's state,
kind, triggers, Generator and version, input versions, evaluation version, and result.

### Checking a user policy version

Checking a user policy version evaluates a proposed setting at the latest point and returns the
current version, the number and URNs of candidates that would be confirmed by machine judgement, and
the point evaluated. A commit between this read and setting the version can change the actual
outcome.

## One question, end to end

1. **Fix the point.** The Query Agent finds elements at "latest": the sales Measure that the
   business term "sales" refers to, and the e-commerce order and POS purchase Datasets. It keeps the
   returned Catalog Snapshot URN for the whole question.
2. **Request knowledge.** At that point it requests the Measure's aggregation rule and reference
   time, and the set relationship and business definition of the customer-to-member correspondence,
   given by edge kind and its two Fields.
3. **Receive.** The Measure's predicates come back as selected Facts. The business definition cannot
   be checked against data, so under `auto` it comes back as awaiting judgement with the candidate
   awaiting consent.
4. **Judge.** If the user is the deciding party, the application registers a review presentation and
   submits the user's consent; the Query Agent rereads at the returned point. Otherwise confirmation
   waits for the semantics owner, and the Query Agent proceeds with unconfirmed knowledge only if
   the user explicitly asks.
5. **Cite.** The Query Agent reports the Fact Revision URNs it used and the Catalog Snapshot URN it
   read to the trace record.

When requested knowledge is missing, the application registers the part of the trace record used for
construction as an observation under the question URN and runs the build tasks returned, so
Generators produce candidates on the spot.
