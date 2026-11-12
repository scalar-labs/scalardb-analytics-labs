-- The selection of one Fact per Fact Address, and what reads of many Fact Addresses at once need.

-- The Fact Address a conflict resolution targets, so a write finds a Fact Address's resolutions.
ALTER TABLE semantic_catalog.log_records ADD COLUMN target_fact_address text;

CREATE INDEX log_records_target_fact_address
    ON semantic_catalog.log_records (domain, target_fact_address)
    WHERE target_fact_address IS NOT NULL;

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

-- The Facts of a Fact Address with their current records, for candidates and lists.
CREATE INDEX fact_records_address
    ON semantic_catalog.fact_records (domain, fact_address);
