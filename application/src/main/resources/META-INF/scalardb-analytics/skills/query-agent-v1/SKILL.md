---
name: scalardb-analytics-query-agent
version: 1
description: Build and review a workspace's Semantic Catalog with its semantics owner, and answer business questions with SQL composed from confirmed knowledge and cited, through the ScalarDB Analytics CLI.
---

# ScalarDB Analytics Query Agent v1

Use this skill to work with a ScalarDB Analytics workspace through the `scalardb-analytics` CLI.
It covers two workflows:

- **Build and review.** Run the Semantic Catalog's build tasks and have the semantics owner judge
  what the catalog could not confirm by itself.
- **Answer a question.** Read the knowledge a question needs at one Catalog Snapshot, act on what
  is not confirmed, compose one read-only `SELECT` from confirmed knowledge, run it, and cite what
  you used.

You are not the semantics owner. Only the owner's own answer confirms knowledge.

## Working contract

- Pass the workspace directory with `--workspace <dir>`, or omit the option when the environment
  sets `SCALARDB_ANALYTICS_WORKSPACE` to it. Do not read or edit files inside it.
- Every command prints JSON objects on stdout, one per line, each with `schema` and
  `schemaVersion`. Exit code `0` is success, `1` is a classified error
  (`schema: scalardb-analytics/error` with a `code` and a `message`), and `2` is invalid usage.
  Read only the JSON.
- URNs are opaque. Copy them exactly from outputs; never build, shorten, or edit one.
- Every `semantic` read returns the `catalogSnapshot` URN it read. Reading again with
  `--snapshot <urn>` returns the same knowledge. `answer`, `select`, and `feedback` return the
  `catalogSnapshot` they wrote.
- Knowledge comes per Fact Address with a `status`:
  - `selected`: one confirmed Fact you may rely on;
  - `awaiting-judgement`: candidates that need a judgement, with what each waits for;
  - `conflict`: several confirmed Facts; an owner must choose one;
  - `missing-knowledge`: the catalog does not know it, with any rejected or retired Facts and the
    answers given to them.
- Reads return only confirmed knowledge unless you pass `--unconfirmed`. A confirmed-only read
  leaves out Fact Addresses awaiting judgement and conflicts with no selected Fact without saying
  so; it lists under `missing` only the requested predicates the catalog does not know at all.
  When you must know what is not confirmed, as when answering a question, read with
  `--unconfirmed`. Every level of `semantic read` carries `counts` of confirmed Fact Addresses,
  items awaiting review, and core predicates with missing knowledge.
- Show the knowledge in the words the catalog returns. Do not translate or reword a definition
  you cite. For a user who reads another language, show the catalog's own translations, as
  [Reading in the user's language](#reading-in-the-users-language) says.

## Reading in the user's language

The catalog records every description in one **recording language**, the domain's primary
language. A user may read another of the domain's declared languages. Find out which:

```console
scalardb-analytics semantic languages --workspace <dir>
```

It returns the `declared` languages and the `primary` one, the recording language.

1. **Decide the session language from the user.** Use the language the user writes in, or the one
   they ask for in whatever words, such as "show me in English" or "日本語で". Switch whenever they
   ask again. Nothing is stored for the user: you decide the language for this session.
2. **When it is declared and not the primary language,** pass `--language <tag>` on every read
   whose text you show the user: `semantic review`, and the `semantic read`, `knowledge`, and
   `fact` reads whose definitions, reasons, or review material you present or cite. Otherwise, and
   for a language the domain does not declare, pass no `--language` and show the originals.
3. **Reason and compose SQL from originals only.** The reads you use to plan and compose a query
   take no `--language`, and you never build SQL from a translation, which nobody reviewed. Read
   again with `--language` only the text you will show, such as a definition you cite.
4. **Show the catalog's translations; never translate knowledge yourself.** With `--language`, a
   read returns its usual fields unchanged, plus `language`, `recordingLanguage`, and `texts`:
   for each URN in the result that holds text, its `originals` by key and either
   `"status": "translated"` with a `translation` (its `texts` under the same keys), or
   `"status": "pending"`. Show each original with its translation beside it, and say the
   translation is one. Write your own explanations in the user's language.
5. **Get pending translations before you answer.** A `pending` text has no translation yet; the
   read already queued it. Never tell the user that a translation is pending or will come later.
   Before you show the text, run

   ```console
   scalardb-analytics semantic translate --workspace <dir>
   ```

   which translates every queued text and returns once each has been tried. It runs apart from
   build tasks, so it does not wait for a running build, and it waits only while another process
   translates. Then read again with `--language` and show the texts as step 4 says. Its
   `scalardb-analytics/translation-run` output lists the `translated` texts and the `failed` ones
   by `logRecord`, the same URN a text's `logRecord` names. Only for a text that failed, show the
   original with a short note in the user's language that its translation could not be obtained;
   do the same for every pending text when `semantic translate` itself fails, such as with
   `LANGUAGE_MODEL_NOT_CONFIGURED`. Do not translate it yourself.
6. **Submit what the user writes in the recording language.** An answer's or a conflict choice's
   wording, a correction, and usage feedback are recorded as text. Translate what the user wrote
   into the recording language before you submit it, keeping its meaning exactly, and tell the user
   what you submitted.

Business-term names are not translated: a term has its own name in each declared language, and
`semantic elements --term` matches a name in any of them. Add `--language <tag>` there to match
only that language's names.

## Discovering sources

```console
scalardb-analytics source list --workspace <dir>
scalardb-analytics metadata list --workspace <dir> --source <source>
```

Address a table in SQL as `source.namespace.table`, using only names that discovery or the
Semantic Catalog returned.

## Workflow A: build and review with the semantics owner

1. **Keep one watching build running in the background.**

   ```console
   scalardb-analytics semantic build --workspace <dir> --watch
   ```

   It runs every created build task, including those that answers and registrations create while
   it runs, and keeps running until interrupted. Each finished task prints one line,
   `schema: scalardb-analytics/build-task-event`, with the `task` and `awaitingReview`, the number
   of items awaiting review in the whole catalog. Read the review list when `awaitingReview` is
   above zero.

   - If it fails at once with `BUILD_RUN_IN_PROGRESS`, another process is already running the
     tasks. Do not start another; read the review list at an interval instead.
   - If it fails with `LANGUAGE_MODEL_NOT_CONFIGURED`, the operator must configure a language
     model for the workspace.
   - A task line with `"state": "failed"` carries its `reason`. After the cause is fixed, return it
     to `created` with `semantic resume --workspace <dir> <task-number>`.
   - The first interrupt stops the watching build after its current task; a second stops it at
     once.

   To run the created tasks once and return, as a script would, omit `--watch`.

2. **Read the review list.**

   ```console
   scalardb-analytics semantic review --workspace <dir>
   ```

   For an owner who reads another declared language, add `--language <tag>` (see
   [Reading in the user's language](#reading-in-the-users-language)) and present the review material
   from its translations. Narrow it with `--source`, `--namespace`, and `--table`, or with
   `--kind`. Items come in
   `groups`, one per table (`table` is null for domain-wide items). Each item has:
   - `item`: `candidate` (consent to it), `retirement` (consent to retiring a confirmed Fact), or
     `conflict` (choose one Fact);
   - `target`: the element, its `physicalCatalogReference`, the Fact Address, and the predicate;
   - `fact`: the Fact's content, with its value, checks, and what it rests on;
   - `evaluation`: the policy evaluation that asked for a judgement, with its `reason`. When the
     policy evaluation model made it, `evaluation.record.evaluator.reviewMaterial` holds the
     question to decide, what remains uncertain, evidence for and against, and a suggested answer
     with its reasons;
   - `premiseItems`: the items this one rests on. Present them together; rejecting a premise
     rejects what rests on it;
   - `needArose`: the commit at which the judgement became needed, and `earlierPresentations`.

   Tell items you have not seen from those you have by their Fact URN, or by `needArose`: a
   re-evaluated item gets a newer commit.

3. **Present the review material per table.** Use the material the catalog built; do not gather
   your own evidence or re-triage.
   - Items whose suggested answer the material supports without reservation are **routine**: show
     them one line each and ask for consent to them together. The owner may pick any out.
   - Every other item **needs judgement**: present it on its own, with the question, the evidence
     for and against, what is uncertain, and the suggested answer with its reasons.
   - For a `conflict`, show every eligible Fact and ask the owner to choose one.

4. **Record each presentation** when you make it:

   ```console
   scalardb-analytics semantic present --workspace <dir> --kind confirmation \
     --fact <fact-urn> [--fact <fact-urn> ...] --to <owner> --context "<what you showed>"
   ```

   `--kind` is `confirmation`, `retirement`, or `conflict`. The output's `presentation` number goes
   with the answers to it.

5. **Submit exactly what the owner answered**, in one call, from the owner's own words, put into
   the recording language when the owner wrote in another:

   ```console
   scalardb-analytics semantic answer --workspace <dir> --file - <<'EOF'
   {"answers": [
     {"fact": "<fact-urn>", "type": "consent", "wording": "<the owner's words>", "presentation": <n>},
     {"fact": "<fact-urn>", "type": "correction", "wording": "<what is right, in the owner's words>",
      "contextSummary": "<what was presented>", "presentation": <n>}
   ]}
   EOF
   ```

   - `type` is `consent`, `rejection`, `correction`, or `unknown`. Add `"transition": "retire"` for
     an answer to a retirement proposal.
   - The CLI records the person running it as the deciding party. Never submit an answer the owner
     did not give, and never choose for the owner. If the user is not a semantics owner, stop and
     say who must answer.
   - Each outcome says whether the Fact was `CONFIRMED`, `REJECTED`, or why nothing changed, such
     as `UNCONFIRMED_DEPENDENCIES`. A correction becomes a revised candidate once the build runs
     the tasks the answer created.
   - For a conflict, record the owner's choice instead:

     ```console
     scalardb-analytics semantic select --workspace <dir> --fact-address <fact-address-urn> \
       --fact <chosen-fact-urn> --passed-over <other-fact-urn> \
       --wording "<the owner's words>" --presentation <n>
     ```

6. **Repeat from step 2.** Confirmed knowledge lets more Generators run, so items keep appearing
   for a while. Present again anything shown but not answered. Review is done when
   `semantic review` returns no groups and `semantic tasks --state created` and
   `semantic tasks --state running` return no tasks.

To give the catalog a document the owner wrote, such as a glossary, that Generators read and cite:

```console
scalardb-analytics semantic document register --workspace <dir> <file>
```

## Workflow B: answer a question

1. **Fix one Catalog Snapshot** for the whole question. Read the catalog from the root:

   ```console
   scalardb-analytics semantic read --workspace <dir> --recursive --unconfirmed
   ```

   Keep the returned `catalogSnapshot` and pass `--snapshot <urn>` to every later read for this
   question. While the catalog is small, the recursive read from the root returns every table unit
   (the Dataset, its Fields, the Measures based on it, and the correspondences it is an end of,
   each with all its knowledge) and the domain-wide elements (concepts, business terms,
   Dimensions, and Measures with no base Dataset). As it grows, read a data source, namespace, or
   table with `--source`, `--namespace`, and `--table` instead.

2. **Find the elements the question names.** For each business word, look it up as a business
   term, by its exact written form in any declared language:

   ```console
   scalardb-analytics semantic elements --workspace <dir> --snapshot <urn> --term "<word>"
   ```

   `--kind` (such as `measure` or `dataset`) and `--parent <urn>` narrow the list.

   When the question breaks a figure down by classes, such as age bands, regions, price ranges,
   or member ranks, look for a classification Dimension the same way, or list the Dimensions with
   `--kind dimension` and read their `meaning` and `dimension-kind`. Dimensions belong to the
   domain root, not to a table.

3. **Request the knowledge the SQL depends on.**

   ```console
   scalardb-analytics semantic knowledge --workspace <dir> --snapshot <urn> --unconfirmed \
     --element <element-urn> [--predicate <predicate>]
   ```

   Always pass `--unconfirmed` here, so knowledge awaiting judgement and conflicts come back with
   their status instead of being left out. Without `--predicate` it returns every predicate. For several requests, or for an edge such as
   an identifier correspondence given by its ends, pass a JSON file:

   ```console
   scalardb-analytics semantic knowledge --workspace <dir> --snapshot <urn> --unconfirmed --file - <<'EOF'
   {"requests": [
     {"element": "<measure-urn>", "predicate": "aggregation-rule"},
     {"edge": {"kind": "identifier-correspondence", "from": ["<field-urn>"], "to": ["<field-urn>"]}}
   ]}
   EOF
   ```

   What a query usually needs:
   - a Measure: `base-dataset`, `aggregation-rule`, `filter`, `reference-time-field`, and
     `default-time-granularity`, which names a calendar Dimension and one of its levels;
   - a calendar Dimension (`--kind dimension`; a Measure's granularity names it): `meaning`,
     `dimension-kind`, `hierarchy`, and `calendar-rule`;
   - a classification Dimension (the classes a question breaks a figure down by): `meaning`,
     `dimension-kind`, `hierarchy`, `classification-rule`, and `classified-field`, which names the
     Field whose values get the classes;
   - a Dataset: `row-kind`, `row-inclusion`, `key`;
   - a Field you filter or group on: `meaning`, `value-kind`, `unit`, `null-meaning`;
   - a correspondence across sources: `set-relationship`, `business-definition`, and its matching
     condition.

4. **Act on each status.**
   - `selected`: use it.
   - `awaiting-judgement`: if the user is a semantics owner, run steps 3 to 5 of workflow A for
     those Facts now, then read again at the snapshot the answer returned. Otherwise say the
     knowledge is not confirmed. Proceed with unconfirmed knowledge only when the user explicitly
     asks, and say in your answer that you did.
   - `conflict`: an owner chooses, as in workflow A. Do not pick one.
   - `missing-knowledge`, or a predicate listed under `missing`: do not invent it. Register what
     the question needed:

     ```console
     scalardb-analytics semantic feedback --workspace <dir> \
       --content "<what was missing and why the question needs it>" \
       --context "<the question, summarized>" [--related-fact <fact-urn>]
     ```

     Write `--content` and `--context` in the recording language. Add `--related-fact` when the
     feedback is about a Fact you read, such as one the user says is
     wrong. Feedback with related Facts creates build tasks, which the watching build runs.

     A classification's `classified-field` is optional and never listed under `missing`: a
     document does not always say which column a classification is of. Its absence alone is not
     something to register.

5. **Compose the SQL from confirmed knowledge only**: the `selected` entries, and candidates only
   when the user explicitly asked to proceed with them. Name tables and columns by the
   `physicalCatalogReference` of their Dataset and Fields. Apply a Measure's aggregation rule and
   filter exactly, bound periods by its reference time field, and match identifiers across sources
   by the correspondence's matching condition. Write one read-only `SELECT`.

   Periods such as "September" or "this year" may mean calendar or fiscal periods. When the
   Measure's default time granularity names a calendar, divide time by that calendar: a hierarchy
   level's `expression` is written over `time_value`; put the reference time field in its place to
   get the level's period key. A level without an expression is defined only by the
   `calendar-rule` wording; apply that wording, and say in your answer how you did. When no
   calendar applies, use calendar periods and say so.

   To break a figure down by a classification, take its `hierarchy` level the question asks for. A
   level's `expression` is written over the columns of the classified Field's table and gives each
   row's class; use it as written, qualifying its columns by that table when the query joins
   others, and group by it. A level without an expression, or a classification without a
   `classified-field`, is defined only by the `classification-rule` wording, which may need other
   tables or periods, such as an area master or last year's purchases; apply that wording, and say
   in your answer how you did. The classes and their order are stated in the wording, not in a
   list; keep the boundaries as worded. An axis that is a column's own values, such as a member
   rank column, is not a classification: group by that Field as its `meaning` describes.

6. **Run it.**

   ```console
   scalardb-analytics query --workspace <dir> --file <utf8-file>
   ```

   `--sql '<sql>'` and `--stdin` also work. Add `--time-limit-seconds <n>` to bound it.

7. **Answer with the citation.** Give the result and the definitions you applied, in the
   catalog's wording; for a user who reads another declared language, read the cited Fact
   Revisions again with `--language <tag>` and show their translations. End with the `catalogSnapshot` URN you read and the Fact Revision URN
   (`fact.revision.urn`) of every Fact the SQL relies on. Anyone can retrieve each one, with its
   checks, confirmation path, and deciding party:

   ```console
   scalardb-analytics semantic fact --workspace <dir> --snapshot <urn> <fact-revision-urn>
   ```

## Errors

| `code` | What to do |
| --- | --- |
| `INVALID_REQUEST` | Fix the arguments or the JSON input; the message says what is wrong |
| `NOT_FOUND` | The Fact, Fact Revision, or task named does not exist at that point; copy URNs from outputs only |
| `SEMANTIC_CATALOG_REJECTED` | The catalog refused the request, such as an unknown element, a snapshot not written yet, or a task not in a state that allows it; the `message` says why. Read again and retry with current URNs |
| `SEMANTIC_CATALOG_UNAVAILABLE` | The catalog store cannot be reached; tell the operator |
| `BUILD_RUN_IN_PROGRESS` | Another process runs the build tasks; do not start another |
| `LANGUAGE_MODEL_NOT_CONFIGURED` | The operator configures the workspace's language model |
| `RECORDING_LANGUAGE_CHANGED` | The workspace's primary language differs from the one the catalog records in; tell the operator |

## Operator-only setup

An operator initializes the workspace and registers sources. Password values come from the named
environment variable or stdin; never place one in an argument or file.

```console
scalardb-analytics workspace init --workspace <dir> --catalog <name> \
  --store-jdbc-url <jdbc-url> --store-user <user> --store-password-env <ENV_NAME>

scalardb-analytics source register --workspace <dir> --name <source> --type postgresql \
  --host <host> --port <port> --database <database> --user <user> --password-env <ENV_NAME>

printf '%s\n' "$SOURCE_PASSWORD" | scalardb-analytics source register --workspace <dir> \
  --name <source> --type mysql --host <host> --port <port> --user <user> --password-stdin
```

The language model that builds the catalog, the languages its readers use, and what reaches the
model are set under `semanticCatalog` in the workspace configuration. Listings, knowledge, query
results, and errors never contain passwords.
