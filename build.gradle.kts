/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

plugins {
    base
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

