# What identifies a table in the Physical Catalog?

**[Planned]** This decision is accepted but not implemented yet.

## Current answer

A resource identifier, issued per combination of data-source registration, namespace, and table name
and kept until the registration is removed. Source-side identifiers such as a PostgreSQL table OID
do not determine it, and the Physical Catalog does not judge whether a renamed or re-created table
is the same table.

## Rationale

Planning and name resolution work on SQL names, and the catalog's lookup key is already a name.
Identifiers based on source-side identifiers would need two rules, one for sources that return them
and one, such as MySQL, that cannot. A matching source-side identifier also does not guarantee the
same table in business terms, so the Physical Catalog would take on part of a judgement of meaning.

## Consequences

- A rename appears as "does not exist" for the old name and, once a user registers it, a new
  identifier for the new name.
- Whether a table was renamed or replaced is a Semantic Catalog Fact, proposed by a Generator from
  source-side identifiers and column structure. A Dataset follows a rename by a new
  physical-catalog-reference Fact, which replaces the old one once confirmed.
- A table dropped and re-created under the same name keeps its identifier. Sources that return
  source-side identifiers produce a new version for it.
