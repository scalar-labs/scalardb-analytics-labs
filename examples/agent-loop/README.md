# Agent-loop runbook

This runbook takes the CLI distribution from a fresh build to an AI agent that builds the
Semantic Catalog, has its knowledge reviewed, and answers a business question with cited SQL. It
is a prototype: the Semantic Catalog runs on provisional design decisions.

Docker Compose starts every database locally with sample data:

| Service | Role | Host port | Contents |
| --- | --- | --- | --- |
| `catalog-store` | Physical Catalog store (PostgreSQL) | `15433` | Empty; the workspace creates its catalog here |
| `shop` | E-commerce source (PostgreSQL) | `25433` | `public.customers`: 8 customers; `public.orders`: 12 orders with status codes |
| `pos` | In-store point-of-sale source (MySQL) | `13307` | `pos.members`: 6 members; `pos.store_sales`: 8 sales |

[`docs/glossary.md`](docs/glossary.md) is the sales domain's glossary, in Japanese. It defines EC
sales, store sales, the order status codes, member ranks, and how online customers and store
members are matched. The tables do not declare that matching. The online customers and the store
members are the same people when their email addresses match ignoring letter case.

The question this runbook answers:

> 2026年9月に EC と店舗の両方で購入した顧客の、9月の EC 売上の合計は?
>
> (What were the September 2026 EC sales of the customers who bought both online and in store
> that month?)

The answer is 18,000 yen from two customers. Getting it right needs four pieces of knowledge
that no table declares:

- which orders count as sales;
- which date places an order in a month;
- that the two sources identify the same person by email address;
- that the email addresses must be compared without letter case.

The passwords in `compose.yaml` and below are example values for this disposable environment.
Each source grants the CLI a read-only account.

## Prerequisites

- Java 25 or later, selected through `JAVA_HOME` or the `java` command on `PATH`
- Docker with Docker Compose
- What the repository build needs; see [Run a query](../../README.md#run-a-query)
- A signed-in [Claude Code](https://claude.com/claude-code) (`claude`) or Codex (`codex`) CLI on
  `PATH`. Construction uses it as the language model. Check it with `claude -p "say ok"`.

Ports `15433`, `25433`, and `13307` must be free on `127.0.0.1`.

## 1. Build the distribution

From the repository root:

```console
./gradlew installDist
cd examples/agent-loop
CLI=$(echo ../../application/build/install/scalardb-analytics-*/bin/scalardb-analytics)
"$CLI" version
```

The rest of the runbook runs in `examples/agent-loop`.

## 2. Start the databases

```console
docker compose up -d --wait
```

## 3. Initialize the workspace

The workspace binds a directory to one Physical Catalog catalog and one Semantic Catalog domain.
Both are stored in the catalog store database. The Semantic Catalog keeps its tables in that
database's `semantic_catalog` schema, so several CLI processes can work on it at the same time.
`--semantics-owner` names who may judge candidates; this runbook uses `owner`:

```console
export CATALOG_PASSWORD=catalog-password
"$CLI" workspace init \
  --workspace ./workspace \
  --catalog sales \
  --store-jdbc-url jdbc:postgresql://127.0.0.1:15433/catalog \
  --store-user catalog \
  --store-password-env CATALOG_PASSWORD \
  --semantics-owner owner
```

The output's `semanticSnapshot` is the first Catalog Snapshot, which holds only the initial user
policy: `auto`, which confirms by machine judgement only what declarations or data checks
support.

## 4. Register the sources and the glossary

```console
export SHOP_PASSWORD=shop-reader-password
"$CLI" source register --workspace ./workspace \
  --name shop --type postgresql \
  --host 127.0.0.1 --port 25433 --database shop \
  --user reader --password-env SHOP_PASSWORD

printf '%s\n' pos-reader-password | "$CLI" source register --workspace ./workspace \
  --name pos --type mysql \
  --host 127.0.0.1 --port 13307 --database pos \
  --user reader --password-stdin

"$CLI" semantic observe --workspace ./workspace --kind document \
  --title "販売ドメイン用語集" --file docs/glossary.md --principal owner
```

Every later command needs `CATALOG_PASSWORD` in its environment.

## 5. Start the agent

The distribution packages a Claude Code plugin at `plugins/scalardb-analytics/`. It holds Query
Agent Skill v1, slash commands for its workflows, and a CLI wrapper. The wrapper fills in the
workspace: `SCALARDB_ANALYTICS_WORKSPACE`, or `./workspace`.

From this directory, with `CATALOG_PASSWORD` still exported, start Claude Code with the plugin:

```console
claude --plugin-dir ../../application/build/install/scalardb-analytics-osx_arm64/plugins/scalardb-analytics
```

In Claude Code, run:

| Command | What it does |
| --- | --- |
| `/scalardb-analytics:setup` | Checks the workspace, the store password, and who you are among the semantics owners, and reports the catalog's state |
| `/scalardb-analytics:build` | Starts a watching build in the background and reviews candidates with you as they become reviewable |
| `/scalardb-analytics:ask <question>` | Answers a question with SQL composed from confirmed knowledge, with citations |
| `/scalardb-analytics:status` | Summarizes the catalog, the review queue, and build tasks |

For this runbook:

```text
/scalardb-analytics:setup
/scalardb-analytics:build
```

Claude Code asks before it runs the CLI. Choose the option that stops asking for that command, or
start Claude Code with `--allowedTools "Bash(<plugin path>/bin/scalardb-analytics:*)"`. Then
answer the candidates the agent shows you, for example "all OK", or name the ones that are wrong
and say why. When nothing is left to review:

```text
/scalardb-analytics:ask 2026年9月に EC と店舗の両方で購入した顧客の、9月の EC 売上の合計は?
```

To use another agent, give it `skills/query-agent-v1/SKILL.md`, the CLI path, the workspace path,
and your principal. Steps 6 and 7 show the commands the agent runs, so you can also run them by
hand.

## 6. Build and review (what the agent does)

The agent starts a watching build in the background and reviews with you while it runs:

```console
"$CLI" semantic build --workspace ./workspace --watch --idle-seconds 600 &
```

The build refetches the four tables' metadata and registers it. It then keeps running build tasks,
printing one `semantic-build-event` JSON line per finished task:

- Schema interpretation confirms the Datasets and Fields, keys, uniqueness, foreign-key joins, and
  date types by machine judgement. The sources declare and enforce these.
- Data checks confirm uniqueness, join multiplicity, and participation from the data.
- The language model proposes row and Field meanings from the names, the data, and the glossary.
  The policy asks for consent to these, because data cannot verify them.

The first reviewable items appear within about half a minute. The agent polls for what it has not
shown you yet, presents it, records the presentation, and submits your answers:

```console
"$CLI" semantic review --workspace ./workspace --unpresented
"$CLI" semantic present --workspace ./workspace --kind confirmation --to owner \
  --context "Reviewed in chat" --fact <fact-urn> ...
"$CLI" semantic answer --workspace ./workspace --principal owner --file answers.json
```

The watching build picks up the tasks your answers create. Confirmed Field meanings trigger
further Generators, which propose these:

- the Measures EC売上 and 店舗売上, and the business terms that name them;
- the identifier correspondence between customer and member email addresses, compared ignoring
  letter case.

A data check then measures that correspondence's set relationship. New items keep appearing for
about three rounds. The review is done when `semantic review` returns no items, and
`semantic status` shows `createdTasks` and `runningTasks` of 0.

To follow it by hand, use two terminals in this directory, with `CLI` and `CATALOG_PASSWORD` set
in both. Run the watching build in one. Run `review --unpresented`, `present`, and `answer` in the
other.

Read the catalog at any time:

```console
"$CLI" semantic status --workspace ./workspace
"$CLI" semantic overview --workspace ./workspace --kind measure
```

## 7. Answer the question (what the agent does)

The agent pins a Catalog Snapshot for the question, finds the Measure that the term 売上 refers to,
and requests the Measure's knowledge. It also reads the identifier correspondence and the joins:

```console
"$CLI" semantic question --workspace ./workspace --text "9月に EC と店舗の両方で購入した顧客の 9月の EC 売上"
"$CLI" semantic elements --workspace ./workspace --snapshot <snapshot> --term 売上
"$CLI" semantic knowledge --workspace ./workspace --snapshot <snapshot> \
  --request <measure-urn>=aggregation-rule --request <measure-urn>=filter \
  --request <measure-urn>=reference-time-field
"$CLI" semantic overview --workspace ./workspace --snapshot <snapshot> --kind identifier-correspondence
```

It composes SQL from that knowledge only, and runs it with the citation.
[`queries/both-channels-ec-sales.sql`](queries/both-channels-ec-sales.sql) is such a query:

```console
"$CLI" query --workspace ./workspace --file queries/both-channels-ec-sales.sql \
  --snapshot <snapshot> --question <question-urn> --cite <fact-revision-urn> ...
```

```json
{"schema":"scalardb-analytics/query-result","schemaVersion":"3",...,"rows":[[2,"18000"]],"truncated":false,"citation":{"snapshot":"urn:sdba:snapshot:sales:...","factRevisions":["..."],"question":"urn:sdba:question:..."}}
```

Without the knowledge, the same question goes wrong in three ways:

- Counting cancelled and returned orders gives 26,000.
- Comparing email addresses as stored misses `SATO@example.com` and gives 6,000.
- Without the identifier correspondence, nothing the sources declare links an online customer to a
  store member.

`semantic fact <fact-revision-urn>` shows why any cited Fact holds: its checks, the documents and
observations it depends on, who consented, and under which policy version.

## 8. Feedback

If you tell the agent a confirmed definition is wrong, it records a user correction against the
Fact. The correction Generator proposes a revised candidate, which comes back through review:

```console
"$CLI" semantic observe --workspace ./workspace --kind user-correction \
  --question <question-urn> --fact <fact-urn> --text "<the correction>" --run-tasks
```

When the question needs knowledge the catalog lacks, the agent reports it as missing knowledge
with `--kind missing-knowledge`. The meaning Generators read it on the next build.

## 9. Clean up

```console
docker compose down -v
rm -rf workspace
```

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| A build run has `state: failed` with `claude: ...` | Check that `claude -p "say ok"` works in this shell. Then run `semantic resume --task <id>` |
| You want Codex instead of Claude Code | `export SCALARDB_ANALYTICS_LLM=codex` before `semantic build` |
| The build should not call any model | `export SCALARDB_ANALYTICS_LLM=none`. Only declarations and data checks then run, and the LLM tasks fail until resumed with a model |
| `NO_AUTHORITY` from `semantic answer` | `--principal` must be one of the workspace's `--semantics-owner` values |
| `ALREADY_EXISTS` from `workspace init` | The store already has the catalog `sales`. Reset the databases with `docker compose down -v` and `docker compose up -d --wait` |
| You want to see what the model was asked | Read the prompts and answers under `workspace/llm-log/` |
| A watching build was stopped during a run, and its task stays `running` | `semantic resume --task <id> --running` |
| You want to check the catalog's integrity | `semantic verify` replays the log and compares it with the materialized state |
| The agent runs in a sandbox, and builds fail with `ERR_PROXY_TUNNEL` or DuckDB cannot extract its native library | The model CLI needs network access to its provider, and the JVM needs a writable temporary directory. Allow the network for the CLI, and set `JAVA_OPTS=-Djava.io.tmpdir=$TMPDIR` |
