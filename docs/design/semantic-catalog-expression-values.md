# How are expression values in the Semantic Catalog represented?

Acceptance and analytical semantics inference implement this decision for a Measure's aggregation
rule and filter conditions, for a classification level's expression, and for a calendar level's
expression, which reads only the time value.

## Current answer

The value expression of a Measure's aggregation rule, a Measure's filter conditions, and a
classification level's expression are SQL expression strings in the Query Engine's dialect, over
the column names of one table: the base Dataset's table for a Measure, and the classified Field's
table for a classification. The string is the reference representation of the definition. At
acceptance the Semantic Catalog parses it with the Query Engine's parser, records the Fields it
references as premise dependencies, and refuses an expression that does not parse or names a
missing column. Names the parser reads as columns but the Query Engine resolves to values of the
session, such as `current_date`, are not columns unless the table has a column of that name, as the
Query Engine binds them.

A calendar level's expression is the same kind of string over one column, `time_value`, the time a
Measure's reference time Field records. It is parsed the same way and refused when it reads any
other column. It adds no premise dependency, because a calendar is bound to no table and its
column is no Field.

## Rationale

These values are written by language-model Generators, judged by the policy evaluation model and the
deciding party, and used by a Query Agent composing SQL. A SQL expression is what all three read and
write well, and the product's Query Engine runs it as written. An engine-neutral structure such as
Substrait would let any engine consume the value, but neither a person nor a model can read or write
it reliably, and the product has one Query Engine.

A string hides which columns it uses, so parsing it at acceptance turns the columns into premise
dependencies. A column that disappears then cascades to the definitions that use it, which matters
because a renamed column is a new element.

## Consequences

- Running a definition on another engine means translating it from the reference representation;
  the product guarantees that the expression is the definition, not that it runs unchanged
  everywhere.
- If an engine-neutral form is needed later, it is derived from the stored string; the stored form
  does not change.
- Generators validate their expressions before proposing them, and the acceptance check catches
  what they miss.
