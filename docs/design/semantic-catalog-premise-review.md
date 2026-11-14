# When is a candidate whose premises are unconfirmed evaluated and presented?

## Current answer

As soon as each premise dependency is either confirmed or itself a candidate awaiting consent. The
candidate is then evaluated, and if it needs consent it is listed in review together with its
premises, marked as resting on them. An evaluation that adopts it takes effect when the last premise
is confirmed. Its confirmation always waits for its premises, and a rejected premise rejects it by
cascade.

## Rationale

Every Fact about an element rests on the element's existence Fact. If a candidate waited for its
premises to be confirmed before evaluation, a new Measure would take one round of review for its
existence and another for its aggregation rule and filter, and every chain of new elements would
add rounds. In the prototype, about half of the review rounds came only from this.

Waiting protected against wasted judgement: consent to a candidate whose premise is later rejected
is lost. Presenting the premise and what rests on it together, with the cascade visible, removes
most of that waste, because the deciding party judges the premise first and sees what a rejection
takes with it. Extra rounds, in contrast, are a direct cost to the deciding party.

Evaluation happens ahead of confirmation because the policy evaluation model is a language model:
whether a dependent needs consent cannot be computed cheaply at read time, so it is decided once and
recorded.

## Consequences

- Answers in one submission apply in dependency order, so the deciding party can consent to a
  premise and its dependents at once.
- A premise that returns to `candidate` starts a new judgement, and its dependents are evaluated
  again.
- Review items carry links to their premise items, and per-table grouping keeps an element's
  existence and its predicates together.
