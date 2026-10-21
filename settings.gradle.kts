/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    @Suppress("UnstableApiUsage")
    repositories {
        // The upstream Physical Catalog store implementation depends on ScalarDB and ScalarDB SQL
        // snapshots. Some ScalarDB SQL modules are published only to a private GitHub Packages
        // registry until they are openly published; the others come from the snapshot repository.
        // The snapshot repository keeps only the latest build, so these versions cannot be pinned;
        // the physical-catalog provenance records which builds were resolved.
        exclusiveContent {
            forRepositories(
                maven {
                    name = "scalarDbSql"
                    url = uri("https://maven.pkg.github.com/scalar-labs/scalardb-sql")
                    credentials {
                        username = providers.gradleProperty("gprUsername")
                            .orElse(providers.environmentVariable("GPR_USERNAME"))
                            .orNull
                        password = providers.gradleProperty("gprPassword")
                            .orElse(providers.environmentVariable("GPR_PASSWORD"))
                            .orNull
                    }
                },
                maven {
                    name = "scalarDbSnapshots"
                    url = uri("https://central.sonatype.com/repository/maven-snapshots/")
                    mavenContent {
                        snapshotsOnly()
                    }
                },
            )
            filter {
                includeModule("com.scalar-labs", "scalardb")
                includeModule("com.scalar-labs", "scalardb-schema-loader")
                includeModuleByRegex("com\\.scalar-labs", "scalardb-sql.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "scalardb-analytics-labs"

// JVM modules are Gradle subprojects. Native build units are connected through root orchestration
// tasks while retaining their authoritative build configurations.
include("application")
include("query-engine")
include("duckdb")
include("physical-catalog")

// The Physical Catalog is built from the pinned upstream source, a submodule. Included builds are
// resolved while settings are evaluated, before any task runs, so the submodule is checked out at
// its recorded commit here when it is missing or behind. Local work in the submodule is never
// discarded: a submodule with uncommitted changes at another commit fails the build instead.
val upstreamSubmodule = "upstream/scalardb-analytics"

fun git(vararg arguments: String) = providers.exec {
    commandLine(listOf("git", "-C", rootDir.absolutePath) + arguments)
    isIgnoreExitValue = true
}

fun syncUpstreamSubmodule() {
    val status = git("submodule", "status", "--", upstreamSubmodule)
    if (status.result.get().exitValue != 0) {
        // Outside a Git checkout, such as an unpacked source archive, the source must already exist.
        if (rootDir.resolve("$upstreamSubmodule/scalardb-analytics-core").isDirectory) return
        throw GradleException(
            "$upstreamSubmodule is missing and this is not a Git checkout, so it cannot be fetched.",
        )
    }
    when (status.standardOutput.asText.get().firstOrNull()) {
        ' ' -> return
        '-' -> Unit
        '+' -> {
            val changes = git("-C", upstreamSubmodule, "status", "--porcelain")
                .standardOutput.asText.get()
            if (changes.isNotBlank()) {
                throw GradleException(
                    "$upstreamSubmodule has uncommitted changes and is not at the recorded commit. " +
                        "Commit or discard them, or run `git submodule update $upstreamSubmodule`.",
                )
            }
        }
        else -> throw GradleException(
            "$upstreamSubmodule has merge conflicts; resolve them before building.",
        )
    }
    if (gradle.startParameter.isOffline) {
        throw GradleException(
            "$upstreamSubmodule must be checked out at its recorded commit, which may need the " +
                "network. Run `git submodule update --init $upstreamSubmodule` or build online.",
        )
    }
    val update = git("submodule", "update", "--init", "--", upstreamSubmodule)
    if (update.result.get().exitValue != 0) {
        throw GradleException(
            "Could not check out $upstreamSubmodule:\n" + update.standardError.asText.get(),
        )
    }
    logger.lifecycle("Checked out $upstreamSubmodule at its recorded commit.")
}

syncUpstreamSubmodule()

// The upstream catalog keeps its own Gradle build.
// Its modules are published as scalardb-analytics-<project>, so their coordinates are mapped to the
// upstream projects explicitly.
includeBuild("upstream/scalardb-analytics/scalardb-analytics-core") {
    dependencySubstitution {
        listOf(
            "lib",
            "api",
            "domain",
            "repository",
            "repository-impl-spring-data-jdbc",
            "service",
            "usecase",
            "usecase-impl",
            "schema-resolver",
            "datasource-scalardb",
            "grpc",
        ).forEach { name ->
            substitute(module("com.scalar-labs:scalardb-analytics-$name")).using(project(":$name"))
        }
        substitute(module("com.scalar-labs:scalardb-analytics-server-base")).using(project(":server:base"))
    }
}

