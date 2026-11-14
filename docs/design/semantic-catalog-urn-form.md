# How are Semantic Catalog URNs formed?

**[Planned]** This decision is accepted but not implemented yet.

## Current answer

URNs are built so that a person reading one can tell roughly what it names. Datasets and Fields take
their identifiers from physical names (data-source registration, namespace, table, column), other
nodes from a slug the proposing Generator gives, and Fact Addresses from their element and
predicate. A renamed table or column is a new element. The form is not a contract: callers never
parse a URN, programs read exact names from Facts, and the text of a URN is never knowledge.

## Rationale

Most of what a catalog holds is about tables, columns, and Measures. With opaque issued numbers,
every review item, citation, and log entry needs a lookup before a person can tell what it is about,
which costs far more than the cases opaque identifiers protect.

Those cases are renames and names that turn out to be wrong. Renames are rare, and the Physical
Catalog already treats a renamed table as a new resource identifier. Making a renamed table or
column a new element keeps a name-based URN true, and names cannot tell a rename from a drop and an
add anyway. The knowledge built on the old element is proposed again on the new one through
ordinary evaluation.

A slug names what a claim is about, not the claim. Sameness of Concepts, Measures, Dimensions, and
business terms is still decided only by the proposer pointing to an existing element, so two
distinct Measures that a model names alike stay distinct: the Semantic Catalog appends a sequence
number at acceptance. A slug is a business name such as `ec-sales`, not a definitional attribute
such as tax treatment, so later Facts rarely contradict it.

Keeping the form out of the contract leaves room to change how new URNs are built, and keeps
programs from depending on a structure that would then have to be maintained forever.

## Consequences

- An element's URN never changes, even when a later Fact shows its slug was ill-chosen. A correction
  that contradicts the slug itself is proposed as a new element with a retirement proposal for the
  old one; other corrections replace values on the same element.
- Retiring a renamed column cascades to the Facts that rest on it, and their Generators propose
  them again, so a rename costs judgement again, adopted by machine where the signals allow.
- Interfaces may show URNs to people as they are, but must not shorten or interpret them by their
  structure.
