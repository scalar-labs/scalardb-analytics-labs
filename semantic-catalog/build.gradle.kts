/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

plugins {
    `java-library`
    alias(libs.plugins.spotless)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))

    // The Semantic Catalog is stored in the Physical Catalog's store database, which is assumed to
    // be PostgreSQL for now, and accessed through Spring Data JDBC behind the storage adapter.
    implementation(libs.spring.data.jdbc)
    implementation(libs.hikaricp)
    runtimeOnly(libs.postgresql)
    api(libs.jackson.databind)

    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation(libs.assertj.core)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    // The Generator tests run construction-run SQL on an in-memory DuckDB.
    testImplementation(libs.duckdb.jdbc) {
        version { strictly(libs.versions.duckdb.jdbc.get()) }
    }
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.nop)
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
