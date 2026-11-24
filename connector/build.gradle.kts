/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

import groovy.json.JsonSlurper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest

plugins {
    `java-library`
    alias(libs.plugins.spotless)
}

// The connectors distribution for the build host, one per platform, is downloaded from the
// connectors repository's GitHub release for the pinned version and checked against the digest
// recorded here. The repository is private, so the download needs a GitHub token. The
// connectorsArchive Gradle property replaces the release with a package built locally, such as an
// unreleased build of the connectors repository; it is used as given, without a digest check.
val connectorsVersion = libs.versions.scalardb.analytics.connectors.get()
val hostPlatform = when (
    "${System.getProperty("os.name")}/${System.getProperty("os.arch")}"
) {
    "Mac OS X/aarch64" -> "osx_arm64"
    "Linux/amd64" -> "linux_amd64"
    "Linux/aarch64" -> "linux_arm64"
    else -> "unsupported"
}
// The SHA-256 digest of each platform's release archive for connectorsVersion. Update these
// together with the version.
val connectorsArchiveDigests = mapOf(
    "osx_arm64" to "1b9992d6fd611ccbb0665cab4a6c34dd3f97fe79f0d970d4da04218f0c934430",
    "linux_amd64" to "d8ac2b3bf0d645ecd68a5d581009ef65651e80bd8c94dceadefb54448a1c3cc0",
    "linux_arm64" to "bdb7e5724a98b9d339fb631ae2a46740b2a39933144b5d0c15bde844b7cf833b",
)
val connectorsDistributionName = "scalardb-analytics-connectors-$connectorsVersion-$hostPlatform"

/** Downloads one asset of a GitHub release and verifies its SHA-256 digest. */
abstract class DownloadGitHubReleaseAsset : DefaultTask() {
    @get:Input
    abstract val repository: Property<String>

    @get:Input
    abstract val tag: Property<String>

    @get:Input
    abstract val assetName: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:Internal
    abstract val token: Property<String>

    @get:OutputFile
    abstract val archive: RegularFileProperty

    @TaskAction
    fun download() {
        val token = token.orNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw GradleException(
                "Downloading ${assetName.get()} from the ${repository.get()} release " +
                    "${tag.get()} needs a GitHub token that can read that repository: set the " +
                    "githubToken Gradle property or the GITHUB_TOKEN environment variable, or " +
                    "log in with the gh CLI.",
            )
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
        fun request(uri: String, accept: String, authorized: Boolean) =
            HttpRequest.newBuilder(URI.create(uri))
                .header("Accept", accept)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .apply { if (authorized) header("Authorization", "Bearer $token") }
                .build()

        val releaseUri = "https://api.github.com/repos/${repository.get()}/releases/tags/${tag.get()}"
        val release = client.send(
            request(releaseUri, "application/vnd.github+json", authorized = true),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (release.statusCode() != 200) {
            throw GradleException(
                "Reading $releaseUri failed with HTTP ${release.statusCode()}; check that the " +
                    "GitHub token can read ${repository.get()}.",
            )
        }
        @Suppress("UNCHECKED_CAST")
        val assets = (JsonSlurper().parseText(release.body()) as Map<String, Any?>)["assets"]
            as List<Map<String, Any?>>
        val assetUri = assets.firstOrNull { it["name"] == assetName.get() }?.get("url") as String?
            ?: throw GradleException("The ${tag.get()} release has no asset named ${assetName.get()}.")

        // The asset endpoint redirects to storage that rejects the GitHub token, so the redirect
        // is followed without it.
        var response = client.send(
            request(assetUri, "application/octet-stream", authorized = true),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        if (response.statusCode() in 300..399) {
            response.body().close()
            val location = response.headers().firstValue("Location").orElseThrow {
                GradleException("Downloading ${assetName.get()} redirected without a location.")
            }
            response = client.send(
                request(location, "application/octet-stream", authorized = false),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
        }
        if (response.statusCode() != 200) {
            response.body().close()
            throw GradleException("Downloading ${assetName.get()} failed with HTTP ${response.statusCode()}.")
        }

        val target = archive.get().asFile
        target.parentFile.mkdirs()
        val partial = File(target.parentFile, "${target.name}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        response.body().use { input ->
            DigestOutputStream(partial.outputStream(), digest).use { input.copyTo(it) }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != sha256.get()) {
            partial.delete()
            throw GradleException(
                "${assetName.get()} has SHA-256 $actual, but the build expects ${sha256.get()}.",
            )
        }
        Files.move(
            partial.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}

val downloadConnectorsDistribution = tasks.register<DownloadGitHubReleaseAsset>(
    "downloadConnectorsDistribution",
) {
    description = "Downloads the connectors distribution for the build host."
    repository.set("scalar-labs/scalardb-analytics-connectors")
    tag.set("v$connectorsVersion")
    assetName.set("$connectorsDistributionName.tgz")
    sha256.set(
        provider {
            connectorsArchiveDigests[hostPlatform] ?: throw GradleException(
                "The connectors distribution has no release for this platform " +
                    "(${System.getProperty("os.name")}/${System.getProperty("os.arch")}).",
            )
        },
    )
    token.set(
        providers.gradleProperty("githubToken")
            .orElse(providers.environmentVariable("GITHUB_TOKEN"))
            .orElse(providers.environmentVariable("GH_TOKEN"))
            .orElse(
                provider {
                    runCatching {
                        val process = ProcessBuilder("gh", "auth", "token")
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start()
                        val output = process.inputStream.bufferedReader().readText().trim()
                        output.takeIf { process.waitFor() == 0 && it.isNotEmpty() }
                    }.getOrNull()
                },
            ),
    )
    archive.set(layout.buildDirectory.file("connectors/$connectorsDistributionName.tgz"))
}

val localConnectorsArchive = providers.gradleProperty("connectorsArchive").orNull
    ?.let { rootProject.layout.projectDirectory.file(it) }
val connectorsArchive: Provider<RegularFile> =
    localConnectorsArchive?.let { provider { it } } ?: downloadConnectorsDistribution.flatMap { it.archive }

// The package's single top-level directory is named after the version it was built as, which a
// local build need not share with connectorsVersion, so it is dropped.
val unpackConnectorsDistribution = tasks.register<Sync>("unpackConnectorsDistribution") {
    description = "Unpacks the connectors distribution."
    from(connectorsArchive.map { tarTree(it) }) {
        eachFile {
            val segments = relativePath.segments
            if (segments.size < 2) {
                exclude()
            } else {
                relativePath = RelativePath(!isDirectory, *segments.copyOfRange(1, segments.size))
            }
        }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("connectors/unpacked"))
}
val connectorsDistribution = layout.buildDirectory.dir("connectors/unpacked")
val generatedResources = layout.buildDirectory.dir("generated-resources/main")

// The distribution is carried as resources and staged at runtime with its layout intact, so the
// extension and the library still find each other and the drivers relative to themselves. The file
// list lets the runtime stage it, since resources cannot be enumerated.
val stageConnectorsPackage = tasks.register<Sync>("stageConnectorsPackage") {
    dependsOn(unpackConnectorsDistribution)
    into(generatedResources)
    from(connectorsDistribution) {
        include("lib/**", "duckdb/**", "adbc-drivers/**")
        into("native/connectors")
    }
    from(connectorsDistribution.map { it.dir("LICENSES") }) {
        into("META-INF/licenses/connectors")
    }
    val files = files(connectorsDistribution).asFileTree.matching {
        include("lib/**", "duckdb/**", "adbc-drivers/**")
    }
    val root = connectorsDistribution.get().asFile
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
