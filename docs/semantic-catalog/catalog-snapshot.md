# Catalog Snapshot

A Catalog Snapshot is what consumers read: the whole catalog of one domain as derived from the log
up to one commit. This document explains which Facts it presents, how it selects one Fact per Fact
Address, what the Query Agent receives for each request, and what is materialized to make past
points readable. The records it is derived from are described in
[`record-model.md`](record-model.md).

## A projection of the log

**[Planned]** No operation creates a Catalog Snapshot. For every commit c, the Catalog Snapshot of c is determined
by the log up to c, and because that part of the log never changes, neither does the Catalog
Snapshot. A consumer that reads the same Catalog Snapshot throughout one question uses one
consistent set of knowledge, however many inputs, checks, corrections, or conflicting Facts arrive
meanwhile. Knowledge confirmed during a question appears at later commits, and the consumer reads it
by moving to a later Catalog Snapshot.

**[Planned]** All users read Catalog Snapshots of the same log. There is no private catalog per user, so confirmed
knowledge is shared and two users do not get different knowledge for the same question.

## Presentable Facts

**[Planned]** A confirmed Fact is **presentable** from the commit at which it became `confirmed` until the commit
before it became `retired`. A retired Fact can return to `candidate` with a new dependency reference
and be confirmed again, so a Fact can have several presentable periods.

**[Planned]** Two invariants keep this simple. A candidate is confirmed only once every premise dependency is
confirmed, so the premises of a confirmed Fact are always confirmed. When a Fact is retired, every
confirmed Fact that has it as a premise is retired in the same commit, so knowledge never stays
presented without its premise. Retirement propagates per Fact; a Fact that only replaced the retired
one, and Facts with no dependency on it, keep being presented. Judging presentability therefore
never requires following dependencies. Traceability also holds by construction: every confirmation
references a policy evaluation or answer, and every acceptance holds its validation result and
provenance.

**[Planned]** Presentability within the Catalog Snapshot, together with the judgement at request time of whether a
Fact applies to the requested target, is **semantic presentability**. It does not include whether
the using party may access the knowledge; that is Governance's decision.

## Selecting one Fact per Fact Address

**[Planned]** The **eligible Facts** of a Fact Address at commit c are the presentable Facts that are not
displaced. A Fact is **displaced** when another presentable Fact at the same Fact Address has its
Fact Revision among its confirmation-time dependency references, or when a conflict resolution
passed it over and the Fact chosen then has stayed presentable since. A chosen Fact retired and
confirmed again no longer carries the resolution.

| Eligible Facts | Selected Fact | Conflict |
| --- | --- | --- |
| None | **[Planned]** None | **[Planned]** No |
| One | **[Planned]** That Fact | **[Planned]** No |
| Two or more | **[Planned]** Decided from the previous commit's selection, as below | **[Planned]** Yes |

**[Planned]** With two or more eligible Facts, the selection carries over from the previous commit:

- **[Planned]** A previously selected Fact that is still eligible stays selected.
- **[Planned]** A previously selected Fact that is now displaced passes the selection to the single eligible Fact
  that depends on it, if there is exactly one.
- **[Planned]** A previously selected Fact that was retired passes the selection to the Fact it had replaced, if
  that Fact is eligible.
- **[Planned]** Otherwise a previously selected Fact that is still presentable stays selected even if displaced,
  and if none of these applies, nothing is selected.

**[Planned]** These rules give the following behavior:

- **[Planned]** **Replacement.** A new Fact made with the selected Fact as input displaces it once confirmed, and
  becomes selected. This is how ordinary updates happen.
- **[Planned]** **Conflict.** A Fact made without regard to the selected Fact leaves two eligible Facts once
  confirmed. The previous Fact stays selected, not because it was judged right but because nothing
  has yet replaced it, so knowledge in use does not stop because someone independently proposed
  another value. Two incompatible Facts confirmed in the same commit at a Fact Address with no
  selection are a conflict with nothing selected.
- **[Planned]** **Conflict resolution.** The deciding party's `resolve-conflict` displaces the passed-over Facts
  and leaves one eligible Fact. It is the only part of the selection recorded in the log, because
  it cannot be reproduced from rules. Every other selection is derived and never stored.
- **[Planned]** **Retirement.** A retired Fact stops being presentable, so the Fact it replaced and the Facts
  passed over in its favor become eligible again. Retirement thus returns treatment to what it was
  before the retired Fact was adopted.

**[Planned]** Points are commits, not records, so two Facts confirmed in one write are treated as simultaneous and
the order of records inside a commit never decides a selection.

**[Planned]** While a new candidate waits for the Facts it depends on, the previously selected Fact keeps being
presented. A revised sales definition that depends on an unconfirmed treatment of returns leaves the
current definition presented, with a note that a new candidate exists, while an unrelated store-code
Fact confirmed at the same commit is presented normally.

**[Planned]** The Catalog Snapshot of commit c contains, for each Fact Address with a selected Fact, that Fact's
latest Fact Revision up to c. Fact Revisions of Facts not selected and of candidates are not part of
it but remain retrievable as material for judgement.

## What the Query Agent receives

The Query Agent requests knowledge as pairs of a target element and a predicate, or all predicates
of an element, directly or by reading along the hierarchy of data sources and tables (see
[`public-api.md`](public-api.md#reads)). For every Fact Address of the requested predicate on that
element, whatever its applicability scope, the Semantic Catalog judges its status at the point and
names it in the result, so a consumer never infers it from what else is present.

A read returns **confirmed knowledge** by default: only the Fact Addresses with a selected Fact,
because composing a query and most other uses need nothing else. That is a `selected` Fact Address,
or a `conflict` whose selected Fact still stands, since its eligible Facts are confirmed too. Whether
`candidate` Facts exist at a Fact Address is still shown, without listing them. A caller that judges
or reports on the catalog asks for **unconfirmed knowledge** as well, and receives every Fact
Address with its status and judgement material. In both, the predicates with missing knowledge are
listed, because knowing what the catalog lacks is not unconfirmed knowledge.

When a Fact is selected and there is no conflict, its status is `selected`, with any `candidate`
Facts at the same Fact Address attached as new candidates when unconfirmed knowledge is asked for.
Otherwise one status applies, judged in this order:

| Status | Condition | Judgement material |
| --- | --- | --- |
| Conflict | Two or more eligible Facts | Each eligible Fact, and the selected Fact if any, which two replacements confirmed at once can leave outside the eligible Facts |
| Awaiting judgement | No eligible Fact, but `candidate` Facts exist, including those waiting for their dependencies | Each candidate, and whether it awaits evaluation, consent, or its dependencies |
| Missing knowledge | Neither an eligible nor a `candidate` Fact, including when only rejected or retired Facts exist | The rejected and retired Facts, if any, with the answers given to them |

Facts that differ only in applicability scope are separate Fact Addresses and are not a conflict.
The Semantic Catalog does not narrow them to one. **[Planned]** When the question names entities, it reports for
each Fact Address whether the applicability scope overlaps them, does not overlap, or is
unrestricted, and the Query Agent decides which to use in light of the question's intent, asking the
user when unsure.

The names of one business term in different languages are likewise separate Fact
Addresses (see [`languages.md`](languages.md#names-one-fact-per-language)): each language has its own selected
name, and only two names in one language conflict.

For each returned Fact the Query Agent can see its state; its checks as signals, with their bases
and the content of what they reference; whether it was confirmed by machine judgement or by
consent; the applicability scope and dependencies, with rejected or retired Facts among its inputs
and their answers; and whether new candidates exist. **[Planned]** It can also see the deciding
party when a person consented, whether machine judgement was inline or by the policy evaluation
model, and the user policy version. It can also see when the Fact was last
confirmed or checked: the commit of its latest confirmation or additional acceptance. An unchanged
re-check records nothing, so this value can make knowledge look older than it is, never newer. It
also sees whether any input the Fact depends on has an observation newer than the one depended on,
up to the commit read. Confirmed means adopted, not proven, and nothing is presented as more certain
than its signals and confirmation path show.

**[Planned]** The Query Agent may compose and run a query with **unconfirmed knowledge**, meaning candidates,
answers from a user who is not a deciding party, or its own assumptions, only when the user
explicitly asks. The result states that it rests on unconfirmed knowledge and which. Neither that
execution nor the user's acceptance of its result is evidence that the knowledge is correct, and the
execution adds nothing to the log. Having been provided or used is never itself evidence that a Fact
is correct.

**[Planned]** Finding which knowledge a question needs belongs to the Query Agent. The Semantic Catalog does not
guarantee to find conditions it was not asked for or to judge the safety of every question.

## Materialization

**[Planned]** Reads at any past commit must not require replaying the log, so the following derivations are
materialized and updated in the same commit as the Fact Events that change them:

| Derived from the log | Materialized | Why |
| --- | --- | --- |
| Current state of each Fact Record | **[Planned]** Yes, the whole record, equal to folding the Fact's events | **[Planned]** Every write checks whether the next Fact Event is accepted, and current lists and review read it |
| Presentable periods of each Fact | **[Planned]** Yes, as pairs of confirmation and retirement commits | **[Planned]** Used whenever eligible Facts are determined |
| Selected and eligible Facts per Fact Address | **[Planned]** Yes, each with the commit range in which it holds | **[Planned]** Lets a Query Agent keep reading a past point without replay |
| Content of a Fact Revision | **[Planned]** The current one, as part of the Fact Record; an earlier one is computed by applying the Fact's events up to it | **[Planned]** Catalog screens list and search current content; a past point needs earlier content only for Facts changed since, and keeping every version whole would copy the content at every event |
| Whether a depended-on input has a newer observation | **[Planned]** No; compared against the latest observation of the same Input Resource | **[Planned]** Observations per Input Resource are ordered by commit |

**[Planned]** The invariant that materialized records equal the result of replaying the log from empty is
mandatory, and it is checked by doing exactly that comparison (see
[`storage.md`](storage.md#replay-check)).
