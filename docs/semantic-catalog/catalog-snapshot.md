# Catalog Snapshot

**[Planned]** This document describes accepted design that is not implemented yet.

A Catalog Snapshot is what consumers read: the whole catalog of one domain as derived from the log
up to one commit. This document explains which Facts it presents, how it selects one Fact per Fact
Address, what the Query Agent receives for each request, and what is materialized to make past
points readable. The records it is derived from are described in
[`record-model.md`](record-model.md).

## A projection of the log

No operation creates a Catalog Snapshot. For every commit c, the Catalog Snapshot of c is determined
by the log up to c, and because that part of the log never changes, neither does the Catalog
Snapshot. A consumer that reads the same Catalog Snapshot throughout one question uses one
consistent set of knowledge, however many inputs, checks, corrections, or conflicting Facts arrive
meanwhile. Knowledge confirmed during a question appears at later commits, and the consumer reads it
by moving to a later Catalog Snapshot.

All users read Catalog Snapshots of the same log. There is no private catalog per user, so confirmed
knowledge is shared and two users do not get different knowledge for the same question.

## Presentable Facts

A confirmed Fact is **presentable** from the commit at which it became `confirmed` until the commit
before it became `retired`. A retired Fact can return to `candidate` with a new dependency reference
and be confirmed again, so a Fact can have several presentable periods.

Two invariants keep this simple. A candidate is confirmed only once every premise dependency is
confirmed, so the premises of a confirmed Fact are always confirmed. When a Fact is retired, every
confirmed Fact that has it as a premise is retired in the same commit, so knowledge never stays
presented without its premise. Retirement propagates per Fact; a Fact that only replaced the retired
one, and Facts with no dependency on it, keep being presented. Judging presentability therefore
never requires following dependencies. Traceability also holds by construction: every confirmation
references a policy evaluation or answer, and every acceptance holds its validation result and
provenance.

Presentability within the Catalog Snapshot, together with the judgement at request time of whether a
Fact applies to the requested target, is **semantic presentability**. It does not include whether
the using party may access the knowledge; that is Governance's decision.

## Selecting one Fact per Fact Address

The **eligible Facts** of a Fact Address at commit c are the presentable Facts that are not
displaced. A Fact is **displaced** when another presentable Fact at the same Fact Address has its
Fact Revision among its confirmation-time dependency references, or when a conflict resolution
passed it over and the Fact chosen then has stayed presentable since. A chosen Fact retired and
confirmed again no longer carries the resolution.

| Eligible Facts | Selected Fact | Conflict |
| --- | --- | --- |
| None | None | No |
| One | That Fact | No |
| Two or more | Decided from the previous commit's selection, as below | Yes |

With two or more eligible Facts, the selection carries over from the previous commit:

- A previously selected Fact that is still eligible stays selected.
- A previously selected Fact that is now displaced passes the selection to the single eligible Fact
  that depends on it, if there is exactly one.
- A previously selected Fact that was retired passes the selection to the Fact it had replaced, if
  that Fact is eligible.
- Otherwise a previously selected Fact that is still presentable stays selected even if displaced,
  and if none of these applies, nothing is selected.

These rules give the following behavior:

- **Replacement.** A new Fact made with the selected Fact as input displaces it once confirmed, and
  becomes selected. This is how ordinary updates happen.
- **Conflict.** A Fact made without regard to the selected Fact leaves two eligible Facts once
  confirmed. The previous Fact stays selected, not because it was judged right but because nothing
  has yet replaced it, so knowledge in use does not stop because someone independently proposed
  another value. Two incompatible Facts confirmed in the same commit at a Fact Address with no
  selection are a conflict with nothing selected.
- **Conflict resolution.** The deciding party's `resolve-conflict` displaces the passed-over Facts
  and leaves one eligible Fact. It is the only part of the selection recorded in the log, because
  it cannot be reproduced from rules. Every other selection is derived and never stored.
- **Retirement.** A retired Fact stops being presentable, so the Fact it replaced and the Facts
  passed over in its favor become eligible again. Retirement thus returns treatment to what it was
  before the retired Fact was adopted.

Points are commits, not records, so two Facts confirmed in one write are treated as simultaneous and
the order of records inside a commit never decides a selection.

While a new candidate waits for the Facts it depends on, the previously selected Fact keeps being
presented. A revised sales definition that depends on an unconfirmed treatment of returns leaves the
current definition presented, with a note that a new candidate exists, while an unrelated store-code
Fact confirmed at the same commit is presented normally.

The Catalog Snapshot of commit c contains, for each Fact Address with a selected Fact, that Fact's
latest Fact Revision up to c. Fact Revisions of Facts not selected and of candidates are not part of
it but remain retrievable as material for judgement.

## What the Query Agent receives

The Query Agent requests knowledge as pairs of a target element and a predicate, or all predicates
of an element, directly or by reading along the hierarchy of data sources and tables (see
[`public-api.md`](public-api.md#reads)). For every Fact Address of the requested predicate on that
element, whatever its applicability scope, the Semantic Catalog returns either the selected Fact or
a status.

When a Fact is selected and there is no conflict, the Fact is returned without a status, with any
`candidate` Facts at the same Fact Address attached as new candidates. Otherwise one status applies,
judged in this order:

| Status | Condition | Judgement material |
| --- | --- | --- |
| Conflict | Two or more eligible Facts | Each eligible Fact, and the selected Fact if any, which two replacements confirmed at once can leave outside the eligible Facts |
| Awaiting judgement | No eligible Fact, but `candidate` Facts exist, including those waiting for their dependencies | Each candidate, and whether it awaits evaluation, consent, or its dependencies |
| Missing knowledge | Neither an eligible nor a `candidate` Fact, including when only rejected or retired Facts exist | The rejected and retired Facts, if any, with the answers given to them |

Facts that differ only in applicability scope are separate Fact Addresses and are not a conflict.
The Semantic Catalog does not narrow them to one. When the question names entities, it reports for
each Fact Address whether the applicability scope overlaps them, does not overlap, or is
unrestricted, and the Query Agent decides which to use in light of the question's intent, asking the
user when unsure.

For each returned Fact the Query Agent can see its state; its checks as signals, with their bases
and the content of what they reference; the deciding party when a person consented; the confirmation
path, inline machine judgement, the policy evaluation model, or consent, and the user policy
version; the applicability scope and dependencies, with rejected or retired Facts among its inputs
and their answers; and whether new candidates exist. It can also see when the Fact was last
confirmed or checked: the commit of its latest confirmation or additional acceptance. An unchanged
re-check records nothing, so this value can make knowledge look older than it is, never newer. It
also sees whether any input the Fact depends on has an observation newer than the one depended on,
up to the commit read. Confirmed means adopted, not proven, and nothing is presented as more certain
than its signals and confirmation path show.

The Query Agent may compose and run a query with **unconfirmed knowledge**, meaning candidates,
answers from a user who is not a deciding party, or its own assumptions, only when the user
explicitly asks. The result states that it rests on unconfirmed knowledge and which. Neither that
execution nor the user's acceptance of its result is evidence that the knowledge is correct, and the
execution adds nothing to the log. Having been provided or used is never itself evidence that a Fact
is correct.

Finding which knowledge a question needs belongs to the Query Agent. The Semantic Catalog does not
guarantee to find conditions it was not asked for or to judge the safety of every question.

## Materialization

Reads at any past commit must not require replaying the log, so the following derivations are
materialized and updated in the same commit as the Fact Events that change them:

| Derived from the log | Materialized | Why |
| --- | --- | --- |
| Current state of each Fact Record | Yes, the whole record, equal to folding the Fact's events | Every write checks whether the next Fact Event is accepted, and current lists and review read it |
| Presentable periods of each Fact | Yes, as pairs of confirmation and retirement commits | Used whenever eligible Facts are determined |
| Selected and eligible Facts per Fact Address | Yes, each with the commit range in which it holds | Lets a Query Agent keep reading a past point without replay |
| Content of a Fact Revision | The current one, as part of the Fact Record; an earlier one is computed by applying the Fact's events up to it | Catalog screens list and search current content; a past point needs earlier content only for Facts changed since, and keeping every version whole would copy the content at every event |
| Whether a depended-on input has a newer observation | No; compared against the latest observation of the same Input Resource | Observations per Input Resource are ordered by commit |

The invariant that materialized records equal the result of replaying the log from empty is
mandatory, and it is checked by doing exactly that comparison (see
[`storage.md`](storage.md#replay-check)).
