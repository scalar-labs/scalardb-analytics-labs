/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

plugins {
    `java-library`
    alias(libs.plugins.spotless)
}

// settings.gradle.kts includes the upstream build and maps these modules to its projects.
val upstreamRepositoryDirectory = rootProject.layout.projectDirectory.dir("upstream/scalardb-analytics")
val upstreamCoreDirectory = upstreamRepositoryDirectory.dir("scalardb-analytics-core")
val upstreamCoreVersion = providers.fileContents(upstreamCoreDirectory.file("VERSION")).asText.map {
    it.trim()
}

fun upstreamGit(vararg arguments: String) = providers.exec {
    commandLine(listOf("git", "-C", upstreamRepositoryDirectory.asFile.absolutePath) + arguments)
}.standardOutput.asText.map { it.trim() }

val generatedResources = layout.buildDirectory.dir("generated-resources/main")

val generateUpstreamProvenance = tasks.register("generateUpstreamProvenance") {
    description = "Records the version, source revision, and license of the upstream catalog."

    val provenanceFile = generatedResources.map {
        it.file("META-INF/scalardb-analytics-labs/physical-catalog.properties")
    }
    val licenseSource = upstreamRepositoryDirectory.file("LICENSE")
    val licenseFile = generatedResources.map { it.file("META-INF/licenses/scalardb-analytics.txt") }
    val version = upstreamCoreVersion
    val commit = upstreamGit("rev-parse", "HEAD")
    // Tracked edits or untracked files mean the built source differs from the recorded commit.
    val sourceChanges = upstreamGit(
        "status",
        "--porcelain",
        "--untracked-files=all",
        "--",
        "scalardb-analytics-core",
    ).map { if (it.isEmpty()) "none" else "modified" }
    // ScalarDB and ScalarDB SQL are resolved as snapshots that cannot be pinned, so the exact
    // builds that were resolved are part of the provenance.
    val scalarDbBuilds = configurations.named("runtimeClasspath").map { configuration ->
        configuration.incoming.resolutionResult.allComponents
            .mapNotNull { component ->
                val id = component.id as? ModuleComponentIdentifier
                if (id != null && id.group == "com.scalar-labs") component.id.displayName else null
            }
            .sorted()
            .joinToString(",")
    }
    inputs.property("version", version)
    inputs.property("commit", commit)
    inputs.property("sourceChanges", sourceChanges)
    inputs.property("scalarDbBuilds", scalarDbBuilds)
    inputs.file(licenseSource)
    outputs.file(provenanceFile)
    outputs.file(licenseFile)

    doLast {
        val properties = provenanceFile.get().asFile
        properties.parentFile.mkdirs()
        // The ScalarDB licenses are written here from the POMs of the resolved modules; review them
        // whenever the upstream dependency set changes.
        properties.writeText(
            """
            component=scalardb-analytics-core
            source=https://github.com/scalar-labs/scalardb-analytics
            version=${version.get()}
            commit=${commit.get()}
            sourceChanges=${sourceChanges.get()}
            license=Apache-2.0
            scalarDbBuilds=${scalarDbBuilds.get()}
            scalarDbLicense=Apache-2.0
            scalarDbSqlLicense=Scalar Commercial License
            """.trimIndent() + "\n",
        )
        val license = licenseFile.get().asFile
        license.parentFile.mkdirs()
        licenseSource.asFile.copyTo(license, overwrite = true)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

sourceSets {
    main {
        resources.srcDir(generatedResources)
    }
}

fun upstream(module: String) = "com.scalar-labs:scalardb-analytics-$module:${upstreamCoreVersion.get()}"

configurations.configureEach {
    // The embedded catalog serves PostgreSQL and MySQL sources and stores its own state through
    // ScalarDB JDBC storage. The upstream server bundles drivers for other databases.
    exclude(group = "com.microsoft.sqlserver")
    exclude(group = "com.oracle.database.jdbc")
    exclude(group = "com.databricks")
    exclude(group = "net.snowflake")
    exclude(group = "com.h2database")
    // Catalog logging must not reach the application's machine-readable output. Upstream code
    // also logs rejected provider payloads, which can contain credentials.
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    implementation(platform(libs.grpc.bom))

    implementation(upstream("server-base"))
    implementation(upstream("grpc"))
    implementation(upstream("api"))
    implementation(upstream("usecase"))
    implementation(upstream("repository-impl-spring-data-jdbc"))
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.jdbc)
    implementation(libs.grpc.api)
    implementation(libs.grpc.inprocess)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.services)
    implementation(libs.grpc.stub)
    implementation(libs.proto.google.common.protos)
    implementation(libs.jackson.databind)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.mariadb.java.client)
    runtimeOnly(libs.slf4j.nop)

    testImplementation(libs.assertj.core)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}

spotless {
    java {
        target("src/*/java/**/*.java")
        licenseHeader(
            """
            /*
             * Copyright Scalar, Inc.
             * SPDX-License-Identifier: Apache-2.0
             */
            """.trimIndent(),
        )
        importOrder()
        removeUnusedImports()
        googleJavaFormat(libs.versions.google.java.format.get())
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:all")
}

tasks.processResources {
    dependsOn(generateUpstreamProvenance)
}

tasks.test {
    useJUnitPlatform()
}
