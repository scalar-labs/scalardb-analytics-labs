# Which user policy does a new Semantic Catalog domain start with?

**[Planned]** This decision is accepted but not implemented yet.

## Current answer

The `auto` preset, with `declared-unenforced` not counted as a deterministic validation method.
Machine judgement confirms a candidate only when deterministic checks support all of its content,
no item that restricts what the data may hold rests on data checks alone, nothing refutes it, and it
would not confirm a second Fact where one is already selected. Everything
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
check can still confirm the declared uniqueness from data under `auto`; only the key itself, a claim
about business identity, needs consent.

A data check that found no counterexample is not enough on its own for an item that restricts what
the data may hold, such as a Field set that does not repeat. Data shows what holds now, often in a
sample, not what the business allows: purchase rows whose member IDs do not repeat only because each
member has bought once would, once confirmed, let a Query Agent omit fan-out handling and
double-count later. A declaration, enforced or not, supplies the intent that data cannot, so a
declared uniqueness with no duplicates found is still confirmed by machine judgement. A result
resting on a counterexample, such as a Field set that repeats, is confirmed by machine judgement,
because erring there only makes queries more cautious.

## Consequences

- Keys and correspondences from unenforced declarations need consent, because unique data does not
  prove a business identifier.
- Uniqueness and set relationships that only data supports need consent, with the range read shown.
- Changing the policy re-evaluates waiting candidates, so loosening it can confirm many at once. The
  application must show the semantics owner which candidates the new version would confirm before
  applying it.
