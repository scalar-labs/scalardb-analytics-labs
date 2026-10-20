# Build boundary decision

## Status

Accepted as the starting structure. Native integration details remain open until their first
implementation.

## Decision

ScalarDB Analytics is one product distribution assembled from several build units:

- Root Gradle is the repository-wide build entry point and orchestrates all implemented build
  units.
- Gradle builds the Java application, Semantic Catalog, and Physical Catalog adapter directly.
- The DuckDB integration uses the C++ and DuckDB extension toolchain in `duckdb/`; Gradle delegates
  to that build and consumes its declared artifacts.
- The connector API library and owned ADBC drivers use Cargo in the separate
  `scalardb-analytics-connectors` repository; Gradle obtains and verifies their versioned
  artifacts.
- The application owns final composition and distribution. This responsibility does not move
  Semantic Catalog, Physical Catalog, DuckDB, or connector implementation logic into the
  application module.

Gradle owns orchestration, dependency ordering, aggregate lifecycle tasks, and distribution inputs.
Each native build remains independently executable and independently testable, and owns its
compiler-specific configuration. Gradle tasks delegate to those builds without hiding or
duplicating their native contracts.

## Dependency direction

```text
application (Java composition root)
├── semantic-catalog (Java module)
├── physical-catalog (Java-to-native boundary)
├── duckdb (C++ extension artifact)
└── connector artifacts (external Rust build)
```

The Query Agent is outside this dependency graph. It consumes the product CLI and Agent Skill.

## Current implementation map

| Model element | Owner or entry point | Evidence |
| --- | --- | --- |
| CLI process and command dispatch | `application` | `ScalarDbAnalyticsCli` and its behavioral tests |
| Machine-readable product version | `application` | `version` command JSON contract test |
| Repository build orchestration | Root Gradle build | Root lifecycle tasks and `settings.gradle.kts` |
| Semantic Catalog | Planned `semantic-catalog` module | Product architecture; no implementation yet |
| Physical Catalog integration | Planned `physical-catalog` module | Product architecture; no implementation yet |
| Query Engine integration | Planned `duckdb` module | Product architecture; no implementation yet |
| Connector implementation | External connector repository | No artifact consumption is configured yet |

## Preserved contract and limitations

- The existing CLI command, exit code, and JSON output remain unchanged.
- Java compilation, formatting, tests, and the CLI distribution are currently wired into the root
  Gradle lifecycle.
- No C++, Rust, Native Image, or end-to-end build is claimed to exist yet.
- Native task names, inputs, outputs, and distribution assembly remain unresolved until their
  concrete build contracts are implemented.
