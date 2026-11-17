# Agent-loop runbook

This runbook takes the CLI distribution from a fresh build to an AI agent that builds the
Semantic Catalog, has its semantics owner review what the catalog could not confirm by itself,
and answers a business question with SQL composed from confirmed knowledge and cited. The agent
follows Query Agent Skill v1, which the distribution packages.

Docker Compose starts every database locally with sample data:

| Service | Role | Host port | Contents |
| --- | --- | --- | --- |
| `catalog-store` | Store of the Physical Catalog and the Semantic Catalog (PostgreSQL) | `15433` | Empty; the workspace creates its catalog here |
| `shop` | E-commerce source (PostgreSQL) | `25433` | `public.customers`: 8 customers; `public.orders`: 12 orders with status codes |
| `pos` | In-store point-of-sale source (MySQL) | `13307` | `pos.members`: 6 members; `pos.store_sales`: 8 sales |

[`docs/glossary.md`](docs/glossary.md) is the sales domain's glossary, in Japanese. It defines EC
sales, store sales, the order status codes, member ranks, and how online customers and store
members are matched. The tables do not declare that matching: an online customer and a store
member are the same person when their email addresses match ignoring letter case.

The question this runbook answers:

> 2026年9月に EC と店舗の両方で購入した顧客の、9月の EC 売上の合計は?
>
> (What were the September 2026 EC sales of the customers who bought both online and in store
> that month?)

The answer is 18,000 yen from two customers. Getting it right needs knowledge that no table
declares:

- which orders count as sales: not cancelled or returned ones;
- which date places an order in a month: the order date;
- that the two sources identify the same person by email address;
- that the email addresses are compared without letter case.

Without it, the same question goes wrong: counting cancelled and returned orders gives 26,000;
comparing email addresses as stored misses `SATO@example.com` and gives 6,000; and nothing the
sources declare links an online customer to a store member at all.

The passwords in `compose.yaml` and below are example values for this disposable environment. Each
source grants the CLI a read-only account.

## Prerequisites

- Java 25 or later, selected through `JAVA_HOME` or the `java` command on `PATH`
- Docker with Docker Compose
- What the repository build needs; see [Run a query](../../README.md#run-a-query)
- A signed-in [Claude Code](https://claude.com/claude-code) (`claude`) on `PATH`. The catalog uses
  it as its language model, and you run the agent in it. Check it with `claude -p "say ok"`.
- `jq`, to edit the workspace configuration below

Ports `15433`, `25433`, and `13307` must be free on `127.0.0.1`.

## 1. Build the distribution

From the repository root:

```console
./gradlew installDist
cd examples/agent-loop
export PATH="$(cd ../../application/build/install/scalardb-analytics-*/bin && pwd):$PATH"
scalardb-analytics version
```

The rest of the runbook runs in `examples/agent-loop`, with the launcher on `PATH` so the agent
can call it as `scalardb-analytics`.

## 2. Start the databases

```console
docker compose up -d --wait
```

## 3. Initialize the workspace

The workspace binds this directory's `workspace/` to one catalog, whose Physical Catalog and
Semantic Catalog are both stored in the catalog store database:

```console
export CATALOG_PASSWORD=catalog-password
scalardb-analytics workspace init \
  --workspace ./workspace \
  --catalog sales \
  --store-jdbc-url jdbc:postgresql://127.0.0.1:15433/catalog \
  --store-user catalog \
  --store-password-env CATALOG_PASSWORD
```

Every later command needs `CATALOG_PASSWORD` in its environment.

Give the Semantic Catalog its language model and its readers' language. The glossary and the
sample data are Japanese, so the domain declares Japanese as its primary language:

```console
jq '.semanticCatalog = {
      "languageModel": {"adapter": "claude", "model": "claude-sonnet-5-5"},
      "languages": ["ja"],
      "primaryLanguage": "ja"
    }' workspace/workspace.json > workspace/workspace.json.new \
  && mv workspace/workspace.json.new workspace/workspace.json
```

Set them before the first registration below, which creates the domain. See
[`../../application/README.md`](../../application/README.md#standalone-cli) for the other settings.

## 4. Register the sources and the glossary

```console
export SHOP_PASSWORD=shop-reader-password
scalardb-analytics source register --workspace ./workspace \
  --name shop --type postgresql \
  --host 127.0.0.1 --port 25433 --database shop \
  --user reader --password-env SHOP_PASSWORD

printf '%s\n' pos-reader-password | scalardb-analytics source register --workspace ./workspace \
  --name pos --type mysql \
  --host 127.0.0.1 --port 13307 --database pos \
  --user reader --password-stdin

scalardb-analytics semantic document register --workspace ./workspace docs/glossary.md
```

Registering a source passes the metadata of its four tables to the Semantic Catalog, and
registering the glossary makes it a document the catalog's Generators read and cite. Both create
build tasks; nothing has run them yet.

## 5. Start the agent

Give Claude Code the packaged skill, and start it in this directory with `CATALOG_PASSWORD` still
exported:

```console
mkdir -p .claude/skills
cp -R ../../application/build/install/scalardb-analytics-*/skills/query-agent-v1 \
  .claude/skills/scalardb-analytics-query-agent
claude
```

Ask it to build and review the catalog with you, with the workspace `./workspace`:

```text
./workspace の Semantic Catalog を構築して、レビューが必要なものを見せてください。
```

The agent starts a watching build in the background, reads the review list as items appear, and
presents them per table: items the review material suggests consenting to, one line each, for
consent together, and the items that need judgement one by one. Answer them, for example "all
OK", or name the ones that are wrong and say what is right. You are the semantics owner here: the
CLI records the person running it as the deciding party, so the agent submits only the answers you
give. Confirmed knowledge lets more Generators run, so new items keep appearing for a while. The
review is done when nothing awaits review and no build task is created or running.

Then ask the question:

```text
2026年9月に EC と店舗の両方で購入した顧客の、9月の EC 売上の合計は?
```

The agent fixes one Catalog Snapshot, reads the knowledge the question needs, composes one
`SELECT` from confirmed knowledge, runs it, and answers with the Catalog Snapshot URN and the Fact
Revision URNs it used. If knowledge is missing, it says so and registers usage feedback instead of
guessing. [`queries/both-channels-ec-sales.sql`](queries/both-channels-ec-sales.sql) is the query
the confirmed knowledge leads to.

Claude Code asks before it runs a command. Choose the option that stops asking for
`scalardb-analytics`, or start it with `--allowedTools "Bash(scalardb-analytics:*)"`.

## 6. The commands behind it

The agent runs the commands Query Agent Skill v1 documents. To follow the loop by hand, use two
terminals in this directory, with `PATH` and `CATALOG_PASSWORD` set in both.

Run a watching build in one. It prints one JSON line per finished build task, with
`awaitingReview`, until interrupted:

```console
scalardb-analytics semantic build --workspace ./workspace --watch
```

In the other, review, present, and answer:

```console
scalardb-analytics semantic review --workspace ./workspace
scalardb-analytics semantic present --workspace ./workspace --kind confirmation \
  --fact <fact-urn> --to owner --context "Reviewed by hand"
scalardb-analytics semantic answer --workspace ./workspace --file - <<'EOF'
{"answers": [{"fact": "<fact-urn>", "type": "consent", "wording": "OK", "presentation": <n>}]}
EOF
```

Then read and query at one Catalog Snapshot:

```console
scalardb-analytics semantic read --workspace ./workspace --recursive
scalardb-analytics semantic elements --workspace ./workspace --snapshot <snapshot> --term EC売上
scalardb-analytics semantic knowledge --workspace ./workspace --snapshot <snapshot> \
  --element <measure-urn>
scalardb-analytics query --workspace ./workspace --file queries/both-channels-ec-sales.sql
scalardb-analytics semantic fact --workspace ./workspace --snapshot <snapshot> <fact-revision-urn>
```

The query returns two customers and 18,000. `semantic fact` shows why a cited Fact holds: its
checks, what it depends on, its confirmation path, and who consented.

## 7. Clean up

```console
docker compose down -v
rm -rf workspace .claude
```

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| `semantic build` fails with `LANGUAGE_MODEL_NOT_CONFIGURED` | Set `semanticCatalog.languageModel` as in step 3 |
| A build task line has `"state": "failed"` | Read its `reason`; check that `claude -p "say ok"` works in this shell, then run `scalardb-analytics semantic resume --workspace ./workspace <task-number>` |
| `semantic build` fails with `BUILD_RUN_IN_PROGRESS` | Another process, such as the agent's watching build, runs the tasks; only one runs them at a time |
| `ALREADY_EXISTS` from `workspace init` | The store already has the catalog `sales`. Reset the databases with `docker compose down -v` and `docker compose up -d --wait` |
