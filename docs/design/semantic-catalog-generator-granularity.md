# What is the unit of a Semantic Catalog Generator?

The schema-interpretation and data-check Generators implement this decision. **[Planned]** The
meaning-inference Generators, and running a Generator again after a rejection, correction, or
retirement, are not implemented yet.

## Current answer

One interpretation or check rule is one Generator. Deriving a key from a primary key, deriving an
identifier correspondence from a foreign key, and checking uniqueness against data are three
Generators, not parts of a schema Generator or a data Generator.

## Rationale

Provenance records the Generator and its version on every acceptance. With one rule per Generator,
provenance says which rule produced a candidate, and when a rule is revised and its version raised,
the Facts it affected can be selected from provenance.

Validation capability is declared per Generator. One rule has one pair of predicate and validation
method, so the declaration stays exact.

After a rejection, correction, or retirement, the Generator that produced the Fact runs again.
Separate rules keep unrelated rules from rerunning.

Adding, stopping, or revising a rule becomes the same act as adding, stopping, or versioning a
Generator, with no second mechanism inside a Generator to switch rules.

## Consequences

- More Generators mean more build tasks. Duplicate runs are held down by adding new triggers to a
  Generator's build task that is still `created`, and the application decides how many run at once.
- Execution order is not a property of a bundle. It follows from each Generator declaring which
  confirmed predicates it reads.
- A rule that would read new input kinds or produce new predicates is added as a new Generator once
  those are defined.
