---
description: Summarize the Semantic Catalog, the review queue, and build tasks
---

Run `${CLAUDE_PLUGIN_ROOT}/bin/scalardb-analytics semantic status` with the Bash tool. It adds
`--workspace` by itself: `SCALARDB_ANALYTICS_WORKSPACE` if set, else `./workspace`. Report to the
user in a few lines:
- the latest Catalog Snapshot;
- confirmed Facts and candidates;
- items awaiting review;
- conflicts;
- created, running, and failed build tasks, with each failed task's `failureReason`.

If items await review, suggest `/scalardb-analytics:build`, which reviews them with the user. If
tasks failed, offer to resume them with `semantic resume --task <id>` once the cause is fixed.
