# Application module

This Java module is the product composition root and owns the user-facing application lifecycle.
It provides the machine-readable CLI and `ApplicationRuntime`, which starts the embedded Physical
Catalog, then starts the Query Engine with the catalog registered into its extension and the
catalog's data sources attached, in dependency order. It will later assemble the Semantic Catalog,
DuckDB extensions, and connector artifacts into the distributed application.

The module does not own the domain logic of those components. The Query Agent is also outside the
module; the application will provide only its CLI-facing Agent Skill.

The source packages expose the same ownership boundaries as the runtime flow:

- `application` owns process composition, shared runtime lifecycle, and classified application
  failures.
- `application.workspace` owns workspace validation, persistence, and initialization consistency.
- `application.cli` composes picocli and owns the shared machine-readable output contract.
- `application.metadata` owns table metadata retrieval, and `application.construction` owns what
  the Semantic Catalog's build tasks run with and running them.
- `application.cli.workspace`, `.catalog`, `.query`, and `.semantic` contain the command adapters
  for their respective use cases. Command implementations remain package-local behind small
  command-tree factories; only the composition surfaces cross package boundaries, such as the
  build command that takes interrupts.

Dependencies point from the CLI adapters toward the workspace and application runtime. The
workspace opens the runtime without exposing its persisted password or Physical Catalog settings.
This structure is intended to keep changes to one command family locally reviewable without
splitting each individual command into a separate layer or module.

See [`../docs/architecture.md`](../docs/architecture.md) for component ownership and the
representative query flow.

## Standalone CLI

Every state-bearing command uses an initialized workspace. `workspace init` writes
`workspace.json`, which binds the directory to exactly one Physical Catalog catalog. The file stores
the catalog store JDBC URL, optional user, and the name of the environment variable holding the
store password; it never stores the password. Treat the workspace as trusted operator-managed
configuration. The CLI refuses symbolic-link or group/other-writable workspace configuration,
creates new configuration with private permissions on POSIX file systems, and rejects JDBC URLs
that embed usernames or passwords.

```console
scalardb-analytics workspace init \
  --workspace ./analytics-workspace \
  --catalog analytics \
  --store-jdbc-url jdbc:postgresql://localhost:5432/catalog \
  --store-user catalog_user \
  --store-password-env CATALOG_PASSWORD
```

`workspace.json` may also set the workspace's Semantic Catalog settings under `semanticCatalog`.
`workspace init` writes none of them, and an unset one takes its default:

| Key | Default | Meaning |
| --- | --- | --- |
| `sampleSize` | `100000` | The most rows construction-run SQL reads of a sampled table, at most `10000000` |
| `constructionQueryTimeLimitSeconds` | `60` | How long one construction-run query may take, at most `86400` |
| `languageModel` | none | The language model the build tasks call; `semantic build` needs it |
| `modelInput` | see below | Which data reads reach the language model |

`languageModel` names the adapter that reaches the model and the concrete model it uses (see
[`../docs/semantic-catalog/generators.md`](../docs/semantic-catalog/generators.md#reaching-a-language-model)):

| `adapter` | Other keys |
| --- | --- |
| `claude` | `model`, a model ID starting with `claude-` that is not an alias |
| `codex` | `model`, passed to `codex exec -m` |
| `command` | `command`, run with `sh -c`, reading the prompt on stdin and writing the answer to stdout; `model`, the identity of the model it reaches |

Each also takes `timeoutSeconds`, how long one answer may take, `300` by default:

```json
"semanticCatalog": {
  "languageModel": { "adapter": "claude", "model": "claude-sonnet-5-5" }
}
```

`modelInput` decides which data reads meaning inference passes to the model (see
[`../docs/semantic-catalog/generators.md`](../docs/semantic-catalog/generators.md#input-passed-to-external-models)).
A withheld read is not made and is listed among the build task's missing inputs:

| Key | Default | Meaning |
| --- | --- | --- |
| `countStatistics` | `true` | Row count, and each column's null ratio and distinct count, over the sample |
| `valueSamples` | `false` | Up to five distinct values of each column, over the sample |

Register, inspect, and query sources:

```console
scalardb-analytics source register \
  --workspace ./analytics-workspace \
  --name warehouse --type postgresql \
  --host localhost --port 5432 --database warehouse --user reader \
  --password-env WAREHOUSE_PASSWORD

printf '%s\n' "$CRM_PASSWORD" | scalardb-analytics source register \
  --workspace ./analytics-workspace \
  --name crm --type mysql \
  --host localhost --port 3306 --database crm --user reader \
  --password-stdin

scalardb-analytics source list --workspace ./analytics-workspace
scalardb-analytics source show --workspace ./analytics-workspace warehouse
scalardb-analytics metadata list --workspace ./analytics-workspace --source warehouse
scalardb-analytics metadata retrieve --workspace ./analytics-workspace --source warehouse
scalardb-analytics query \
  --workspace ./analytics-workspace \
  --time-limit-seconds 30 \
  --sql 'SELECT * FROM warehouse.public.orders LIMIT 10'
```

Registering a source also retrieves the table metadata of its registered tables through the
source's connector and passes it to the Semantic Catalog; the `tableMetadata` member of the output
says what the Semantic Catalog recorded for each table. `metadata retrieve` runs the retrieval again
for a registered source.
See [`../docs/application.md`](../docs/application.md#table-metadata-retrieval).

Registration creates Semantic Catalog build tasks; `semantic build` runs them:

```console
scalardb-analytics semantic build --workspace ./analytics-workspace
scalardb-analytics semantic build --workspace ./analytics-workspace --watch
```

It prints one `scalardb-analytics/build-task-event` line per finished build task, with the task,
its result or failure reason, and `awaitingReview`, the number of items awaiting review once it
finished, and then one `scalardb-analytics/build-run` line with the tasks a stopped run had left
`running` and returned to `created` (`reset`) and the numbers that `succeeded` and `failed`. A failed
task is reported, not a command failure. Without `--watch` it returns once no created task remains;
with `--watch` it keeps picking up new tasks until interrupted: the first `SIGINT` stops it after
the current task, and a second stops the process at once. Only one process runs the workspace's
build tasks at a time; another fails at once with `BUILD_RUN_IN_PROGRESS`. Without
`semanticCatalog.languageModel` it fails with `LANGUAGE_MODEL_NOT_CONFIGURED` before running any
task.

`semantic document register` registers a document the semantics owner wrote, such as a glossary,
which meaning inference reads and can cite:

```console
scalardb-analytics semantic document register --workspace ./analytics-workspace glossary.md
scalardb-analytics semantic document register --workspace ./analytics-workspace \
  --resource <inputResource> glossary.md
```

It prints one `scalardb-analytics/document-registration` line with `recorded`, the document's
`inputResource`, and its latest `inputObservation`. Without `--resource` it registers a new document;
pass the `inputResource` it printed to register a changed version of the same document. Unchanged
text records nothing and prints `recorded: false`. A `--resource` that names no document fails with
`DOCUMENT_REGISTRATION_FAILED`. A new observation creates a concept-inference build task for the
tables the document names and an analytical-semantics-inference build task, which proposes the
Measures and business terms the document defines; the next build tasks of meaning inference also
read it.

`query` accepts exactly one of `--sql`, `--file`, or `--stdin`. Successful commands and classified
operational failures write versioned JSON to stdout and return exit code `0` or `1`, respectively.
Invalid command usage returns exit code `2` through picocli. `SIGINT` cancels an active query.

The distribution packages Query Agent Skill v0 at `skills/query-agent-v0/SKILL.md`, and the
application jar carries the same file at `META-INF/scalardb-analytics/skills/query-agent-v0/SKILL.md`.
It covers only workspace selection, source and physical metadata discovery, and query execution.
