/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

plugins {
    base
    alias(libs.plugins.error.prone) apply false
    alias(libs.plugins.spotless) apply false
}

group = "com.scalar-labs"

// The product version comes from the nearest release tag, `v<YYYY.M.D>`. A build of the tagged
// commit has the tag's version. A later commit appends the commit count and abbreviated hash, and
// `dirty` for uncommitted changes, as semver build metadata, so it never orders before the release
// it follows. Without a release tag the version is 0.0.0, and outside a Git checkout it is
// 0.0.0+unknown.
fun productVersion(): String {
    val describe = providers.exec {
        commandLine(
            // Without optional locks, checking for uncommitted changes never takes the index
            // lock, so the build does not collide with a concurrent Git command.
            "git", "--no-optional-locks", "-C", layout.projectDirectory.asFile.absolutePath,
            "describe", "--tags", "--match", "v[0-9]*", "--dirty", "--always",
        )
        isIgnoreExitValue = true
    }
    if (describe.result.get().exitValue != 0) return "0.0.0+unknown"
    val output = describe.standardOutput.asText.get().trim()
    Regex("""v(\d+\.\d+\.\d+)(?:-(\d+)-g([0-9a-f]+))?(-dirty)?""").matchEntire(output)?.let { tag ->
        val (release, count, hash, dirty) = tag.destructured
        val metadata = listOfNotNull(
            count.takeIf { it.isNotEmpty() },
            hash.takeIf { it.isNotEmpty() }?.let { "g$it" },
            dirty.takeIf { it.isNotEmpty() }?.let { "dirty" },
        )
        return if (metadata.isEmpty()) release else release + "+" + metadata.joinToString(".")
    }
    Regex("""([0-9a-f]+)(-dirty)?""").matchEntire(output)?.let { untagged ->
        val (hash, dirty) = untagged.destructured
        return "0.0.0+g$hash" + if (dirty.isNotEmpty()) ".dirty" else ""
    }
    throw GradleException("Cannot derive the product version from `git describe` output: $output")
}

version = productVersion()

val checkJava = tasks.register("checkJava") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs checks for all Java modules included in the Gradle build."
}

subprojects {
    group = rootProject.group
    version = rootProject.version

    pluginManager.withPlugin("java") {
        apply(plugin = "net.ltgt.errorprone")

        dependencies {
            add("compileOnly", libs.jspecify)
            add("testCompileOnly", libs.jspecify)
            add("errorprone", libs.error.prone.core)
            add("errorprone", libs.nullaway)
        }

        tasks.withType<JavaCompile>().configureEach {
            options.errorprone {
                disableAllChecks.set(true)
                check("NullAway", CheckSeverity.ERROR)
                check("RequireExplicitNullMarking", CheckSeverity.ERROR)
                check("JSpecifyUnrecognizedAnnotationLocation", CheckSeverity.ERROR)
                option("NullAway:OnlyNullMarked", "true")
                option("NullAway:JSpecifyMode", "true")
            }
        }
    }

    pluginManager.withPlugin("base") {
        val subprojectAssemble = tasks.named("assemble")
        val subprojectCheck = tasks.named("check")
        rootProject.tasks.named("assemble") {
            dependsOn(subprojectAssemble)
        }
        checkJava.configure {
            dependsOn(subprojectCheck)
        }
    }
}

tasks.named("check") {
    description = "Runs checks for every build unit currently wired into the repository."
    dependsOn(checkJava)
}

tasks.named("assemble") {
    description = "Assembles every build unit currently wired into the repository."
}

// These delegate to the application's distribution tasks of the same names, because the
// application owns the distribution.
tasks.register("distTar") {
    group = "distribution"
    description = "Assembles the CLI distribution archive for the build host platform."
    dependsOn(":application:distTar")
}

tasks.register("installDist") {
    group = "distribution"
    description = "Installs the CLI distribution, unarchived, for the build host platform."
    dependsOn(":application:installDist")
}

tasks.register("distSmokeTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs the CLI distribution archive in an isolated, network-restricted environment."
    dependsOn(":application:distSmokeTest")
}

tasks.register("queryAcceptanceTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Proves the query path end to end through the CLI distribution archive."
    dependsOn(":application:queryAcceptanceTest")
}

