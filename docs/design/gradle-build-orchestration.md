# Should Gradle orchestrate the polyglot repository build?

## Current answer

Yes. Root Gradle is the repository-wide build entry point and orchestrator. It builds Java modules
directly, delegates native compilation to each component's authoritative build system, and owns
aggregate lifecycle and distribution assembly dependencies.

## Rationale

The application and its composition logic are Java-based, so Gradle provides a natural entry point
for the product build and Java dependency graph. A single root entry point also lets CI and
developers run consistent aggregate `check`, `assemble`, and distribution tasks.

CMake, the DuckDB extension toolchain, and Cargo encode compiler- and ecosystem-specific behavior
that Gradle should not duplicate. Delegation keeps those build units independently executable and
testable while still making their declared artifacts inputs to the product build.

## Consequences

- Java modules are Gradle subprojects.
- Native modules retain their own build configuration and expose explicit build outputs to Gradle.
- Connector sources remain in their owning repository; this build consumes the connectors'
  distribution, downloaded from the connectors' GitHub release, and builds no Rust.
- The application owns final distribution assembly without absorbing component implementation
  logic.
- Root lifecycle tasks cover every build unit connected to the repository build.
