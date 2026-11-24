---
description: Answer a business question with SQL composed from confirmed knowledge, with citations
argument-hint: "<question>"
---

Answer this question for the user:

> $ARGUMENTS

Follow **Workflow B** of the `scalardb-analytics-query-agent` skill. Load that skill first if it is
not loaded.

Wherever the skill writes `scalardb-analytics`, run `${CLAUDE_PLUGIN_ROOT}/bin/scalardb-analytics`
with the Bash tool. Pass `--workspace <dir>` if this session settled one; otherwise the CLI uses
`SCALARDB_ANALYTICS_WORKSPACE`.

Answer in the user's language, reading as the skill's "Reading in the user's language" says:
compose the SQL from originals, and show each definition you cite with the catalog's translation
beside the original.
Cite as the skill says: the Catalog Snapshot and the Fact Revisions the SQL relies on.
