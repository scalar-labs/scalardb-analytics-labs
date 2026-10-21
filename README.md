# ScalarDB Analytics Labs

ScalarDB Analytics Labs is the integration repository for building the new ScalarDB Analytics
architecture as one command-line application from Java, C++, and Rust components.

The current MVP accepts SQL through a non-interactive CLI, executes it with an embedded DuckDB
Query Engine, and returns a versioned JSON result. The CLI is the machine-to-machine boundary for
an external Query Agent; this repository does not implement the agent runtime or SQL generation.

## Run a query

Java 25 is required. The Gradle wrapper supplies the repository's Gradle version. Building the
embedded Physical Catalog also needs:

- the upstream catalog source: `git submodule update --init`
- credentials for the private ScalarDB SQL package registry, as the `gprUsername` and
  `gprPassword` Gradle properties or the `GPR_USERNAME` and `GPR_PASSWORD` environment variables

The upstream catalog modules compile with their own Java toolchains, which Gradle provisions
automatically.

Build the CLI distribution:

```console
./gradlew :application:installDist
```

Run a query:

```console
application/build/install/scalardb-analytics/bin/scalardb-analytics query --sql "SELECT 1 AS answer"
```

The command returns a versioned JSON envelope:

```json
{"schema":"scalardb-analytics/query-result","schemaVersion":"3","executionId":"...","fields":[{"name":"answer","type":"Int(32, true)","nullable":true,"children":[]}],"rows":[[1]],"truncated":false}
```

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

Build the current CLI distribution:

```console
./gradlew :application:installDist
```

Inspect the available CLI commands:

```console
application/build/install/scalardb-analytics/bin/scalardb-analytics --help
```
