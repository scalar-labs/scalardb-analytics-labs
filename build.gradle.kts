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
version = providers.fileContents(layout.projectDirectory.file("VERSION")).asText.get().trim()

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

