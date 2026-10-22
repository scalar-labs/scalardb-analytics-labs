---
description: Check that the ScalarDB Analytics workspace, store password, and owner are ready
argument-hint: "[workspace-dir]"
---

Check that this session can work with the ScalarDB Analytics Semantic Catalog, and report the
result to the user in a few lines.

Run the CLI with the Bash tool as `${CLAUDE_PLUGIN_ROOT}/bin/scalardb-analytics`. Wherever the
`scalardb-analytics-query-agent` skill writes `scalardb-analytics`, use this path. It adds
`--workspace` by itself: `SCALARDB_ANALYTICS_WORKSPACE` if set, else `./workspace`. If the user
gave a workspace (`$ARGUMENTS`), pass `--workspace <dir>` yourself in every command this session.

1. `${CLAUDE_PLUGIN_ROOT}/bin/scalardb-analytics version`
2. `${CLAUDE_PLUGIN_ROOT}/bin/scalardb-analytics semantic status`.
   - `INVALID_WORKSPACE`: no workspace was found. Ask the user where it is.
   - `MISSING_CREDENTIAL`: the store password variable the workspace names is not set in the
     environment Claude Code was started with. Tell the user to restart Claude Code with it
     exported.
3. Report:
   - the latest Catalog Snapshot;
   - the counts in `summary`: elements, confirmed, candidates, awaitingReview, and created,
     running, and failed tasks.
4. Settle who the user is for reviews. `semanticsOwners` in the status output lists who may answer.
   - If there is one, confirm with the user that it is them.
   - If there are several, ask which one is the user.

   Use that principal for presentations and answers in this session.
5. Suggest the next command:
   - `/scalardb-analytics:build` to build the catalog and review it together;
   - `/scalardb-analytics:ask <question>` to answer a question.
