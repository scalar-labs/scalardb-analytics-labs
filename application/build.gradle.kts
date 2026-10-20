/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

plugins {
    application
    alias(libs.plugins.spotless)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

application {
    mainClass.set("com.scalar.db.analytics.labs.application.Main")
    applicationName = "scalardb-analytics"
}

dependencies {
    implementation(libs.jackson.databind)
    implementation(libs.picocli)

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
}

tasks.jar {
    manifest {
        attributes["Implementation-Title"] = "ScalarDB Analytics"
        attributes["Implementation-Version"] = project.version
    }
}

