---
description: Check that this session can work with the ScalarDB Analytics workspace
argument-hint: "[workspace-dir]"
---

Check that this session can work with the ScalarDB Analytics Semantic Catalog, and report the
result to the user in a few lines. Load the `scalardb-analytics-query-agent` skill first if it is
not loaded.

## Running the CLI here

Wherever the skill writes `scalardb-analytics`, run `${CLAUDE_PLUGIN_ROOT}/bin/scalardb-analytics`
with the Bash tool. The workspace is the one this command settles:

- If the user gave a directory (`$ARGUMENTS`), pass `--workspace <dir>` in every command this
  session.
- Otherwise the CLI uses `SCALARDB_ANALYTICS_WORKSPACE` from the environment Claude Code was
  started with, and you omit `--workspace`.

## Steps

1. `${CLAUDE_PLUGIN_ROOT}/bin/scalardb-analytics version`
2. `${CLAUDE_PLUGIN_ROOT}/bin/scalardb-analytics semantic languages`, which opens the workspace and
   its catalog store.
   - `INVALID_WORKSPACE`: no workspace was given or found. Ask the user where it is, or tell them
     to restart Claude Code with `SCALARDB_ANALYTICS_WORKSPACE` exported.
   - `MISSING_CREDENTIAL`: the store password variable the workspace names is not set. Tell the
     user to restart Claude Code with it exported.
   - Any other error: report its `code` and `message`, and see the skill's error table.
3. `${CLAUDE_PLUGIN_ROOT}/bin/scalardb-analytics semantic read`, and report the Catalog Snapshot
   and its `counts`.
4. Suggest the next command:
   - `/scalardb-analytics:build` to build the catalog and review it together;
   - `/scalardb-analytics:ask <question>` to answer a question.
