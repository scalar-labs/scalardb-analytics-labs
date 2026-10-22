---
description: Answer a business question with SQL composed from confirmed knowledge, with citations
argument-hint: "<question>"
---

Answer this question for the user:

> $ARGUMENTS

Follow **Workflow B** of the `scalardb-analytics-query-agent` skill. Load that skill first if it
is not loaded.

Run the CLI with the Bash tool as `${CLAUDE_PLUGIN_ROOT}/bin/scalardb-analytics`. Wherever the
skill writes `scalardb-analytics`, use this path. It adds `--workspace` by itself:
`SCALARDB_ANALYTICS_WORKSPACE` if set, else `./workspace`.

Keep to these points:
- Open the question with `semantic question --text`, and pin its snapshot for every read.
- Use only knowledge the catalog returns. Find business words with `semantic elements --term`,
  and read the Measures, joins, and identifier correspondences you need with `semantic knowledge`
  or `semantic overview`.
- For knowledge that is awaiting judgement, in conflict, or missing, follow the skill: ask the
  user, review it with the owner now, or report it as missing knowledge. Never fill a gap by
  guessing.
- Run the SQL with `query --snapshot --question --cite ...`.
- Answer in the user's language. Give the result, the definitions you applied in plain words, and
  the citation: the snapshot and the Fact Revisions.
