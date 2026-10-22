---
description: Build the Semantic Catalog in the background and review candidates with the user
argument-hint: "[--no-refetch]"
---

Build the ScalarDB Analytics Semantic Catalog and review it with the user, by following
**Workflow A** of the `scalardb-analytics-query-agent` skill. Load that skill first if it is not
loaded.

Run the CLI with the Bash tool as `${CLAUDE_PLUGIN_ROOT}/bin/scalardb-analytics`. Wherever the
skill writes `scalardb-analytics`, use this path. It adds `--workspace` by itself:
`SCALARDB_ANALYTICS_WORKSPACE` if set, else `./workspace`.

The user is the semantics owner who answers. If you do not know their principal yet, run
`semantic status` and confirm it with the user from `semanticsOwners` once.

How to run it here:
1. Start the watching build with the Bash tool **in the background** (`run_in_background`), so you
   stay free to talk with the user:

   ```console
   ${CLAUDE_PLUGIN_ROOT}/bin/scalardb-analytics semantic build --watch --idle-seconds 900 $ARGUMENTS
   ```

   If you started one earlier in this conversation and it is no longer running (the session was
   resumed, or it exited after being idle), start it again. A task it left `running`, as seen in
   `semantic status`, is resumed with `semantic resume --task <id> --running`.
2. Arm the `Monitor` tool, so you are notified when something becomes reviewable instead of waiting
   for the build to end. Ending your turn does not stop it, and a notification wakes you while the
   user is idle. Use `timeout_ms` 1800000 and this command:

   ```sh
   cli='${CLAUDE_PLUGIN_ROOT}/bin/scalardb-analytics'
   last_review=; last_failed=
   while true; do
     if review=$("$cli" semantic review --unpresented 2>&1); then
       review=$(printf '%s' "$review" | sed 's/"snapshot":"[^"]*"//')
       case "$review" in
         *'"items":[]'*) ;;
         *) [ "$review" != "$last_review" ] && echo "reviewable: run semantic review --unpresented" ;;
       esac
       last_review=$review
     else
       echo "review check failed: $(printf '%s' "$review" | head -n 1)"
     fi
     if failed=$("$cli" semantic tasks --state failed 2>&1); then
       case "$failed" in
         *'"tasks":[]'*) ;;
         *) [ "$failed" != "$last_failed" ] && echo "failed: a build task failed" ;;
       esac
       last_failed=$failed
     fi
     sleep 10
   done
   ```

   When it expires and the build is still working, arm it again. Stop it with `TaskStop` when you
   finish.
3. On a `reviewable` notification, run `semantic review --unpresented` and present the new items to
   the user, in plain Japanese if the user writes Japanese:
   - group them by Dataset or element;
   - give a short list the user can answer at once, for example by number;
   - for each item, say what it states, why it was proposed (the check methods), and what is
     unconfirmed.

   Record the presentation with `semantic present` right away, so the same items are not
   notified again. Then end your turn and wait for the user's answers.
4. Submit the user's answers with `semantic answer --principal <owner> --stdin`, without
   `--run-tasks`. Never answer for the user. If the user says "all OK", that is consent to every
   item you presented. The watching build picks up the tasks the answers create, and the monitor
   notifies you of what they produce.
5. On a `failed` notification, run `semantic tasks --state failed` and tell the user each
   `failureReason`. On `review check failed`, tell the user the error. While nothing new is
   reviewable, say briefly that the build is still working, and keep the conversation free for
   other questions.
6. Finish when all three hold:
   - `semantic review --unpresented` and `semantic review` both return no items;
   - `semantic status` shows `createdTasks` and `runningTasks` of 0.

   Then stop the monitor, and summarize what was confirmed: Measures, business terms, identifier
   correspondences, and counts. Suggest `/scalardb-analytics:ask <question>`.
