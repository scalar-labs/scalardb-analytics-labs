/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

import com.github.jk1.license.filter.DependencyFilter
import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.github.jk1.license.render.InventoryMarkdownReportRenderer
import com.github.jk1.license.render.ReportRenderer
import java.util.Properties

plugins {
    application
    alias(libs.plugins.dependency.license.report)
    alias(libs.plugins.spotless)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

val requiredJavaVersion = 25

application {
    mainClass.set("com.scalar.db.analytics.labs.application.Main")
    applicationName = "scalardb-analytics"
    applicationDefaultJvmArgs = listOf(
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED",
        // Arrow's memory module uses sun.misc.Unsafe; without this the JVM warns on stderr, which
        // is part of the CLI's machine-readable contract.
        "--sun-misc-unsafe-memory-access=allow",
    )
}

val distributionTest = sourceSets.create("distributionTest")

dependencies {
    implementation(project(":query-engine"))
    implementation(project(":duckdb"))
    implementation(project(":physical-catalog"))
    implementation(libs.jackson.databind)
    implementation(libs.picocli)

    testImplementation(libs.assertj.core)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)

    // The smoke test exercises only the unpacked distribution, never the application classes.
    "distributionTestImplementation"(libs.assertj.core)
    "distributionTestImplementation"(libs.jackson.databind)
    "distributionTestImplementation"(libs.junit.jupiter)
    "distributionTestImplementation"(libs.testcontainers.junit.jupiter)
    "distributionTestCompileOnly"(libs.jspecify)
    "distributionTestRuntimeOnly"(libs.junit.platform.launcher)
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

tasks.jar {
    manifest {
        attributes["Implementation-Title"] = "ScalarDB Analytics"
        attributes["Implementation-Version"] = project.version
    }
}

// Distribution

// The packaged DuckDB extensions cannot be cross-compiled, so an archive supports exactly the
// build host's platform. The names match the platform recorded in the extension provenance.
val distributionPlatform = providers.systemProperty("os.name")
    .zip(providers.systemProperty("os.arch")) { os, arch ->
        when {
            os == "Mac OS X" && arch == "aarch64" -> "osx_arm64"
            os == "Linux" && arch == "aarch64" -> "linux_arm64"
            os == "Linux" && (arch == "amd64" || arch == "x86_64") -> "linux_amd64"
            else -> throw GradleException(
                "Unsupported build platform $os/$arch: the distribution supports only osx_arm64, " +
                    "linux_amd64, and linux_arm64; build it on one of those hosts.",
            )
        }
    }

fun repositoryGit(vararg arguments: String) = providers.exec {
    commandLine(listOf("git", "-C", rootProject.projectDir.absolutePath) + arguments)
}.standardOutput.asText.map { it.trim() }

val upstreamCoreVersion = providers.fileContents(
    rootProject.layout.projectDirectory.file("upstream/scalardb-analytics/scalardb-analytics-core/VERSION"),
).asText.map { it.trim() }

// Components whose license the Maven metadata does not declare. The product modules carry the
// product license; the upstream catalog modules are built from source whose license is recorded in
// the Physical Catalog provenance. Every other entry must be checked against the component's own
// license before it is added.
val licenseOverrides = layout.buildDirectory.file("third-party/license-overrides.txt")
val generateLicenseOverrides = tasks.register("generateLicenseOverrides") {
    val productVersion = project.version.toString()
    val upstreamVersion = upstreamCoreVersion
    val output = licenseOverrides
    inputs.property("productVersion", productVersion)
    inputs.property("upstreamVersion", upstreamVersion)
    outputs.file(output)

    doLast {
        val apache = "Apache License, Version 2.0|https://www.apache.org/licenses/LICENSE-2.0"
        val product = listOf("duckdb", "physical-catalog", "query-engine").map {
            "com.scalar-labs:$it:$productVersion||$apache"
        }
        val upstream = listOf(
            "api", "base", "datasource-scalardb", "domain", "grpc", "lib", "repository",
            "repository-impl-spring-data-jdbc", "schema-resolver", "service", "usecase", "usecase-impl",
        ).map {
            "com.scalar-labs:$it:${upstreamVersion.get()}|https://github.com/scalar-labs/scalardb-analytics|$apache"
        }
        val others = listOf(
            "com.scalar-labs:scalardb-sql-direct-mode:${upstreamVersion.get()}" +
                "|https://scalar-labs.com|Scalar Commercial License|https://www.scalar-labs.com",
            "org.antlr:ST4:4.3.1|https://www.stringtemplate.org" +
                "|The 3-Clause BSD License|https://github.com/antlr/stringtemplate4/blob/master/LICENSE.txt",
        )
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText((product + upstream + others).joinToString("\n", postfix = "\n"))
        }
    }
}

val thirdPartyReportDirectory = layout.buildDirectory.dir("third-party/report")
licenseReport {
    outputDir = thirdPartyReportDirectory.get().asFile.path
    configurations = arrayOf("runtimeClasspath")
    // The ScalarDB modules share this project's group but are separately licensed components.
    excludeOwnGroup = false
    excludeBoms = true
    renderers = arrayOf<ReportRenderer>(
        InventoryMarkdownReportRenderer(
            "THIRD-PARTY.md",
            "ScalarDB Analytics third-party components",
            licenseOverrides.get().asFile,
        ),
    )
    filters = arrayOf<DependencyFilter>(LicenseBundleNormalizer())
}

tasks.named("generateLicenseReport") {
    dependsOn(generateLicenseOverrides)
    inputs.file(licenseOverrides)
    doLast {
        val report = thirdPartyReportDirectory.get().file("THIRD-PARTY.md").asFile.readText()
        if (report.contains("\n## Unknown\n")) {
            throw GradleException(
                "Some runtime components have no known license; see the Unknown section of " +
                    "${thirdPartyReportDirectory.get().file("THIRD-PARTY.md").asFile}. Check each " +
                    "component's license and record it in generateLicenseOverrides.",
            )
        }
    }
}

// The provenance and license material the component jars carry, surfaced at the top level of the
// distribution so it can be inspected without unpacking jars.
val componentJars = files(
    configurations.runtimeClasspath.map { configuration ->
        configuration.incoming.artifactView {
            componentFilter { it is ProjectComponentIdentifier }
        }.files
    },
)
val componentJarContents = files(componentJars.elements.map { jars -> jars.map { zipTree(it.asFile) } })

val stageComponentMaterial = tasks.register<Sync>("stageComponentMaterial") {
    into(layout.buildDirectory.dir("distribution/component-material"))
    includeEmptyDirs = false
    from(componentJarContents) {
        include("META-INF/scalardb-analytics-labs/**")
        eachFile {
            relativePath = RelativePath(true, "provenance", *relativePath.segments.drop(2).toTypedArray())
        }
    }
    from(componentJarContents) {
        include("META-INF/licenses/**")
        eachFile {
            relativePath = RelativePath(true, "licenses", *relativePath.segments.drop(2).toTypedArray())
        }
    }
}

val generateDistributionProvenance = tasks.register("generateDistributionProvenance") {
    val output = layout.buildDirectory.file("distribution/provenance/distribution.properties")
    val properties = mapOf(
        "product" to providers.provider { "scalardb-analytics" },
        "version" to providers.provider { project.version.toString() },
        "platform" to distributionPlatform,
        "javaRuntime" to providers.provider { "$requiredJavaVersion or later" },
        "repository" to providers.provider { rootProject.name },
        "commit" to repositoryGit("rev-parse", "HEAD"),
        // Tracked edits or untracked files mean the built source differs from the recorded commit.
        "sourceChanges" to repositoryGit("status", "--porcelain", "--untracked-files=all")
            .map { if (it.isEmpty()) "none" else "modified" },
        "license" to providers.provider { "Apache-2.0" },
        "agentSkills" to providers.provider { "query-agent-v0" },
    )
    properties.forEach { (name, value) -> inputs.property(name, value) }
    outputs.file(output)

    doLast {
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText(properties.entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value.get()}" })
        }
    }
}

distributions {
    main {
        distributionClassifier.set(distributionPlatform)
        contents {
            from(rootProject.layout.projectDirectory.file("LICENSE"))
            from("src/main/resources/META-INF/scalardb-analytics/skills") {
                into("skills")
            }
            from(generateDistributionProvenance) {
                into("provenance")
            }
            from(stageComponentMaterial)
            from(tasks.named("generateLicenseReport")) {
                into("licenses/third-party")
            }
        }
    }
}

// The packaged extensions load only on the platform they were built for, so the archive must not
// claim another one.
val verifyDistributionPlatform = tasks.register("verifyDistributionPlatform") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Verifies that the packaged DuckDB extensions match the distribution platform."

    val extensionProvenance = componentJarContents.asFileTree.matching {
        include("META-INF/scalardb-analytics-labs/duckdb-extensions/*.properties")
    }
    val platform = distributionPlatform
    inputs.files(extensionProvenance)
    inputs.property("platform", platform)

    doLast {
        val files = extensionProvenance.files
        if (files.isEmpty()) {
            throw GradleException("The runtime classpath contains no DuckDB extension provenance.")
        }
        files.forEach { file ->
            val extension = Properties().apply { file.inputStream().use { load(it) } }
            if (extension.getProperty("platform") != platform.get()) {
                throw GradleException(
                    "${file.name} records platform ${extension.getProperty("platform")}, but the " +
                        "distribution is for ${platform.get()}.",
                )
            }
        }
    }
}

tasks.startScripts {
    doLast {
        // The archive supports only Unix-like platforms.
        windowsScript.delete()
        // Check the runtime before passing it options that older Java versions reject, so a
        // missing or old Java fails with an actionable message instead of a JVM error.
        val script = unixScript
        val javaHint = "Set JAVA_HOME to a Java $requiredJavaVersion or later installation, or put its " +
            "java command on your PATH."
        val versionCheck =
            """
            # ScalarDB Analytics requires Java $requiredJavaVersion or later. Legacy versions report "1.N".
            java_version=$( "${'$'}JAVACMD" -version 2>&1 | sed -n 's/.* version "\(1\.\)\{0,1\}\([0-9][0-9]*\).*/\2/p' | head -n 1 )
            if [ -z "${'$'}java_version" ] ; then
                die "ERROR: Could not determine the Java version of ${'$'}JAVACMD.

            ScalarDB Analytics requires Java $requiredJavaVersion or later. $javaHint"
            elif [ "${'$'}java_version" -lt $requiredJavaVersion ] ; then
                die "ERROR: ScalarDB Analytics requires Java $requiredJavaVersion or later, but ${'$'}JAVACMD is Java ${'$'}java_version.

            $javaHint"
            fi


            """.trimIndent()
        val anchor = "# Increase the maximum file descriptors if we can."
        val missingJavaHint = "Please set the JAVA_HOME variable in your environment to match the\n" +
            "location of your Java installation."
        val original = script.readText()
        check(original.contains(anchor) && original.split(missingJavaHint).size == 3) {
            "The Gradle start script template changed; update the Java check in application/build.gradle.kts."
        }
        script.writeText(
            original
                .replace(missingJavaHint, "ScalarDB Analytics requires Java $requiredJavaVersion or later. $javaHint")
                .replace(anchor, versionCheck + "\n" + anchor),
        )
    }
}

tasks.distZip {
    enabled = false
}

tasks.distTar {
    dependsOn(verifyDistributionPlatform)
    compression = Compression.GZIP
    archiveExtension.set("tar.gz")
}

tasks.installDist {
    dependsOn(verifyDistributionPlatform)
    doLast {
        logger.lifecycle("Installed the distribution; run ${destinationDir.resolve("bin/scalardb-analytics")}")
    }
}

// The smoke test needs the full distribution, so check only compiles it.
tasks.check {
    dependsOn(tasks.named(distributionTest.classesTaskName))
}

val javaToolchains = extensions.getByType<JavaToolchainService>()

tasks.register<Test>("distSmokeTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Unpacks the distribution archive and runs it in an isolated, network-restricted environment."

    testClassesDirs = distributionTest.output.classesDirs
    classpath = distributionTest.runtimeClasspath
    useJUnitPlatform()

    val archive = tasks.distTar.flatMap { it.archiveFile }
    inputs.file(archive)
    val supportedJava = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(requiredJavaVersion))
    }.map { it.metadata.installationPath.asFile.absolutePath }
    // A runtime older than the requirement, to prove the launcher rejects it.
    val olderJava = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    }.map { it.metadata.installationPath.asFile.absolutePath }
    inputs.property("supportedJava", supportedJava)
    inputs.property("olderJava", olderJava)
    inputs.property("platform", distributionPlatform)
    inputs.property("version", project.version.toString())

    doFirst {
        systemProperty("distribution.archive", archive.get().asFile.absolutePath)
        systemProperty("distribution.platform", distributionPlatform.get())
        systemProperty("distribution.version", project.version.toString())
        systemProperty("distribution.javaHome", supportedJava.get())
        systemProperty("distribution.olderJavaHome", olderJava.get())
    }
}
