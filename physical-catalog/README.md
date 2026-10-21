# Physical Catalog module

This Java module embeds the upstream ScalarDB Analytics catalog in the application process and
owns the boundary around it. `PhysicalCatalog` starts the catalog against an application-configured
ScalarDB store and exposes two surfaces. Application code uses its typed operations to register and
resolve PostgreSQL and MySQL data sources. Native consumers use `callSerialized`, a read-only path
in serialized gRPC form. Only `resolveConnection`, which native consumers cannot reach, returns
source credentials, and it returns them as `Secret` values.

The upstream source is the `upstream/scalardb-analytics` submodule, included in the root build as a
composite build. This module's build records the upstream provenance and license under `META-INF/`
in its resources.

Tests use Testcontainers and require a Docker-compatible daemon.

See [`../docs/physical-catalog.md`](../docs/physical-catalog.md) for the boundary's mental model.
