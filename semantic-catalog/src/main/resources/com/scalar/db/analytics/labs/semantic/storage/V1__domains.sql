-- The domains of the Semantic Catalog. Every table has the domain as the first column of its
-- primary key.

CREATE TABLE semantic_catalog.domains (
    domain text PRIMARY KEY,
    created_at timestamptz NOT NULL
);
