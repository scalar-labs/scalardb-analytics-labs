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
    // The storage DuckDB is embedded behind the storage adapter. It is a separate database from
    // the Query Engine's, but the JDBC binding must be the same release the duckdb module pins.
    implementation(libs.duckdb.jdbc) {
        version { strictly(libs.versions.duckdb.jdbc.get()) }
    }
    api(libs.jackson.databind)

    testImplementation(libs.assertj.core)
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
