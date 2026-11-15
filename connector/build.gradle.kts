/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

plugins {
    `java-library`
    alias(libs.plugins.spotless)
}

// The connectors distribution, one per platform as the connectors repository builds it, is carried
// unpacked in this repository until the connectors publish releases.
val connectorsVersion = libs.versions.scalardb.analytics.connectors.get()
val hostPlatform = when (
    "${System.getProperty("os.name")}/${System.getProperty("os.arch")}"
) {
    "Mac OS X/aarch64" -> "osx_arm64"
    "Linux/amd64" -> "linux_amd64"
    "Linux/aarch64" -> "linux_arm64"
    else -> "unsupported"
}
val connectorsDistribution =
    layout.projectDirectory.dir("distribution/scalardb-analytics-connectors-$connectorsVersion-$hostPlatform")
val generatedResources = layout.buildDirectory.dir("generated-resources/main")

// The distribution is carried as resources and staged at runtime with its layout intact, so the
// extension and the library still find each other and the drivers relative to themselves. The file
// list lets the runtime stage it, since resources cannot be enumerated.
val stageConnectorsPackage = tasks.register<Sync>("stageConnectorsPackage") {
    into(generatedResources)
    from(connectorsDistribution) {
        include("lib/**", "duckdb/**", "adbc-drivers/**")
        into("native/connectors")
    }
    from(connectorsDistribution.dir("LICENSES")) {
        into("META-INF/licenses/connectors")
    }
    val files = connectorsDistribution.asFileTree.matching {
        include("lib/**", "duckdb/**", "adbc-drivers/**")
    }
    val root = connectorsDistribution.asFile
    inputs.files(files)
    doLast {
        generatedResources.get().file("native/connectors/files").asFile.writeText(
            files.files.map { it.relativeTo(root).invariantSeparatorsPath }.sorted()
                .joinToString("\n", postfix = "\n"),
        )
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

tasks.processResources {
    dependsOn(stageConnectorsPackage)
}

dependencies {
    // The JSON form of retrieved table metadata.
    api(libs.jackson.databind)

    testImplementation(libs.assertj.core)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.postgresql)
    testImplementation(libs.junit.jupiter)
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

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
