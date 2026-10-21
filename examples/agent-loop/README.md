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

## 5. Hand the workspace to an AI agent

The distribution packages Query Agent Skill v1 at `skills/query-agent-v1/SKILL.md`. Start an
agent, such as Claude Code, in this directory with the skill, and tell it the following:

- the CLI path;
- the workspace path;
- that `CATALOG_PASSWORD` must be set;
- that you are the semantics owner `owner`.

For example:

```text
Read ../../application/build/install/scalardb-analytics-*/skills/query-agent-v1/SKILL.md and
follow it. The CLI is ../../application/build/install/scalardb-analytics-*/bin/scalardb-analytics,
the workspace is ./workspace, and every command needs CATALOG_PASSWORD=catalog-password. I am the
semantics owner "owner".

First build the Semantic Catalog and have me review the candidates until nothing awaits review.
Then answer: 2026年9月に EC と店舗の両方で購入した顧客の、9月の EC 売上の合計は?
```

The agent then runs the two workflows of the skill. Steps 6 and 7 show the commands it runs, so
you can also run them by hand.

## 6. Build and review (what the agent does)

```console
"$CLI" semantic build --workspace ./workspace
```

The build takes about half a minute. It refetches the four tables' metadata and registers it.
Then it runs build tasks:

- Schema interpretation confirms the Datasets and Fields, keys, uniqueness, foreign-key joins, and
  date types by machine judgement. The sources declare and enforce these.
- Data checks confirm uniqueness, join multiplicity, and participation from the data.
- The language model proposes row and Field meanings from the names, the data, and the glossary.
  The policy asks for consent to these, because data cannot verify them.

The output's `summary.awaitingReview` counts what awaits the owner. The agent reads the queue,
presents it to you, records the presentation, and submits your answers:

```console
"$CLI" semantic review --workspace ./workspace
"$CLI" semantic present --workspace ./workspace --kind confirmation --to owner \
  --context "Reviewed in chat" --fact <fact-urn> ...
"$CLI" semantic answer --workspace ./workspace --principal owner --file answers.json --run-tasks
```

Confirmed Field meanings trigger further Generators. They propose the Measures EC売上 and 店舗売上,
the business terms that name them, and the identifier correspondence between customer and member
email addresses, compared ignoring letter case. A data check then measures that correspondence's
set relationship. Expect about three review rounds.

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
| You want to check the catalog's integrity | `semantic verify` replays the log and compares it with the materialized state |
| The agent runs in a sandbox, and builds fail with `ERR_PROXY_TUNNEL` or DuckDB cannot extract its native library | The model CLI needs network access to its provider, and the JVM needs a writable temporary directory. Allow the network for the CLI, and set `JAVA_OPTS=-Djava.io.tmpdir=$TMPDIR` |
