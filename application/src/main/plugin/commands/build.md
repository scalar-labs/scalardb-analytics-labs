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
     sleep 3
   done
   ```

   When it expires and the build is still working, arm it again. Stop it with `TaskStop` when you
   finish.
3. On a `reviewable` notification, run `semantic review --unpresented`. Gather the evidence and
   sort the items into **routine** and **needs judgement**, as step 3 of the skill's Workflow A
   describes, and present them in plain Japanese if the user writes Japanese.

   Use this layout, and no other list markup:
   - One number per element (a column, a Dataset, a Measure), covering all of its Facts in this
     round, for example a column's meaning and value kind together. Use plain integers that
     continue across rounds in this conversation; never letters.
   - **Routine** first: one Markdown table per Dataset, one row per element:

     ```markdown
     #### shop.public.orders（EC 注文）
     | # | 列 | 提案 | 根拠 |
     |---|---|---|---|
     | 12 | ordered_on | 注文日（日付） | 2026-09-01〜09-30 の日付のみ |
     | 13 | status | 配送状況の区分値 | delivered 5 / shipped 2 / cancelled 1 |
     ```

   - **Needs judgement** after it, each as a short block:

     ```markdown
     **35. pos.members.member_rank の意味**
     提案: 会員ランク（gold / silver / regular）
     根拠: 3 値のみ。各ランクの条件を示す資料はない
     気になる点: ランク名が正式名称か不明
     見立て: 区分値としては妥当。正式名称があれば修正を
     ```

   - End with one line on how to answer, for example: 「定型は "定型OK"、要判断は番号ごとに
     OK / NG / 修正 / 不明 で」.

   Keep the evidence queries few: one `query` per Dataset usually covers its Fields' sample
   values and counts. Record the presentation with `semantic present` right away, so the same
   items are not notified again. Then end your turn and wait for the user's answers.
4. Submit the user's answers with `semantic answer --principal <owner> --stdin`, without
   `--run-tasks`. Never answer for the user. An answer to a number applies to every Fact under
   it; if the user corrects only one of them, ask which. "Routine OK" consents to the routine
   list; "all OK" consents to every item you presented. The watching build picks up the tasks the answers
   create, and the monitor notifies you of what they produce.
5. On a `failed` notification, run `semantic tasks --state failed` and tell the user each
   `failureReason`. On `review check failed`, tell the user the error. While nothing new is
   reviewable, say briefly that the build is still working, and keep the conversation free for
   other questions.
6. Finish when all three hold:
   - `semantic review --unpresented` and `semantic review` both return no items;
   - `semantic status` shows `createdTasks` and `runningTasks` of 0.

   Then stop the monitor, and summarize what was confirmed: Measures, business terms, identifier
   correspondences, and counts. Suggest `/scalardb-analytics:ask <question>`.
