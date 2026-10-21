# How does the build obtain the Physical Catalog?

## Current answer

The public `scalardb-analytics` repository is a Git submodule at `upstream/scalardb-analytics`,
pinned to a commit. The root build includes its Gradle build for `scalardb-analytics-core` as a
composite build. `settings.gradle.kts` maps the published `com.scalar-labs:scalardb-analytics-*`
coordinates to the upstream projects, so labs depends on them as ordinary modules while Gradle
builds them from the pinned source.

## Rationale

The catalog modules are not published to a public Maven repository, so the build must start from
source. A submodule pins that source to one reviewed commit. A composite build keeps the upstream
build configuration authoritative and lets Gradle track upstream changes like any other project
dependency. Neither an extra build invocation nor an intermediate repository is needed.

## Consequences

- A checkout needs `git submodule update --init`.
- The upstream build runs under the root build's Gradle version, so upgrading Gradle in either
  repository requires the other to support the new version.
- The upstream modules compile with their own Java toolchains. Gradle provisions those toolchains.
- Upstream tests are not part of the root `check`; the upstream repository owns them.
- The upstream modules depend on ScalarDB and ScalarDB SQL snapshots. ScalarDB SQL is resolved
  from a private GitHub Packages registry, which requires `gprUsername` and `gprPassword` Gradle
  properties or `GPR_USERNAME` and `GPR_PASSWORD` environment variables.
- The snapshot repository keeps only the latest build of each snapshot. A clean build is therefore
  reproducible from the pinned catalog source, but not for those snapshot dependencies. The build
  records the snapshot builds it resolved in the catalog provenance. Full reproducibility requires
  upstream releases of these dependencies.
- ScalarDB SQL is distributed under the Scalar Commercial License, and the provenance records it.
  Redistributing it in the product distribution needs a licensing decision.
