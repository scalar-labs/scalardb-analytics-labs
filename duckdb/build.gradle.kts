/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

plugins {
    `java-library`
    alias(libs.plugins.spotless)
}

val nativeBuildDirectory = layout.buildDirectory.dir("native")
val nativeExtension = nativeBuildDirectory.map {
    it.file("output/scalardb_analytics.duckdb_extension")
}

val buildNativeExtension = tasks.register<Exec>("buildNativeExtension") {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Builds the ScalarDB Analytics DuckDB extension."

    inputs.file("src/main/cpp/scalardb_analytics_extension.cpp")
    inputs.file("src/native/build-extension.sh")
    inputs.property("extensionVersion", project.version.toString())
    outputs.file(nativeExtension)

    commandLine(
        "bash",
        layout.projectDirectory.file("src/native/build-extension.sh").asFile.absolutePath,
        nativeBuildDirectory.get().asFile.absolutePath,
        project.version.toString(),
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
    implementation(libs.arrow.c.data)
    implementation(libs.arrow.memory.unsafe)
    implementation(libs.duckdb.jdbc)
    compileOnly(libs.checker.qual)

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
    jvmArgs(
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED",
    )
}

val stageNativeExtension = tasks.register<Copy>("stageNativeExtension") {
    dependsOn(buildNativeExtension)
    from(nativeExtension)
    into(layout.buildDirectory.dir("generated-resources/main/native"))
}

tasks.processResources {
    dependsOn(stageNativeExtension)
}
