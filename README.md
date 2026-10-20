# ScalarDB Analytics Labs

This repository is the integration repository for the new ScalarDB Analytics architecture. It
combines independently built Java, C++, and Rust artifacts into one user-facing distribution. It is
not a Java-only project, even though the currently implemented application module is built with
Gradle.

## Repository boundaries

| Path or artifact | Responsibility | Primary language and build |
| --- | --- | --- |
| `application/` | CLI, composition, lifecycle, and final distribution | Java 25 and Gradle; GraalVM Native Image will be added later |
| `semantic-catalog/` | Semantic state, evidence, decisions, and catalog construction | Java 25 and Gradle |
| `physical-catalog/` | Adapter and native boundary around the existing Physical Catalog | Java 25, Gradle, and GraalVM Native Image |
| `duckdb/` | DuckDB extension and product integration | C++ and the DuckDB extension toolchain |
| `end-to-end-tests/` | Tests of the assembled product | Integration-specific tooling, to be decided with the first tests |
| `upstream/scalardb-analytics/` | Pinned existing Analytics implementation | Git submodule; not present yet |
| Connector artifacts | Source access and ADBC drivers | Built with Cargo in `scalardb-analytics-connectors` and consumed here |

The Query Agent is an external consumer. This repository provides a machine-readable CLI and a
versioned Agent Skill, but does not implement the Query Agent runtime, model, planning, or SQL
generation.

## Build orchestration

Gradle is the repository-wide build entry point and orchestrator. It builds the Java modules
directly and will delegate native compilation to each module's authoritative build tool. The root
build will connect those independently testable build units through declared inputs, outputs, and
task dependencies; it will not reproduce CMake or Cargo build logic in Gradle.

- Java modules are Gradle subprojects included by `settings.gradle.kts`.
- `duckdb/` will own its C++ and DuckDB extension configuration. Root Gradle tasks will invoke that
  build and consume its artifacts.
- Connectors remain owned and built by `scalardb-analytics-connectors`. Root Gradle tasks will
  obtain and verify versioned connector artifacts instead of compiling their Rust sources here.
- Root lifecycle tasks such as `check` and `build` cover every implemented build unit wired into
  the repository. At present, only the Java application is implemented.
- `application` owns the final Native Image and distribution assembly.

Run all currently implemented checks:

```console
./gradlew check
```

Build the current CLI distribution:

```console
./gradlew :application:installDist
```

Run it:

```console
application/build/install/scalardb-analytics/bin/scalardb-analytics version
```

See [`docs/architecture/build-boundaries.md`](docs/architecture/build-boundaries.md) for the
boundary decision and its verification status.
