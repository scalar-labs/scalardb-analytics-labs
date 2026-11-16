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
- `application.cli.workspace`, `.catalog`, and `.query` contain the command adapters for their
  respective use cases. Command implementations remain package-local behind small command-tree
  factories; only the composition surfaces cross package boundaries.

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

Registering a PostgreSQL source also retrieves the table metadata of its registered tables through
the PostgreSQL connector and passes it to the Semantic Catalog; the `tableMetadata` member of the
output says what the Semantic Catalog recorded for each table. MySQL sources have no connector yet,
so their retrieval is skipped. `metadata retrieve` runs the retrieval again for a registered source.
See [`../docs/application.md`](../docs/application.md#table-metadata-retrieval).

`query` accepts exactly one of `--sql`, `--file`, or `--stdin`. Successful commands and classified
operational failures write versioned JSON to stdout and return exit code `0` or `1`, respectively.
Invalid command usage returns exit code `2` through picocli. `SIGINT` cancels an active query.

The distribution packages Query Agent Skill v0 at `skills/query-agent-v0/SKILL.md`, and the
application jar carries the same file at `META-INF/scalardb-analytics/skills/query-agent-v0/SKILL.md`.
It covers only workspace selection, source and physical metadata discovery, and query execution.
