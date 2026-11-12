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
