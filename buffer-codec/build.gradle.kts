@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree
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
// Honor -Pversion so local publishes can pin a version (e.g. -Pversion=4.3.0-SNAPSHOT)
// without being clobbered by Maven Central's getNextVersion auto-increment.
if (!project.hasProperty("version") || project.version == "unspecified") {
    project.version = getNextVersion(!isRunningOnGithub).toString()
}

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
        // Include commonTest in Android instrumented tests so the full
        // suite runs on a real emulator (ART), not just the host JVM.
        instrumentedTestVariant {
            sourceSetTree.set(KotlinSourceSetTree.test)
        }
    }
    jvm {
        // Keep Java 8 bytecode for maximum compatibility
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
    }
    js {
        outputModuleName.set("buffer-codec-kt")
        browser()
        nodejs()
    }
    wasmJs {
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
            // Register linuxArm64 on local Linux dev too (was previously CI-only). K/N
            // ships an aarch64 cross-compiler; required for downstream consumers
            // (socket, mqtt) that target linuxArm64 to resolve buffer's umbrella metadata.
            linuxArm64()
        }
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        // Shared source set for JVM and Android — both consume the same
        // OwnedBytesHandle actual since `PlatformBuffer` is itself the
        // platform boundary, and the handle is a thin internal wrapper.
        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain {
            dependsOn(jvmCommonMain)
        }
        androidMain {
            dependsOn(jvmCommonMain)
        }
        commonMain.dependencies {
            api(project(":buffer"))
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        // AGP 9 no longer auto-provides the default instrumentation runner;
        // declare androidx.test.runner (AndroidJUnitRunner) explicitly so it is
        // packaged into the androidTest APK (otherwise connectedDebugAndroidTest
        // crashes with ClassNotFoundException: androidx.test.runner.AndroidJUnitRunner).
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
    namespace = "$group.buffer.codec"

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

    coordinates(publishedGroupId, "buffer-codec", project.version.toString())

    pom {
        name.set("Buffer Codec")
        description.set("Kotlin Multiplatform protocol codec system for the buffer library")
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

// Apple metadata reconciliation is handled by the two-host release merge in
// .github/workflows/validate-artifacts.yaml (same as buffer, buffer-compression,
// buffer-flow and buffer-crypto): each host publishes its own umbrella metadata
// module, and CI unions the .module variants and the umbrella JAR's PSM
// (kotlin-project-structure-metadata.json). Publishing the umbrella on both hosts
// (rather than disabling it on macOS and hand-injecting Apple variants into the
// Linux .module) is what lets the merged PSM carry the Apple source sets that
// downstream `compileAppleMainKotlinMetadata` needs.

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
