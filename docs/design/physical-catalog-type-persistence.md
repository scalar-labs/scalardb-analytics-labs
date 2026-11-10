# How does the Physical Catalog persist Analytics types?

**[Planned]** This decision is accepted but not implemented yet.

## Current answer

As normalized relational tables in its ScalarDB store, designed as for an ordinary RDBMS on the
premise that ScalarDB SQL has joins. Structure the database schema must expose, such as type
category, nesting, sharing, and references, is never hidden in JSON. Parts the database need not
treat as structure, such as the parameters of extension-encoded types, may be JSON. Scalar and
structured types use differently shaped tables: `ARRAY` and `MAP` hold their fixed parameters as
columns of their own row, and `STRUCT` holds its fields as a child relation shaped like the relation
between a table and its columns. No table pairs a discriminator with nullable columns that vary by
category.

## Rationale

Planning, browsing, and migration all need to see a type's category and nesting. The shapes not
taken each hide or distort that:

- Serializing a whole type as one JSON document makes category, nesting, and sharing invisible to
  the database schema.
- A generic tree-node table of parent, role, and order forces `ARRAY` and `MAP`, which have only
  fixed parameters, into a variable-length frame.
- Keying types by source type fails because an enum's member list has no length bound and one type
  has several notations.

## Consequences

- Compatibility with the existing Physical Catalog's persistence is not a constraint, so the
  existing schema is migrated rather than preserved.
