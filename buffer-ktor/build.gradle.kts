@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka)
    alias(libs.plugins.binary.compatibility.validator)
    signing
}

// Validate the JVM ABI only (host-independent, fully contains the common public surface).
apiValidation {
    klib {
        enabled = false
    }
}

group = "com.ditchoom"
val isRunningOnGithub = System.getenv("GITHUB_REPOSITORY")?.isNotBlank() == true

apply(from = "../gradle/setup.gradle.kts")

@Suppress("UNCHECKED_CAST")
val getNextVersion = project.extra["getNextVersion"] as (Boolean) -> Any
if (!project.hasProperty("version") || project.version == "unspecified") {
    project.version = getNextVersion(!isRunningOnGithub).toString()
}

repositories {
    google()
    mavenCentral()
}

kotlin {
    jvmToolchain(21)

    androidTarget {
        publishLibraryVariants("release")
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
        instrumentedTestVariant {
            sourceSetTree.set(KotlinSourceSetTree.test)
        }
    }
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
    }
    js {
        outputModuleName.set("buffer-ktor-kt")
        browser()
        nodejs()
    }
    wasmJs {
        browser()
        nodejs()
    }
    if (isRunningOnGithub) {
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
            linuxArm64()
        }
    }
    // The :buffer klib references simdutf symbols; the final Kotlin/Native test binary must relink.
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
            api(project(":buffer-kotlinx-io"))
            api(project(":buffer-codec"))
            api(libs.ktor.io)
            api(libs.ktor.http)
            api(libs.ktor.serialization)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            // Generated CommandCodec fixture (KSP output in :buffer-codec-test commonMain).
            implementation(project(":buffer-codec-test"))
        }
        jvmTest.dependencies {
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.test.host)
            implementation(libs.ktor.server.content.negotiation)
            implementation(libs.ktor.client.core)
        }

        val androidInstrumentedTest by getting {
            dependencies {
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.rules)
                implementation(libs.androidx.test.core.ktx)
                implementation(libs.androidx.test.ext.junit)
            }
        }
    }
}

android {
    compileSdk = 36
    defaultConfig {
        minSdk = 21
    }
    namespace = "$group.buffer.ktor"

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

    coordinates(publishedGroupId, "buffer-ktor", project.version.toString())

    pom {
        name.set("Buffer Ktor")
        description.set("Kotlin Multiplatform Ktor 3 channel bridges, ContentConverter, and WebSocket helpers for the buffer library")
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
        reportUndocumented.set(false)
    }
}
