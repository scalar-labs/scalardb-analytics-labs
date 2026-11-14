# How does machine judgement decide what deterministic signals do not settle?

## Current answer

Under `auto`, a language model kept separate from the Generators, the policy evaluation model,
weighs the candidate's signals as a whole and either adopts it or sends it to the deciding party
with review material it builds. Candidates that deterministic signals cover are adopted inline
without it. A refuted candidate, or one that would confirm a second Fact where one is selected,
always goes to a person. The policy evaluation model runs as its own build task, and its reason and
version are recorded.
It reaches a language model through the Semantic Catalog's adapter (see
[`../semantic-catalog/generators.md`](../semantic-catalog/generators.md#reaching-a-language-model)).
**[Planned]** The application supplies the adapter and model a domain's workspace configuration
names.

## Rationale

Generators propose with models, so their candidates rest on fuzzy signals: a column name, sample
values, a quoted document, a model's reading. Checks are signals, not proof, and no predicate has a
set of validation items known in advance to be sufficient for its value to hold. A fixed test over
methods or items cannot say when to accept such a candidate; requiring consent for everything that
is not deterministic instead sends most of a catalog's meaning to the deciding party, including
items that any reader would accept at a glance. Accepting fuzzy proposals is therefore a fuzzy
judgement too.

Making that judgement a separate model, rather than the proposing Generator's own assessment,
keeps producing and accepting candidates apart, as human consent does. The model reads the same
expanded material a review item shows, so the reading that decided not to adopt a candidate is
what the deciding party sees; building the review material in the same step avoids a second
reading by each interface.

The judgement calls a model, so it cannot run inside the operation that makes a candidate
evaluable, such as an answer submission, without holding that operation up. Running it as a build
task keeps answers quick and lets an interactive session present what is prepared.

Deterministic signals cover most of a catalog's structure, such as existence, technical references,
and enforced constraints. Adopting those inline keeps model calls for the candidates that need
judgement.

## Consequences

- Every Fact the policy evaluation model adopts shows that path, its version (model and prompt), and
  its reason, so consumers know how it was adopted, and Facts adopted by an earlier model can be
  found.
- Review has two queues: candidates awaiting evaluation, as build tasks, and candidates sent to a
  person, as the review list.
- How far an organization delegates judgement to the machine is fixed by the preset for now.
  Modeling and quantifying the policy so organizations can configure it comes later, and its
  quantities will themselves be a model's fuzzy assessments.
