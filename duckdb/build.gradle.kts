/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

plugins {
    `java-library`
    alias(libs.plugins.spotless)
}

// The DuckDB version set is owned by the version catalog. The native scripts pin the checksums of
// every artifact of that release and fail for a release they have not pinned.
val duckDbVersion = libs.versions.duckdb.asProvider().get()
val duckDbJdbcVersion = libs.versions.duckdb.jdbc.get()

// Verified downloads are cached outside the build directory, keyed by checksum, so clean builds
// reuse them without network access.
val nativeArtifactCache = gradle.gradleUserHomeDir.resolve("caches/scalardb-analytics-labs/duckdb")
val offlineBuild = gradle.startParameter.isOffline

val verifyDuckDbVersionSet = tasks.register("verifyDuckDbVersionSet") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Verifies that the DuckDB JDBC binding belongs to the pinned DuckDB release."

    inputs.property("duckDbVersion", duckDbVersion)
    inputs.property("duckDbJdbcVersion", duckDbJdbcVersion)
    doLast {
        // DuckDB JDBC versions append a binding revision to the core release they embed.
        if (!duckDbJdbcVersion.startsWith("$duckDbVersion.")) {
            throw GradleException(
                "DuckDB JDBC $duckDbJdbcVersion does not embed DuckDB $duckDbVersion. Update " +
                    "duckdb and duckdb-jdbc in gradle/libs.versions.toml together.",
            )
        }
    }
}

val nativeBuildDirectory = layout.buildDirectory.dir("native")
val nativeExtension = nativeBuildDirectory.map {
    it.file("output/scalardb_analytics.duckdb_extension")
}
val nativeExtensionProvenance = nativeBuildDirectory.map {
    it.file("output/scalardb_analytics.properties")
}

val buildNativeExtension = tasks.register<Exec>("buildNativeExtension") {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Builds the ScalarDB Analytics DuckDB extension."
    dependsOn(verifyDuckDbVersionSet)

    inputs.dir("src/main/cpp")
    inputs.file("src/native/build-extension.sh")
    inputs.file("src/native/common.sh")
    inputs.property("extensionVersion", project.version.toString())
    inputs.property("duckDbVersion", duckDbVersion)
    outputs.file(nativeExtension)
    outputs.file(nativeExtensionProvenance)

    environment("DUCKDB_ARTIFACTS_OFFLINE", offlineBuild.toString())
    commandLine(
        "bash",
        layout.projectDirectory.file("src/native/build-extension.sh").asFile.absolutePath,
        nativeBuildDirectory.get().asFile.absolutePath,
        project.version.toString(),
        duckDbVersion,
        nativeArtifactCache.absolutePath,
    )
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

sourceSets {
    main {
        resources.srcDir(layout.buildDirectory.dir("generated-resources/main"))
    }
}

dependencies {
    api(project(":query-engine"))
    // The connector read path takes data source connections as the Physical Catalog resolves them.
    api(project(":physical-catalog"))
    // The connector read path loads the DuckDB extension of the connectors distribution package.
    implementation(project(":connector"))
    implementation(libs.arrow.c.data)
    implementation(libs.arrow.memory.unsafe)
    implementation(libs.duckdb.jdbc) {
        // The packaged extensions are ABI-bound to this exact release, so conflict resolution must
        // not substitute another one.
        version { strictly(duckDbJdbcVersion) }
    }
    compileOnly(libs.checker.qual)

    testImplementation(libs.assertj.core)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
    // The connector tests prepare and inspect the source databases directly.
    testRuntimeOnly(libs.mariadb.java.client)
    testRuntimeOnly(libs.postgresql)
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

tasks.test {
    useJUnitPlatform()
    jvmArgs(
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED",
    )
}

val stageNativeExtension = tasks.register<Sync>("stageNativeExtension") {
    dependsOn(buildNativeExtension)
    into(layout.buildDirectory.dir("generated-resources/main"))
    from(nativeExtension) {
        into("native")
    }
    from(nativeExtensionProvenance) {
        into("META-INF/scalardb-analytics-labs/duckdb-extensions")
    }
}

tasks.processResources {
    dependsOn(stageNativeExtension)
}
