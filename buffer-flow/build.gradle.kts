@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka)
    signing
}

group = "com.ditchoom"
val isRunningOnGithub = System.getenv("GITHUB_REPOSITORY")?.isNotBlank() == true

apply(from = "../gradle/setup.gradle.kts")

@Suppress("UNCHECKED_CAST")
val getNextVersion = project.extra["getNextVersion"] as (Boolean) -> Any
project.version = getNextVersion(!isRunningOnGithub).toString()

repositories {
    google()
    mavenCentral()
}

kotlin {
    // Ensure consistent JDK version across all developer machines and CI
    jvmToolchain(21)

    androidTarget {
        publishLibraryVariants("release")
        // Use JVM 1.8 for Android to maintain maximum compatibility
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
    }
    jvm {
        // Keep Java 8 bytecode for maximum compatibility
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
    }
    js {
        outputModuleName.set("buffer-flow-kt")
        browser()
        nodejs()
    }
    if (isRunningOnGithub) {
        // CI: register targets based on host OS (must match :buffer module)
        if (HostManager.hostIsMac) {
            macosX64()
            macosArm64()
            iosArm64()
            iosSimulatorArm64()
            iosX64()
            watchosArm64()
            watchosSimulatorArm64()
            watchosX64()
            tvosArm64()
            tvosSimulatorArm64()
            tvosX64()
        }
        if (HostManager.hostIsLinux) {
            linuxX64()
            linuxArm64()
        }
    } else {
        if (HostManager.hostIsMac) {
            val osArch = System.getProperty("os.arch")
            if (osArch == "aarch64") {
                macosArm64()
            } else {
                macosX64()
            }
        } else if (HostManager.hostIsLinux) {
            linuxX64()
        }
    }
    // Link against simdutf (transitive dependency via :buffer module)
    if (HostManager.hostIsLinux || isRunningOnGithub) {
        targets.matching { it.name == "linuxX64" }.configureEach {
            val target = this as KotlinNativeTarget
            val simdutfLibDir =
                project(":buffer")
                    .layout.buildDirectory
                    .dir("simdutf/libs/linux-x64/lib")
                    .get()
                    .asFile
            target.binaries.all {
                linkerOpts(
                    "-L${simdutfLibDir.absolutePath}",
                    "-lsimdutf_wrapper",
                    "-lsimdutf",
                    "-lstdc++",
                )
            }
        }
        targets.matching { it.name == "linuxArm64" }.configureEach {
            val target = this as KotlinNativeTarget
            val simdutfLibDir =
                project(":buffer")
                    .layout.buildDirectory
                    .dir("simdutf/libs/linux-arm64/lib")
                    .get()
                    .asFile
            target.binaries.all {
                linkerOpts(
                    "-L${simdutfLibDir.absolutePath}",
                    "-lsimdutf_wrapper",
                    "-lsimdutf",
                    "-lstdc++",
                )
            }
        }
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            api(project(":buffer"))
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    compileSdk = 36
    defaultConfig {
        minSdk = 19
    }
    namespace = "$group.buffer.flow"

    // Use Java 1.8 for Android to maintain maximum compatibility
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

val javadocJar: TaskProvider<Jar> by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

val publishedGroupId: String by project
val libraryName: String by project
val siteUrl: String by project
val gitUrl: String by project
val licenseName: String by project
val licenseUrl: String by project
val developerOrg: String by project
val developerName: String by project
val developerEmail: String by project
val developerId: String by project

project.group = publishedGroupId

val signingInMemoryKey = project.findProperty("signingInMemoryKey")
val signingInMemoryKeyPassword = project.findProperty("signingInMemoryKeyPassword")
val isMainBranchGithub = System.getenv("GITHUB_REF") == "refs/heads/main"
val shouldSignAndPublish = isMainBranchGithub && signingInMemoryKey is String && signingInMemoryKeyPassword is String

if (shouldSignAndPublish) {
    signing {
        useInMemoryPgpKeys(
            signingInMemoryKey as String,
            signingInMemoryKeyPassword as String,
        )
        sign(publishing.publications)
    }
}

mavenPublishing {
    if (shouldSignAndPublish) {
        publishToMavenCentral()
        signAllPublications()
    }

    coordinates(publishedGroupId, "buffer-flow", project.version.toString())

    pom {
        name.set("Buffer Flow")
        description.set("Kotlin Multiplatform Flow extensions for the buffer library")
        url.set(siteUrl)

        licenses {
            license {
                name.set(licenseName)
                url.set(licenseUrl)
            }
        }
        developers {
            developer {
                id.set(developerId)
                name.set(developerName)
                email.set(developerEmail)
            }
        }
        organization {
            name.set(developerOrg)
        }
        scm {
            connection.set(gitUrl)
            developerConnection.set(gitUrl)
            url.set(siteUrl)
        }
    }
}

// Split publishing metadata fix (see buffer/build.gradle.kts for details)
afterEvaluate {
    if (isRunningOnGithub) {
        if (HostManager.hostIsLinux) {
            tasks.named("generateMetadataFileForKotlinMultiplatformPublication") {
                doLast {
                    val moduleFile = outputs.files.singleFile
                    injectAppleVariantsIntoModuleMetadata(moduleFile, project.version.toString(), "buffer-flow")
                }
            }
        }
        if (HostManager.hostIsMac) {
            tasks
                .matching {
                    it.name.startsWith("publishKotlinMultiplatformPublication")
                }.configureEach { enabled = false }
        }
    }
}

ktlint {
    verbose.set(true)
    outputToConsole.set(true)
    android.set(true)
    filter {
        exclude("**/generated/**")
    }
}

dokka {
    dokkaSourceSets.configureEach {
        externalDocumentationLinks.register("kotlin-stdlib") {
            url("https://kotlinlang.org/api/latest/jvm/stdlib/")
            packageListUrl("https://kotlinlang.org/api/latest/jvm/stdlib/package-list")
        }
        externalDocumentationLinks.register("kotlinx-coroutines") {
            url("https://kotlinlang.org/api/kotlinx.coroutines/")
            packageListUrl("https://kotlinlang.org/api/kotlinx.coroutines/package-list")
        }
        reportUndocumented.set(false)
    }
}

/** See buffer/build.gradle.kts for full documentation. */
@Suppress("UNCHECKED_CAST")
fun injectAppleVariantsIntoModuleMetadata(
    moduleFile: File,
    version: String,
    artifactId: String,
) {
    val appleTargets =
        listOf(
            "iosArm64" to "ios_arm64",
            "iosSimulatorArm64" to "ios_simulator_arm64",
            "iosX64" to "ios_x64",
            "macosArm64" to "macos_arm64",
            "macosX64" to "macos_x64",
            "tvosArm64" to "tvos_arm64",
            "tvosSimulatorArm64" to "tvos_simulator_arm64",
            "tvosX64" to "tvos_x64",
            "watchosArm64" to "watchos_arm64",
            "watchosSimulatorArm64" to "watchos_simulator_arm64",
            "watchosX64" to "watchos_x64",
        )

    val json = groovy.json.JsonSlurper().parseText(moduleFile.readText()) as MutableMap<String, Any>
    val variants = json["variants"] as MutableList<Any>

    appleTargets.forEach { (gradleName, konanName) ->
        val moduleName = "$artifactId-${gradleName.lowercase()}"
        val availableAt =
            mapOf(
                "url" to "../../$moduleName/$version/$moduleName-$version.module",
                "group" to "com.ditchoom",
                "module" to moduleName,
                "version" to version,
            )
        variants.add(
            mapOf(
                "name" to "${gradleName}ApiElements-published",
                "attributes" to
                    mapOf(
                        "org.gradle.category" to "library",
                        "org.gradle.jvm.environment" to "non-jvm",
                        "org.gradle.usage" to "kotlin-api",
                        "org.jetbrains.kotlin.native.target" to konanName,
                        "org.jetbrains.kotlin.platform.type" to "native",
                    ),
                "available-at" to availableAt,
            ),
        )
        variants.add(
            mapOf(
                "name" to "${gradleName}SourcesElements-published",
                "attributes" to
                    mapOf(
                        "org.gradle.category" to "documentation",
                        "org.gradle.dependency.bundling" to "external",
                        "org.gradle.docstype" to "sources",
                        "org.gradle.jvm.environment" to "non-jvm",
                        "org.gradle.usage" to "kotlin-runtime",
                        "org.jetbrains.kotlin.native.target" to konanName,
                        "org.jetbrains.kotlin.platform.type" to "native",
                    ),
                "available-at" to availableAt,
            ),
        )
    }

    moduleFile.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(json)))
}
