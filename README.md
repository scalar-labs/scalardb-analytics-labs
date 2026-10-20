# ScalarDB Analytics Labs

ScalarDB Analytics Labs is the integration repository for building the new ScalarDB Analytics
architecture as one command-line application from Java, C++, and Rust components.

The current MVP accepts SQL through a non-interactive CLI, executes it with an embedded DuckDB
Query Engine, and returns a versioned JSON result. The CLI is the machine-to-machine boundary for
an external Query Agent; this repository does not implement the agent runtime or SQL generation.

## Run a query

Java 25 is required. The Gradle wrapper supplies the repository's Gradle version.

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
| `duckdb/` | Embedded Query Engine boundary and DuckDB integration | Java today; C++ for product extensions |
| Semantic and Physical Catalog modules | Catalog behavior and the boundary to the existing implementation | Java and native integration |
| Connector artifacts | Source access and ADBC drivers | Rust and Cargo in `scalardb-analytics-connectors` |

Root Gradle orchestrates these build units while their native build systems remain authoritative.
The architecture and rationale are documented under [`docs/`](docs/README.md).

## Development

Run all currently implemented checks:

```console
./gradlew check
```

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
