-- The Semantic Catalog store. Every table has the domain as the first column of its primary key.

-- The domains of the Semantic Catalog. builder_lock is the key of the session-level advisory lock
-- that a process running the domain's build tasks holds, issued per domain so domains never share
-- one.
CREATE TABLE semantic_catalog.domains (
    domain text PRIMARY KEY,
    created_at timestamptz NOT NULL,
    builder_lock bigint GENERATED ALWAYS AS IDENTITY UNIQUE
);

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
-- target_fact_address is the Fact Address a conflict resolution targets, so a write finds a Fact
-- Address's resolutions.
CREATE TABLE semantic_catalog.log_records (
    domain text NOT NULL,
    commit_number bigint NOT NULL,
    ordinal integer NOT NULL,
    kind text NOT NULL,
    target_fact text,
    target_input_resource text,
    content jsonb NOT NULL,
    target_fact_address text,
    PRIMARY KEY (domain, commit_number, ordinal),
    FOREIGN KEY (domain, commit_number) REFERENCES semantic_catalog.commits (domain, commit_number)
);

CREATE INDEX log_records_target_fact
    ON semantic_catalog.log_records (domain, target_fact)
    WHERE target_fact IS NOT NULL;

CREATE INDEX log_records_target_input_resource
    ON semantic_catalog.log_records (domain, target_input_resource)
    WHERE target_input_resource IS NOT NULL;

CREATE INDEX log_records_target_fact_address
    ON semantic_catalog.log_records (domain, target_fact_address)
    WHERE target_fact_address IS NOT NULL;

-- Edge ends hold each end in its canonical form, a JSON array of the URNs of its elements.
-- technical_reference is the canonical form of the table or column that identifies a Dataset or a
-- Field.
CREATE TABLE semantic_catalog.elements (
    domain text NOT NULL,
    element text NOT NULL,
    kind text NOT NULL,
    parent text,
    technical_reference text,
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

-- Usage feedback is a new Input Resource at each registration, so nothing identifies an Input
-- Resource by a question. latest_table_metadata_version is the newest table metadata version
-- received for a physical schema, which is not derived from the log: it equals the highest version
-- among the table's derived records.
CREATE TABLE semantic_catalog.input_resources (
    domain text NOT NULL,
    input_resource text NOT NULL,
    kind text NOT NULL,
    table_identifier text,
    created_commit bigint NOT NULL,
    latest_table_metadata_version bigint,
    PRIMARY KEY (domain, input_resource)
);

CREATE UNIQUE INDEX input_resources_table
    ON semantic_catalog.input_resources (domain, table_identifier)
    WHERE table_identifier IS NOT NULL;

-- The Fact Records, materialized from the Fact Events in the log.

-- The current Fact Record of each Fact, rewritten by every commit that changes it. It always equals
-- the result of folding the Fact's events, so a write, or a read at a point at or after the latest
-- event, uses it without folding. A read at an earlier point folds the Fact's events instead.
CREATE TABLE semantic_catalog.fact_records (
    domain text NOT NULL,
    fact text NOT NULL,
    fact_address text NOT NULL,
    latest_event_commit bigint NOT NULL,
    latest_event_ordinal integer NOT NULL,
    state text NOT NULL,
    awaiting_consent boolean NOT NULL,
    open_retirement_proposal text,
    unknown_answer_pending boolean NOT NULL,
    record jsonb NOT NULL,
    PRIMARY KEY (domain, fact)
);

-- The Facts of a Fact Address with their current records, for candidates and lists.
CREATE INDEX fact_records_address
    ON semantic_catalog.fact_records (domain, fact_address);

-- A confirmed Fact is presentable from the commit it was confirmed until the commit it was retired.
CREATE TABLE semantic_catalog.presentable_periods (
    domain text NOT NULL,
    fact text NOT NULL,
    confirmed_commit bigint NOT NULL,
    retired_commit bigint,
    PRIMARY KEY (domain, fact, confirmed_commit)
);

-- The dependency references of each Fact that are in effect, or were in effect at its latest
-- confirmation, which cascades follow.
CREATE TABLE semantic_catalog.dependencies (
    domain text NOT NULL,
    dependent_fact text NOT NULL,
    depended_record text NOT NULL,
    depended_fact text,
    depended_input_resource text,
    in_effect boolean NOT NULL,
    for_confirmation boolean NOT NULL,
    PRIMARY KEY (domain, dependent_fact, depended_record)
);

CREATE INDEX dependencies_depended_fact
    ON semantic_catalog.dependencies (domain, depended_fact)
    WHERE depended_fact IS NOT NULL;

-- The selected and eligible Facts of a Fact Address over a commit range: from from_commit until
-- to_commit, exclusive, or open while to_commit is null. A row is added only when the selection
-- changes.
CREATE TABLE semantic_catalog.address_selections (
    domain text NOT NULL,
    fact_address text NOT NULL,
    from_commit bigint NOT NULL,
    to_commit bigint,
    selected_fact text,
    eligible_facts jsonb NOT NULL,
    PRIMARY KEY (domain, fact_address, from_commit)
);

-- Records kept outside the log.

-- What was presented in review, to whom, and when. Presenting changes no Fact's state, so it is not
-- in the log. presentation is a number increasing within the domain; content holds the kind, the
-- target Facts with their Fact Addresses, the recipient, the time, a reference to the context
-- shown, and the resulting events, which the commits of answers and conflict resolutions add.
CREATE TABLE semantic_catalog.review_presentations (
    domain text NOT NULL REFERENCES semantic_catalog.domains (domain),
    presentation bigint NOT NULL,
    content jsonb NOT NULL,
    PRIMARY KEY (domain, presentation)
);

-- The Facts each review presentation targets, to find a Fact's earlier presentations.
CREATE TABLE semantic_catalog.review_presentation_targets (
    domain text NOT NULL,
    presentation bigint NOT NULL,
    fact text NOT NULL,
    PRIMARY KEY (domain, presentation, fact),
    FOREIGN KEY (domain, presentation)
        REFERENCES semantic_catalog.review_presentations (domain, presentation)
);

CREATE INDEX review_presentation_targets_fact
    ON semantic_catalog.review_presentation_targets (domain, fact);

-- The connector-derived part of each table metadata version received: the Analytics types, the
-- retrieval information, the read time, and the content digest. It is neither an Input Observation
-- nor a Fact, so it is not in the log.
CREATE TABLE semantic_catalog.derived_records (
    domain text NOT NULL REFERENCES semantic_catalog.domains (domain),
    table_identifier text NOT NULL,
    table_metadata_version bigint NOT NULL,
    content jsonb NOT NULL,
    PRIMARY KEY (domain, table_identifier, table_metadata_version)
);

-- Build tasks: one run of a Generator, an impact judgement, an evaluation by the policy evaluation
-- model, or a policy re-evaluation. Running one changes no Fact's state by itself, so it is not in
-- the log. task is a number increasing within the domain; kind and state are kept as columns for
-- build task reads; target is the Fact an evaluation task evaluates, to find the created task a new
-- trigger joins; content holds the work, the triggers, and the result or failure reason.
CREATE TABLE semantic_catalog.build_tasks (
    domain text NOT NULL REFERENCES semantic_catalog.domains (domain),
    task bigint NOT NULL,
    kind text NOT NULL,
    state text NOT NULL,
    target text,
    content jsonb NOT NULL,
    PRIMARY KEY (domain, task)
);

CREATE INDEX build_tasks_state_kind ON semantic_catalog.build_tasks (domain, state, kind, target);
