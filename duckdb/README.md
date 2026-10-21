# DuckDB integration module

This module owns the embedded Query Engine integration. Its public Java surface describes query
capabilities and results without exposing JDBC types. It embeds DuckDB through its JDBC artifact,
builds the product-owned C++ extension against the matching DuckDB release, and loads that packaged
extension when the Query Engine starts.

The target distribution keeps DuckDB and product integrations as loadable native artifacts. The
root Gradle build will delegate their compilation to the DuckDB extension toolchain and assemble
them with the application. Runtime extension downloads remain disabled; required extensions must
come from the verified product distribution.

`buildNativeExtension` is the Gradle entry point for the native build. The delegated build downloads
the official DuckDB source amalgamation and platform static library, verifies their SHA-256 digests,
and produces `scalardb_analytics.duckdb_extension`.

The extension's host ABI is declared in `src/main/cpp/include/scalardb_analytics_host.h`. The build
also records the extension's DuckDB source, checksums, and platform in
`META-INF/scalardb-analytics-labs/duckdb-extension.properties`.

See [`../docs/query-engine.md`](../docs/query-engine.md) for the Query Engine mental model.
