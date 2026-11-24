# Development

This document covers building, checking, and releasing ScalarDB Analytics Labs. To use a released
distribution, see the [README](README.md). The architecture and rationale are documented under
[`docs/`](docs/README.md).

## Repository scope

| Path or artifact | Responsibility | Primary language and build |
| --- | --- | --- |
| `application/` | CLI, composition, lifecycle, and final distribution | Java 25 and Gradle |
| `duckdb/` | Embedded Query Engine boundary and DuckDB integration | Java and C++ product extension |
| `physical-catalog/` | Boundary to the embedded upstream Physical Catalog | Java and Gradle |
| `upstream/scalardb-analytics/` | Pinned upstream Physical Catalog source (submodule) | Java; included Gradle build |
| `connector/` | The connectors distribution and the Java binding of its connector API library | Java and Gradle |
| Semantic Catalog modules | Semantic catalog behavior | Java |
| Connectors distribution | Connector API library, Connectors, their DuckDB extension, and ADBC drivers | Rust and C++, built by `scalardb-analytics-connectors` |

Root Gradle orchestrates these build units while their native build systems remain authoritative.

## Build prerequisites

Java 25 is required. The Gradle wrapper supplies the repository's Gradle version. Building the
embedded Physical Catalog and packaging the connectors also needs:

- the upstream catalog source, the `upstream/scalardb-analytics` submodule. Gradle checks it out at
  its recorded commit when it is missing or behind, which needs network access. A submodule with
  uncommitted changes at another commit fails the build instead of being overwritten.
- a GitHub token that can read the private `scalar-labs/scalardb-analytics-connectors` repository,
  as the `githubToken` Gradle property, the `GITHUB_TOKEN` or `GH_TOKEN` environment variable, or a
  `gh` CLI login. Gradle downloads the connectors distribution for the build host from that
  repository's GitHub release, or uses a locally built package that the `connectorsArchive` Gradle
  property names; see [`connector/README.md`](connector/README.md).
- credentials for the private ScalarDB SQL package registry, as the `gprUsername` and
  `gprPassword` Gradle properties or the `GPR_USERNAME` and `GPR_PASSWORD` environment variables

The upstream catalog modules compile with their own Java toolchains, which Gradle provisions
automatically.

## Build the distribution

Build the CLI distribution archive:

```console
./gradlew distTar
```

The task writes `application/build/distributions/scalardb-analytics-<version>-<platform>.tar.gz`.
The packaged DuckDB extensions are built for the build host, so an archive supports only the
platform it was built on: `osx_arm64`, `linux_amd64`, or `linux_arm64`. The build fails on other
hosts.

To try the distribution without an archive, install the same layout unarchived:

```console
./gradlew installDist
cd application/build/install/scalardb-analytics-<platform>
```

## Checks and tests

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
loaded with business fixtures, both read through their Connectors. Starting from an empty workspace, it registers both sources and
queries them with the commands of the Query Agent Skill. It checks type and value fidelity in the
JSON output, a cross-source join, deterministic output and exit codes, pushdown in the SQL each
source server logs, the time limit, output truncation, SIGINT cancellation, unsafe-SQL rejection,
and secret redaction. It fails if an invocation leaves a source session or temporary file behind,
or if a query reaches the network, loads an extension from the user's DuckDB directory, reaches a
database other than the registered sources, or is answered from anything other than the live
source. Row, byte, and memory limits are not covered because the CLI does not expose them yet.

## Versions

The product version uses calendar versioning, `YYYY.M.D`: the release date without zero padding,
such as `2026.11.24`. Gradle takes it from the nearest release tag `v<version>` through
`git describe`. A build of the tagged commit has the tag's version; a later commit appends the
commit count and abbreviated hash, and `dirty` for uncommitted changes, as semver build metadata,
such as `2026.11.24+3.gabc1234`. Without a release tag in the history the version is `0.0.0` with
that metadata, and outside a Git checkout it is `0.0.0+unknown`. The connectors distribution is
versioned separately and pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Continuous integration

The [`Package`](.github/workflows/package.yml) workflow builds the distribution archive of every
platform on pull requests and on `main`. The Linux jobs also run `check`, `distSmokeTest`, and
`queryAcceptanceTest`; the macOS job only builds, because its runners have no Docker. The workflow
needs these repository secrets:

- `PRIVATE_REPOSITORY_TOKEN`: a token that can read the `upstream/scalardb-analytics` submodule and
  the `scalar-labs/scalardb-analytics-connectors` releases
- `GPR_USERNAME` and `GPR_PASSWORD`: credentials for the ScalarDB SQL package registry

## Release

1. Move the `Unreleased` entries of [`CHANGELOG.md`](CHANGELOG.md) into a section for the release
   date's version, naming the pinned connectors version when it changed, and merge that to `main`.
2. Push the tag `v<version>` on that commit. The workflow builds the archives with the tag's
   version, checks that the changelog has the version's section, and creates the GitHub Release
   with the three archives and that section as its notes.
