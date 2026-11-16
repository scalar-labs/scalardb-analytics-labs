# Connector module

This module carries the scalardb-analytics-connectors distribution and is the Java binding of its
connector API library. The distribution, one per platform, holds the connector API library with
the Connectors built into it, the connectors' DuckDB extension, the ADBC drivers those Connectors
load, the library's C header, and the license texts of everything in it.

## The carried distribution

Until the connectors repository publishes releases, the distribution it builds is carried unpacked
in [`distribution/`](distribution), as
`scalardb-analytics-connectors-<version>-<platform>/`, for the version in
`gradle/libs.versions.toml` (`scalardb-analytics-connectors`). Only `osx_arm64` is carried. To
update it, replace the directory with the newly built package unpacked.

The jar carries the distribution's `lib/`, `duckdb/`, and `adbc-drivers/` under
`native/connectors/` with their layout intact, and its license texts under
`META-INF/licenses/connectors/`.

## Java binding

`com.scalar.db.analytics.labs.connector` calls the library through the Java FFM API, following the
package's `include/scalardb_analytics_connector.h`:

- `ConnectorPackage.forProcess()` stages the carried package once per process into a private
  temporary directory, whole, because its parts find one another relative to themselves, and
  deletes it when the process exits. Every user of the library loads it from there: the dynamic
  loader identifies the library by its install name, so a second staged copy would resolve to the
  library already loaded.
- `ConnectorLibrary.open()` loads that library and initializes it with the package's driver
  directory, and `close()` shuts it down. The library allows one initialization per process at a
  time, so one instance is open at a time, and opening fails when something else in the process
  already initialized the library. The instance owns the library: the package's DuckDB extension,
  loaded while it is open, uses its initialization and never shuts the library down, so the
  instance closes only after the DuckDB databases that loaded the extension have closed. An
  extension loaded while no instance is open initializes the library itself until the process
  exits.
- `DataSource` lists namespaces and tables and retrieves table metadata, each request on a session
  of its own. Connection options are passed as the text the Connector parses.
- The records (`TableRecord`, `TableMetadata`, `Column`, `Constraint`, `AnalyticsType`,
  `SourceMetadata`, `Retrieval`) copy the native result, which is freed before a call returns.
  `TableMetadataJson` renders them as JSON; a column's `analyticsType` member holds what the
  Connector derived.
- A failure is a `ConnectorException` with the contract's error kind and the Connector's message.
  The Data Source's own code and message are dropped, because they may contain user data.

The tests start PostgreSQL with Testcontainers and require a Docker-compatible daemon.

See [`../docs/architecture.md`](../docs/architecture.md) for the module boundaries.
