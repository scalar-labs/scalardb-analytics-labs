---
description: Build the Semantic Catalog in the background and review it with the user
---

Build the ScalarDB Analytics Semantic Catalog and review it with the user, by following
**Workflow A** of the `scalardb-analytics-query-agent` skill. Load that skill first if it is not
loaded.

Wherever the skill writes `scalardb-analytics`, run `${CLAUDE_PLUGIN_ROOT}/bin/scalardb-analytics`
with the Bash tool. Pass `--workspace <dir>` if this session settled one; otherwise the CLI uses
`SCALARDB_ANALYTICS_WORKSPACE`.

Claude Code wakes you after your turn ends only on a notification. Run the skill's step 1 so that
the build's events notify you:

1. **Start the watching build** with the Bash tool and `run_in_background`, so it keeps running
   while you talk with the user:

   ```console
   ${CLAUDE_PLUGIN_ROOT}/bin/scalardb-analytics semantic build --watch
   ```

   Keep the output file the Bash tool reports for it. Start only one per session; if one you
   started has exited, read the end of its output file before starting another.
2. **Arm the `Monitor` tool on that output**, with `timeout_ms` 1800000 and this command, where
   `<output-file>` is the file from step 1:

   ```sh
   tail -n 0 -f '<output-file>' | "${CLAUDE_PLUGIN_ROOT}/bin/review-notifications"
   ```

   It notifies you of a build task event when `awaitingReview` rises above what it last saw since
   it was armed, or a task failed, and of any classified error or run summary the build prints.
   When the monitor expires and the build is still running, arm it again.
3. **Read the review list** with `semantic review`, and the failed tasks with
   `semantic tasks --state failed`, right after each arming, since the monitor passes on only what
   the build prints after it is armed. Read the review list again on each
   notification whose `awaitingReview` is above zero. Then continue with the skill's steps 3 to 5:
   present, record the presentation, end your turn, and submit the owner's answers. The watching
   build runs the tasks the answers create.
4. **Re-arm the monitor after you submit answers**: stop it with `TaskStop`, arm it again as in
   step 2, and read the review list once. Answers lower `awaitingReview` without a build event, so
   a monitor left armed would not notify you of new items until the count rose above its earlier
   value.
5. **On a failed task or an error**, tell the user the `reason` or the `code` and `message`, as the
   skill says. If the build failed at once with `BUILD_RUN_IN_PROGRESS`, stop the monitor and read
   the review list at an interval instead, as the skill says.

Finish when the skill's step 6 says review is done. Then stop the monitor and the watching build
with `TaskStop`, summarize what was confirmed, and suggest `/scalardb-analytics:ask <question>`.
