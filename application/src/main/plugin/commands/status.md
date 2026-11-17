---
description: Summarize the Semantic Catalog, its review list, and its build tasks
---

Summarize the state of the ScalarDB Analytics Semantic Catalog for the user in a few lines. Load
the `scalardb-analytics-query-agent` skill first if it is not loaded.

Wherever the skill writes `scalardb-analytics`, run `${CLAUDE_PLUGIN_ROOT}/bin/scalardb-analytics`
with the Bash tool. Pass `--workspace <dir>` if this session settled one; otherwise the CLI uses
`SCALARDB_ANALYTICS_WORKSPACE`.

1. `semantic read`: the Catalog Snapshot and its `counts`.
2. `semantic review`: how many items await review, per table.
3. `semantic tasks --state created`, `--state running`, and `--state failed`: the number of each,
   and every failed task's reason.

If items await review, suggest `/scalardb-analytics:build`, which reviews them with the user. If
tasks failed, say that they return to `created` with `semantic resume <task-number>` once the cause
is fixed.
