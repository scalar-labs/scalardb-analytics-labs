# Which user policy does a new Semantic Catalog domain start with?

## Current answer

The `auto` preset, with `declared-unenforced` not counted as a deterministic validation method.
Machine judgement adopts a candidate inline when deterministic checks support all of its content, no
item that restricts what the data may hold rests on data checks alone, nothing refutes it, and it
would not confirm a second Fact where one is already selected. A refuted candidate or a second
Fact always goes to a person. **[Planned]** The policy evaluation model decides every other
candidate, adopting it or sending it to the deciding party. `full-auto` and `manual` remain
available, and the semantics owner can change the setting at any time.

## Rationale

The product fixes no condition under which human consent is mandatory. Organizations differ in how
they weigh immediate review load against later corrections and lost trust, and each chooses how far
to delegate. If the user organization loosens the setting, the risk of a wrong adoption falls on
that organization's data consumers, so every Fact shows its confirmation path and policy version to
consumers.

`auto` sends to a person what the signals leave in doubt, not everything that is not deterministic.
Generators propose with models, so requiring consent for every non-deterministic candidate would
send most of a catalog's meaning to the deciding party, including Field meanings on which the name,
the sample values, and a registered glossary all agree (see
[`semantic-catalog-machine-judgement.md`](semantic-catalog-machine-judgement.md)). `manual` remains
for organizations that want every candidate seen.

The inline path stays narrow because it calls no model and so must not adopt what deterministic
signals leave open. Unenforced declarations are excluded from it because analytical sources often
declare primary keys without enforcing them; adopting them inline would confirm a key that repeats
in the data as unique, and data consumers would receive aggregates that count rows twice. Such keys,
and uniqueness or set relationships that only data supports, go to the policy evaluation model
instead, with the range read in view. A data check that found no counterexample is not enough on its
own for an item that restricts what the data may hold: data shows what holds now, often in a sample,
not what the business allows. A declaration, enforced or not, supplies that intent, so a declared
uniqueness with no duplicates found is still adopted inline. A result resting on a counterexample is
adopted inline, because erring there only makes queries more cautious.

## Consequences

- **[Planned]** Changing the policy re-evaluates waiting candidates, so loosening it can confirm
  many at once. The application must show the semantics owner which candidates the new version would
  adopt inline and which would go to the policy evaluation model before applying it.
