# ScalarDB Analytics Labs

ScalarDB Analytics Labs is the integration repository for building the new ScalarDB Analytics
architecture as one command-line application from Java, C++, and Rust components.

The current MVP accepts SQL through a non-interactive CLI, executes it with an embedded DuckDB
Query Engine, and returns a versioned JSON result. The CLI is the machine-to-machine boundary for
an external Query Agent; this repository does not implement the agent runtime or SQL generation.

## Run a query

Java 25 is required. The Gradle wrapper supplies the repository's Gradle version. Building the
embedded Physical Catalog also needs:

- the upstream catalog source, the `upstream/scalardb-analytics` submodule. Gradle checks it out at
  its recorded commit when it is missing or behind, which needs network access. A submodule with
  uncommitted changes at another commit fails the build instead of being overwritten.
- credentials for the private ScalarDB SQL package registry, as the `gprUsername` and
  `gprPassword` Gradle properties or the `GPR_USERNAME` and `GPR_PASSWORD` environment variables

The upstream catalog modules compile with their own Java toolchains, which Gradle provisions
automatically.

Build the CLI distribution archive:

```console
./gradlew distTar
```

The task writes `application/build/distributions/scalardb-analytics-<version>-<platform>.tar.gz`.
The packaged DuckDB extensions are built for the build host, so an archive supports only the
platform it was built on: `osx_arm64`, `linux_amd64`, or `linux_arm64`. The build fails on other
hosts.

The unpacked distribution needs only a Java 25 or later runtime, selected through `JAVA_HOME` or the
`java` command on `PATH`. A missing or older Java fails with a message that says so. It needs no
installed DuckDB, extension directory, or network download at runtime. Each command runs in one
process, with no background service.

| Path | Contents |
| --- | --- |
| `bin/scalardb-analytics` | Launcher |
| `lib/` | Runtime jars, including the packaged DuckDB extensions inside the `duckdb` jar |
| `skills/query-agent-v0/` | Query Agent Skill v0 |
| `LICENSE` | Product license |
| `licenses/` | Third-party inventory (`third-party/THIRD-PARTY.md`), the license files embedded in the bundled jars, and the licenses of the DuckDB and upstream catalog builds |
| `provenance/` | Distribution version, platform, and source revision; DuckDB extension and Physical Catalog provenance |

Unpack the archive:

```console
tar -xzf application/build/distributions/scalardb-analytics-<version>-<platform>.tar.gz
cd scalardb-analytics-<version>-<platform>
```

To try the distribution without an archive, install the same layout unarchived:

```console
./gradlew installDist
cd application/build/install/scalardb-analytics-<platform>
```

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
[quickstart runbook](examples/quickstart/README.md).

See [`application/README.md`](application/README.md) for workspace initialization, source
registration, metadata discovery, SQL input modes, time limits, and the packaged Query Agent Skill.

## Repository scope

| Path or artifact | Responsibility | Primary language and build |
| --- | --- | --- |
| `application/` | CLI, composition, lifecycle, and final distribution | Java 25 and Gradle |
| `duckdb/` | Embedded Query Engine boundary and DuckDB integration | Java and C++ product extension |
| `physical-catalog/` | Boundary to the embedded upstream Physical Catalog | Java and Gradle |
| `upstream/scalardb-analytics/` | Pinned upstream Physical Catalog source (submodule) | Java; included Gradle build |
| Semantic Catalog modules | Semantic catalog behavior | Java |
| Connector artifacts | Source access and ADBC drivers | Rust and Cargo in `scalardb-analytics-connectors` |

Root Gradle orchestrates these build units while their native build systems remain authoritative.
The architecture and rationale are documented under [`docs/`](docs/README.md).

## Development

Run all currently implemented checks:

```console
./gradlew check
```

The Physical Catalog, DuckDB, and application tests start PostgreSQL and MySQL with Testcontainers,
so a running Docker-compatible daemon is required.

All Java packages are `@NullMarked` with JSpecify. NullAway checks both production and test source
sets as compilation errors. New Java packages must declare `@NullMarked` or `@NullUnmarked`
explicitly; use `@Nullable` only where `null` is part of the contract.

Run the distribution smoke test:

```console
./gradlew distSmokeTest
```

The smoke test builds the archive, unpacks it into a clean directory, and runs the launcher with an
isolated `HOME` and temporary directory. It checks version and help output, the Java runtime check,
workspace initialization against a PostgreSQL store, source registration, a typed query, denied
unsafe SQL, and classified, redacted failures. Network access is denied except to the test
databases. On macOS, the launcher runs under `sandbox-exec`. On Linux, it runs in an
`eclipse-temurin:25-jre` container on an internal Docker network that contains only the databases.
The test needs a Docker-compatible daemon, and Gradle provisions a Java 21 toolchain to exercise the
launcher's rejection of older runtimes.

Run the query-path acceptance suite:

```console
./gradlew queryAcceptanceTest
```

It runs the archive in the same restricted environment against a PostgreSQL and a MySQL source
loaded with business fixtures. Starting from an empty workspace, it registers both sources and
queries them with the commands of Query Agent Skill v0. It checks type and value fidelity in the
JSON output, a cross-source join, deterministic output and exit codes, pushdown in the SQL each
source server logs, the time limit, output truncation, SIGINT cancellation, unsafe-SQL rejection,
and secret redaction. It fails if an invocation leaves a source session or temporary file behind,
or if a query reaches the network, loads an extension from the user's DuckDB directory, reaches a
database other than the registered sources, or is answered from anything other than the live
source. Row, byte, and memory limits are not covered because the CLI does not expose them yet.
