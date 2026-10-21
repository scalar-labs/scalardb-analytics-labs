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

// The Physical Catalog is built from the pinned upstream source, which keeps its own Gradle build.
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

