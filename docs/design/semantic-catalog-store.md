# Where does the Semantic Catalog store its records?

**[Planned]** This decision is accepted but not implemented yet.

## Current answer

In its own `semantic_catalog` schema inside the Physical Catalog's PostgreSQL database, accessed
directly through Spring Data JDBC and JDBC rather than through ScalarDB. The Physical Catalog and
the Semantic Catalog share the database, keep separate schemas, and never read each other's.

## Rationale

The Semantic Catalog's reads and writes need queries that ScalarDB SQL cannot express. Reads at a
past point search materialized records by commit range. Identity of edges, Fact Addresses, and Facts
is enforced by unique constraints, and the order of writes by the primary key of `commits`.

The records share the Physical Catalog's database so that the user organization does not have to
provide and operate another database.

## Consequences

- The store must be PostgreSQL. The Semantic Catalog depends on `REPEATABLE READ` snapshots and
  `jsonb`, and fails at startup on any other database. The Physical Catalog alone accepts any
  database ScalarDB JDBC supports, so this decision narrows the workspace's store to PostgreSQL, and
  the product depends on a PostgreSQL server that the user organization provides and operates.
- The Semantic Catalog runs its own schema migrations and connection pool. The Physical Catalog's
  migrations belong to the embedded upstream catalog, and its connections live inside ScalarDB.
- The application must refuse to register the store's database as a data source, and the store's
  credentials must never reach the Query Engine. Otherwise SQL written by users or models could read
  or change the log through DuckDB.
- Tables and SQL stay behind a storage adapter, so moving to another store later changes the
  adapter, not the domain model or the public API.
