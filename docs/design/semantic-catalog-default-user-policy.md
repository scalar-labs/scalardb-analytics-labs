# Which user policy does a new Semantic Catalog domain start with?

**[Planned]** This decision is accepted but not implemented yet.

## Current answer

The `auto` preset, with `declared-unenforced` not counted as a deterministic validation method.
Machine judgement confirms a candidate only when deterministic checks support all of its content,
nothing refutes it, and it would not confirm a second Fact where one is already selected. Everything
else goes to the deciding party. `full-auto` and `manual` remain available, and the semantics owner
can change the setting at any time.

## Rationale

The product fixes no condition under which human consent is mandatory. Organizations differ in how
they weigh immediate review load against later corrections and lost trust, and each chooses how far
to delegate. The default is the choice made for organizations that make no choice, so it sits on the
cautious side. If the user organization loosens it, the risk of a wrong confirmation falls on that
organization's data consumers, so every Fact shows its confirmation path and policy version to
consumers.

Under `auto`, nothing that data and enforced declarations cannot verify is confirmed without a
person. Field meaning, business definitions, and model inference all reach the deciding party, while
existence, technical references, and enforced or data-checked constraints do not consume their
attention.

Unenforced declarations are excluded because analytical sources often declare primary keys without
enforcing them. Counting them would confirm a key that repeats in the data as unique, and data
consumers would receive aggregates that count rows twice. The cost is small, because the uniqueness
check can still confirm such a key from data under `auto`. Consent is needed only when the data
check cannot run.

## Consequences

- Keys and joins from unenforced declarations need consent, because unique data does not prove a
  business identifier.
- Changing the policy re-evaluates waiting candidates, so loosening it can confirm many at once. The
  application must show the semantics owner which candidates the new version would confirm before
  applying it.
