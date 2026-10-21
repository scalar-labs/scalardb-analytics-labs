# DuckDB integration module

This module owns the embedded Query Engine integration. Its public Java surface describes query
capabilities and results without exposing JDBC types. It embeds DuckDB through its JDBC artifact,
builds the product-owned C++ extension against the matching DuckDB release, and loads that packaged
extension when the Query Engine starts.

The target distribution keeps DuckDB and product integrations as loadable native artifacts. The
root Gradle build will delegate their compilation to the DuckDB extension toolchain and assemble
them with the application. Runtime extension downloads remain disabled; required extensions must
come from the verified product distribution.

The packaged extension set is the product extension plus DuckDB's official PostgreSQL and MySQL
scanner extensions, all bound to one DuckDB release:

- `gradle/libs.versions.toml` owns the version set: the DuckDB release (`duckdb`), the JDBC
  binding (`duckdb-jdbc`), and the upstream commits of the scanners. `verifyDuckDbVersionSet` fails
  the build when the JDBC binding does not embed that release, and the dependency is pinned
  strictly.
- `buildNativeExtension` downloads the official DuckDB source amalgamation and platform static
  library, verifies their SHA-256 digests, and produces `scalardb_analytics.duckdb_extension`.
- `acquireScannerExtensions` downloads the signed scanner binaries, verifies their pinned SHA-256
  digests, and checks each footer for the CPP ABI, the DuckDB release, the platform, the scanner
  commit, and a signature.
- The native scripts pin checksums per DuckDB release and fail for a release they have not pinned.

Verified downloads are cached under `<gradle-user-home>/caches/scalardb-analytics-labs/duckdb`,
keyed by checksum, so a clean build needs no network once the cache is populated. With
`--offline`, a missing cache entry fails instead of being downloaded.

Supported platforms are `osx_arm64`, `linux_amd64`, and `linux_arm64`. Extensions are built for the
build host only; other hosts fail the build with a diagnostic. At startup the Query Engine compares
the packaged platform and DuckDB release with the embedded DuckDB and fails initialization with a
diagnostic that names the mismatch.

At runtime the Query Engine opens DuckDB with automatic installation and loading and community
extensions disabled, and with an application-owned `extension_directory` instead of the per-user
default. It loads each packaged extension by absolute path, attaches the given `SourceAttachment`s,
and then disables external access. The interim scanner read path for PostgreSQL and MySQL lives in
the `scanner` package.

The scanner tests start PostgreSQL and MySQL with Testcontainers, so `:duckdb:test` requires a
Docker-compatible daemon.

The extension's host ABI is declared in `src/main/cpp/include/scalardb_analytics_host.h`. Each
packaged extension's provenance (source, checksums, platform, DuckDB release, licenses, and, for
the scanners, upstream commit and statically linked components) is recorded in
`META-INF/scalardb-analytics-labs/duckdb-extensions/<extension>.properties`. The license texts of
DuckDB and of the components statically linked into the scanners are in `META-INF/licenses/`.

See [`../docs/query-engine.md`](../docs/query-engine.md) for the Query Engine mental model.
