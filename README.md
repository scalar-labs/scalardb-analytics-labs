# ScalarDB Analytics Labs

ScalarDB Analytics Labs is the integration repository for building the new ScalarDB Analytics
architecture as one command-line application from Java, C++, and Rust components.

The current MVP accepts SQL through a non-interactive CLI, executes it with an embedded DuckDB
Query Engine, and returns a versioned JSON result. The CLI is the machine-to-machine boundary for
an external Query Agent; this repository does not implement the agent runtime or SQL generation.

To build the application or change it, see [`DEVELOPMENT.md`](DEVELOPMENT.md).

## Get the distribution

Each [GitHub Release](https://github.com/scalar-labs/scalardb-analytics-labs/releases) carries one
distribution archive per platform: `osx_arm64`, `linux_amd64`, and `linux_arm64`. An archive runs
only on its platform. The repository is private, so download it with an authorized `gh` CLI, for
example:

```console
gh release download v<version> --repo scalar-labs/scalardb-analytics-labs \
  --pattern 'scalardb-analytics-<version>-<platform>.tar.gz'
tar -xzf scalardb-analytics-<version>-<platform>.tar.gz
cd scalardb-analytics-<version>-<platform>
```

The [changelog](CHANGELOG.md) lists what each release adds or changes.

The unpacked distribution needs only a Java 25 or later runtime, selected through `JAVA_HOME` or the
`java` command on `PATH`. A missing or older Java fails with a message that says so. It needs no
installed DuckDB, extension directory, or network download at runtime. Each command runs in one
process, with no background service.

| Path | Contents |
| --- | --- |
| `bin/scalardb-analytics` | Launcher |
| `lib/` | Runtime jars, including the packaged DuckDB extensions inside the `duckdb` jar |
| `skills/query-agent-v1/` | Query Agent Skill v1 |
| `LICENSE` | Product license |
| `licenses/` | Third-party inventory (`third-party/THIRD-PARTY.md`), the license files embedded in the bundled jars, and the licenses of the DuckDB and upstream catalog builds |
| `provenance/` | Distribution version, platform, and source revision; DuckDB extension and Physical Catalog provenance |

## Run a query

Initialize a workspace. The named environment variable supplies the Physical Catalog store
password; the password is not written to the workspace. The workspace is trusted,
operator-managed configuration, and credentials must not be embedded in the JDBC URL:

```console
bin/scalardb-analytics workspace init \
  --workspace ./analytics-workspace \
  --catalog analytics \
  --store-jdbc-url jdbc:postgresql://localhost:5432/catalog \
  --store-user catalog_user \
  --store-password-env CATALOG_PASSWORD
```

Query through the workspace (registered sources are addressed as `source.namespace.table`):

```console
bin/scalardb-analytics query \
  --workspace ./analytics-workspace \
  --sql "SELECT 1 AS answer"
```

The command returns a versioned JSON envelope:

```json
{"schema":"scalardb-analytics/query-result","schemaVersion":"1","executionId":"...","fields":[{"name":"answer","type":"Int(32, true)","nullable":true,"children":[]}],"rows":[[1]],"truncated":false}
```

To try the whole flow against local sample databases, follow the
[quickstart runbook](examples/quickstart/README.md). The
[agent-loop runbook](examples/agent-loop/README.md) adds the Semantic Catalog: an AI agent builds
it, has it reviewed, and answers a business question with cited SQL.

See [`application/README.md`](application/README.md) for workspace initialization, source
registration, metadata discovery, SQL input modes, time limits, and the packaged Query Agent Skill.

## Versions

Releases use calendar versioning, `YYYY.M.D`: the release date without zero padding, such as
`2026.11.24`. `bin/scalardb-analytics version` prints the version. A build from a commit after a
release carries that release's version with semver build metadata, such as
`2026.11.24+3.gabc1234`: the number of commits since the release and the abbreviated commit hash.
