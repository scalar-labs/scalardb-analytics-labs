---
name: scalardb-analytics-query-agent
version: 1
description: Build and review the Semantic Catalog, then answer business questions with cited SQL, through the ScalarDB Analytics CLI.
---

# ScalarDB Analytics Query Agent v1

Use this skill to work with a workspace's Semantic Catalog through the `scalardb-analytics` CLI.
It has two workflows:

- **Build and review.** Construct knowledge from the registered sources and documents, and have
  the semantics owner judge the candidates.
- **Answer a question.** Find the knowledge a question needs, act on its status, compose SQL from
  it, run the SQL, and cite what you used.

v0 covered only source discovery and SQL. Everything in v0 still applies.

## Working contract

- Pass the workspace directory with `--workspace <dir>`. Do not read or edit files inside it.
- Every command prints one JSON object on stdout. Exit code `0` is success. `1` is a classified
  error with `schema: scalardb-analytics/error` and a `code`. `2` is invalid usage. Read only the
  JSON; do not parse other text.
- URNs are opaque. Copy them from outputs exactly; never build or edit one.
- The environment must carry the catalog store password variable the operator named. For
  construction, it also carries the language model selection (`SCALARDB_ANALYTICS_LLM`, `claude`
  by default).
- Knowledge has a state. A **candidate** is a proposal. A **confirmed** Fact was accepted, by
  machine judgement or by the semantics owner. Only the **selected** Fact of a Fact Address is
  knowledge you may rely on without asking.
- A **Catalog Snapshot** URN (`urn:sdba:snapshot:...`) names a point in the catalog's history.
  Reads at the same snapshot always return the same knowledge.

## Workflow A: build and review

Building runs in the background while you review with the user. Present what has become
reviewable as soon as it appears; do not wait for the build to finish. The catalog is stored in
the workspace's store database, so a running build and your review commands work on it at the
same time.

1. **Start a watching build in the background.** Run it as a background process of your own; it
   does not return until it has been idle for `--idle-seconds`:

   ```console
   scalardb-analytics semantic build --workspace <dir> --watch --idle-seconds 600
   ```

   It refetches every source's table metadata, registers it, and keeps running build tasks,
   including the tasks your answers create. Each finished task prints one JSON line,
   `schema: scalardb-analytics/semantic-build-event`, with the task, its Generator, `state`,
   `accepted`, and `awaitingReview`. The last line is the `semantic-build` result.

   - A line with `state: failed`: read `failureReason`; a missing or failing language model is the
     usual cause. A build does not rerun failed tasks. After the cause is fixed, run
     `semantic resume --workspace <dir> --task <id>`.
   - If the watching build stopped during a run, its task stays `running`. Resume it with
     `semantic resume --workspace <dir> --task <id> --running`.
   - If you run in a sandbox, the model CLI needs network access to its provider, and the JVM
     needs a writable temporary directory (`JAVA_OPTS=-Djava.io.tmpdir=$TMPDIR`).
2. **Poll for what you have not presented yet**, every few seconds while the build runs:

   ```console
   scalardb-analytics semantic review --workspace <dir> --unpresented
   ```

   Each item has a `kind`:
   - `confirmation`: consent to a candidate;
   - `retirement`: consent to retiring a confirmed Fact;
   - `conflict`: select one of several confirmed Facts.

   Each Fact in `items[].facts` carries these fields:
   - `elementName` and `predicate`: what it is about;
   - `value`: what it states;
   - `checks`: how it was examined. `method` is `declared-enforced`, `declared-unenforced`,
     `data-check`, `document`, or `inference`;
   - `unconfirmedItems`: what no check supports.

   While nothing is new, tell the user the build is still working, and keep the conversation free
   for other questions.
3. **Present the new items to the user**, who must be a semantics owner of the workspace. Choose
   how to present them; grouping by Dataset reads well. For each Fact, show:
   - what it states, in plain words;
   - why it was proposed (the check methods);
   - what is unconfirmed.

   Ask for one answer per Fact:
   - `consent`: it is right;
   - `rejection`: it is wrong;
   - `correction`: it is wrong, and the user says what is right;
   - `unknown`: the user cannot tell.

   Never answer for the user. If the user is not a semantics owner, stop and say who must answer.
   To check a candidate against the data before presenting it, run a read-only `query`; no
   citation is needed for these checks.
4. **Record what you presented**, right when you present it:

   ```console
   scalardb-analytics semantic present --workspace <dir> --kind confirmation \
     --fact <fact-urn> --fact <fact-urn> ... --to <owner> --context "<what you showed, briefly>"
   ```

   The output carries a `presentation` identifier. Recorded items no longer appear under
   `--unpresented`.
5. **Submit the user's answers** in one call, with the user as `--principal`. Do not pass
   `--run-tasks`; the watching build runs the tasks the answers create.

   ```console
   scalardb-analytics semantic answer --workspace <dir> --principal <owner> --stdin <<'EOF'
   {"answers": [
     {"fact": "<fact-urn>", "type": "consent", "wording": "<the user's words>", "presentation": "<id>"},
     {"fact": "<fact-urn>", "type": "correction", "wording": "<what is right, in the user's words>", "presentation": "<id>"}
   ]}
   EOF
   ```

   Answer a candidate and the candidates that depend on it in the same call; they are applied in
   dependency order. A correction rejects the candidate. A Generator then proposes a revised
   candidate from the wording, which appears in a later poll.
6. **Repeat steps 2 to 5.** Confirmed meanings let further Generators run, for example for
   Measures and identifier correspondences, so new items keep appearing for a while. The review is
   done when all three hold:
   - `semantic review --unpresented` returns no items;
   - `semantic review` returns no items, so nothing presented is still unanswered;
   - `semantic status` shows `createdTasks` and `runningTasks` of 0.

   The watching build then exits after its idle time, or stop it yourself.
7. **For a `conflict` item**, present every Fact in it and ask the owner to choose one. Record the
   choice:

   ```console
   scalardb-analytics semantic select --workspace <dir> --principal <owner> \
     --address <fact-address-urn> --fact <chosen-fact-urn> --not <other-fact-urn> \
     --wording "<the user's words>" --presentation <id>
   ```

Without a background process, `semantic build` (no `--watch`) runs the created tasks once and
returns. `semantic answer --run-tasks` runs the tasks its answers create before it returns.

To register a document, such as a glossary, that Generators may cite:

```console
scalardb-analytics semantic observe --workspace <dir> --kind document --title "<title>" --file <utf8-file> --run-tasks
```

## Workflow B: answer a question

1. **Open the question and pin the point.**

   ```console
   scalardb-analytics semantic question --workspace <dir> --text "<the question>"
   ```

   Keep the `question` URN and the `snapshot` URN. Pass `--snapshot <snapshot>` to every read for
   this question.
2. **Find the elements.** For each business word in the question, look it up as a business term:

   ```console
   scalardb-analytics semantic elements --workspace <dir> --snapshot <snapshot> --term "<word>"
   ```

   For the whole map of Datasets, Fields, Measures, joins, identifier correspondences, and their
   selected knowledge, read the overview. Narrow it with `--kind`.

   ```console
   scalardb-analytics semantic overview --workspace <dir> --snapshot <snapshot>
   ```

3. **Request the knowledge the SQL depends on.** Give one element and predicate per `--request`.
   Use `*` for every predicate the element has knowledge for.

   ```console
   scalardb-analytics semantic knowledge --workspace <dir> --snapshot <snapshot> \
     --request <measure-urn>=aggregation-rule --request <measure-urn>=filter \
     --request <measure-urn>=reference-time-field --request <measure-urn>=base-dataset
   ```

   Ask at least for the following:
   - **A Measure:** `base-dataset`, `aggregation-rule`, `filter`, `reference-time-field`.
   - **Each join you use:** `multiplicity` and both participations, to avoid fan-out and dropped
     rows.
   - **An identifier correspondence across sources:** `set-relationship` and
     `business-definition`. The correspondence's `identifying.normalization` says how values
     compare; `lower-trim` means compare `lower(trim(value))`.
   - **A Field whose values you filter on:** its `meaning`. A meaning with `codes` lists the
     values.
   - **Any Dataset or Field you name in SQL:** its `technical-reference`. Its `sql` value is the
     only name to use.
4. **Act on each response's status.** Each Fact Address response has either `selected`, or a
   `status`:
   - **`selected`, no status:** use it. If `newCandidates` is not empty, someone proposed a
     change; mention it.
   - **`awaiting-judgement`:** tell the user the knowledge is not confirmed yet. If the user is a
     semantics owner, run steps 3 to 6 of workflow A for those Facts now, then read again at the
     snapshot the answer returned. Otherwise ask whether to wait. Proceed with unconfirmed
     knowledge only if the user explicitly says so, and say in your answer that you did.
   - **`conflict`:** show the eligible Facts in `material` and ask a semantics owner to select
     one. Do not pick one yourself.
   - **`missing`:** the catalog does not know it. Say so, do not invent it, and report it:

     ```console
     scalardb-analytics semantic observe --workspace <dir> --kind missing-knowledge --question <question> --stdin <<'EOF'
     {"text": "<what was missing and why the question needs it>", "missing": [{"element": "<urn or name>", "need": "<predicate or description>"}]}
     EOF
     ```

     Then `semantic build --workspace <dir> --no-refetch` may propose candidates for it.
5. **Compose the SQL from the knowledge only.**
   - Name tables and columns by their `technical-reference` `sql` values, `source.namespace.table`
     and the column name.
   - Apply a Measure's `aggregation-rule` and `filter` exactly.
   - Bound the period by its `reference-time-field`.
   - Match identifiers across sources by the correspondence and its normalization.
   - Write one read-only `SELECT`.
6. **Run it with the citation.** Cite the `factRevision` of every Fact the SQL relies on:

   ```console
   scalardb-analytics query --workspace <dir> --snapshot <snapshot> --question <question> \
     --cite <fact-revision-urn> --cite <fact-revision-urn> --file <query.sql>
   ```

7. **Answer the user.** Give the result, and the definitions you applied in plain words, for
   example "EC sales exclude cancelled and returned orders, by order date". End with the
   citation: the snapshot and the Fact Revisions. `semantic fact <fact-revision-urn>` shows any
   one's rationale, deciding party, and confirmation path.

If the user says a confirmed definition is wrong, record the correction against the Fact:

```console
scalardb-analytics semantic observe --workspace <dir> --kind user-correction --question <question> \
  --fact <fact-urn> --text "<the user's correction>" --run-tasks
```

The corrected candidate then goes through review like any other.

## Errors

| `code` | Meaning and what to do |
| --- | --- |
| `NO_AUTHORITY` | The principal is not a semantics owner. Ask the operator who is |
| `NOT_ACCEPTED_IN_STATE` | The Fact or task is not in a state that accepts this; read it again |
| `NOT_FOUND` | A URN does not exist; copy URNs from outputs only |
| `POINT_OUT_OF_RANGE` | The snapshot is not written yet |
| `INVALID_REQUEST` | Fix the arguments or JSON input |
| `SEMANTIC_CATALOG_NOT_INITIALIZED` | The workspace predates the Semantic Catalog; the operator initializes a new one |
| `MISSING_CREDENTIAL` | The catalog store password variable is not set |

## Operator-only setup

An operator initializes the workspace, naming the semantics owners, and registers sources and
documents. Password values come from the named environment variable or stdin.

```console
scalardb-analytics workspace init --workspace <dir> --catalog <name> \
  --store-jdbc-url <jdbc-url> --store-user <user> --store-password-env <ENV_NAME> \
  --semantics-owner <owner>

scalardb-analytics source register --workspace <dir> --name <source> --type postgresql \
  --host <host> --port <port> --database <database> --user <user> --password-env <ENV_NAME>
```

Construction calls a local, signed-in coding-agent CLI as the language model:

| Variable | Values |
| --- | --- |
| `SCALARDB_ANALYTICS_LLM` | `claude` (default; runs `claude -p`), `codex` (runs `codex exec`), `command`, or `none` |
| `SCALARDB_ANALYTICS_LLM_MODEL` | The model name passed to the CLI; `sonnet` by default for `claude` |
| `SCALARDB_ANALYTICS_LLM_COMMAND` | For `command`: a shell command that reads the prompt on stdin and prints the answer |
| `SCALARDB_ANALYTICS_LLM_TIMEOUT_SECONDS` | Per call; 300 by default |

Every prompt and answer is logged under `<workspace>/llm-log/`.
