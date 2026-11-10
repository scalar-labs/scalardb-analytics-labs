-- The log of each domain, and the records its creating log records materialize.

-- One row per commit. Writers of one domain collide on the primary key, which serializes them.
CREATE TABLE semantic_catalog.commits (
    domain text NOT NULL REFERENCES semantic_catalog.domains (domain),
    commit_number bigint NOT NULL,
    committed_at timestamptz NOT NULL,
    operation text NOT NULL,
    author text NOT NULL,
    PRIMARY KEY (domain, commit_number)
);

-- The append-only log. A record's position is its commit number and its order within the commit.
CREATE TABLE semantic_catalog.log_records (
    domain text NOT NULL,
    commit_number bigint NOT NULL,
    ordinal integer NOT NULL,
    kind text NOT NULL,
    target_fact text,
    target_input_resource text,
    content jsonb NOT NULL,
    PRIMARY KEY (domain, commit_number, ordinal),
    FOREIGN KEY (domain, commit_number) REFERENCES semantic_catalog.commits (domain, commit_number)
);

CREATE INDEX log_records_target_fact
    ON semantic_catalog.log_records (domain, target_fact)
    WHERE target_fact IS NOT NULL;

CREATE INDEX log_records_target_input_resource
    ON semantic_catalog.log_records (domain, target_input_resource)
    WHERE target_input_resource IS NOT NULL;

CREATE TABLE semantic_catalog.elements (
    domain text NOT NULL,
    element text NOT NULL,
    kind text NOT NULL,
    parent text,
    created_commit bigint NOT NULL,
    edge_end_a text,
    edge_end_b text,
    edge_directed boolean,
    edge_identity_key text,
    edge_identifying_part jsonb,
    PRIMARY KEY (domain, element)
);

-- An edge is the same when kind, ends, direction, and identifying part match.
CREATE UNIQUE INDEX elements_edge_identity
    ON semantic_catalog.elements
        (domain, kind, edge_end_a, edge_end_b, edge_directed, edge_identity_key)
    WHERE edge_end_a IS NOT NULL;

CREATE INDEX elements_parent
    ON semantic_catalog.elements (domain, parent)
    WHERE parent IS NOT NULL;

CREATE TABLE semantic_catalog.fact_addresses (
    domain text NOT NULL,
    fact_address text NOT NULL,
    element text NOT NULL,
    predicate text NOT NULL,
    scope_description text,
    scope_entities jsonb NOT NULL,
    predicate_definition text,
    created_commit bigint NOT NULL,
    PRIMARY KEY (domain, fact_address)
);

-- An empty-scope Fact Address of a closed-core or Generator-declared predicate is found by element
-- and predicate.
CREATE UNIQUE INDEX fact_addresses_empty_scope
    ON semantic_catalog.fact_addresses (domain, element, predicate)
    WHERE scope_description IS NULL AND predicate_definition IS NULL;

CREATE INDEX fact_addresses_element
    ON semantic_catalog.fact_addresses (domain, element, predicate);

CREATE TABLE semantic_catalog.facts (
    domain text NOT NULL,
    fact text NOT NULL,
    fact_address text NOT NULL,
    identity_key text,
    created_commit bigint NOT NULL,
    PRIMARY KEY (domain, fact)
);

-- A closed-range Fact is the same when its value matches at the same Fact Address.
CREATE UNIQUE INDEX facts_matching_value
    ON semantic_catalog.facts (domain, fact_address, identity_key)
    WHERE identity_key IS NOT NULL;

CREATE INDEX facts_address
    ON semantic_catalog.facts (domain, fact_address);

-- Datasets and Fields are found by the identity key of their technical-reference Facts.
CREATE INDEX facts_by_matching_value
    ON semantic_catalog.facts (domain, identity_key)
    WHERE identity_key IS NOT NULL;

CREATE TABLE semantic_catalog.input_resources (
    domain text NOT NULL,
    input_resource text NOT NULL,
    kind text NOT NULL,
    table_identifier text,
    question text,
    created_commit bigint NOT NULL,
    PRIMARY KEY (domain, input_resource)
);

CREATE UNIQUE INDEX input_resources_table
    ON semantic_catalog.input_resources (domain, table_identifier)
    WHERE table_identifier IS NOT NULL;

CREATE UNIQUE INDEX input_resources_question_urn
    ON semantic_catalog.input_resources (domain, question)
    WHERE question IS NOT NULL;
