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
// Honor -Pversion so local publishes can pin a version without being clobbered by
// Maven Central's getNextVersion auto-increment.
if (!project.hasProperty("version") || project.version == "unspecified") {
    project.version = getNextVersion(!isRunningOnGithub).toString()
}

repositories {
    google()
    mavenCentral()
    maven { setUrl("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/kotlin-js-wrappers/") }
}

kotlin {
    jvmToolchain(21)

    // SHA-256 / HMAC are modeled as expect/actual classes (multiplatform impl is a Beta feature).
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidTarget {
        publishLibraryVariants("release")
        // Use JVM 1.8 for Android to maintain maximum compatibility
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
        // Run the full common suite on a real emulator (ART), not just the host JVM.
        instrumentedTestVariant {
            sourceSetTree.set(KotlinSourceSetTree.test)
        }
    }
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
    }
    js {
        outputModuleName.set("buffer-crypto-kt")
        browser()
        nodejs()
    }
    wasmJs {
        browser()
        nodejs()
    }
    // Native crypto is backed by each platform's system library: JCA (JVM/Android),
    // CommonCrypto/Security (Apple). WebCrypto's digest is async-only, so js/wasm fall
    // back to a synchronous pure-Kotlin SHA-256/HMAC. Linux (BoringSSL) is deferred —
    // no native target is registered for it yet.
    if (isRunningOnGithub) {
        if (HostManager.hostIsMac) {
            macosX64()
            macosArm64()
            iosArm64()
            iosSimulatorArm64()
            iosX64()
            // watchosArm64 (arm64_32) is intentionally omitted: it is the only 32-bit
            // Apple target, and appleMain calls CommonCrypto/Security size_t functions.
            // With no cinterop of its own this module doesn't engage size_t commonization,
            // so the shared Apple metadata compile rejects the 32-bit vs 64-bit width
            // mismatch. watchOS is still covered by the simulator + x64 targets below.
            watchosSimulatorArm64()
            watchosX64()
            tvosArm64()
            tvosSimulatorArm64()
            tvosX64()
        }
    } else if (HostManager.hostIsMac) {
        if (System.getProperty("os.arch") == "aarch64") {
            macosArm64()
        } else {
            macosX64()
        }
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            api(project(":buffer"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        val androidInstrumentedTest by getting {
            dependencies {
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.rules)
                implementation(libs.androidx.test.core.ktx)
                implementation(libs.androidx.test.ext.junit)
            }
        }

        // Shared source set for JVM and Android: both use the JCA (java.security /
        // javax.crypto) for MessageDigest, Mac, and SecureRandom.
        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain {
            dependsOn(jvmCommonMain)
        }
        androidMain {
            dependsOn(jvmCommonMain)
        }

        // Shared source set for JS and wasmJs: pure-Kotlin SHA-256/HMAC (WebCrypto's
        // SubtleCrypto.digest is async-only and cannot satisfy a synchronous contract).
        val jsAndWasmJsMain by creating {
            dependsOn(commonMain.get())
        }
        val jsAndWasmJsTest by creating {
            dependsOn(commonTest.get())
        }
        jsMain {
            dependsOn(jsAndWasmJsMain)
        }
        wasmJsMain {
            dependsOn(jsAndWasmJsMain)
        }
        jsTest {
            dependsOn(jsAndWasmJsTest)
        }
        wasmJsTest {
            dependsOn(jsAndWasmJsTest)
        }
    }
}

android {
    compileSdk = 36
    defaultConfig {
        // API 28+: the secure/deterministic key-material buffers route through
        // sun.misc.Unsafe.allocateMemory, which is absent from the Android runtime
        // before API 26 (NoSuchMethodError on API 21). 28 is a conservative modern
        // floor for a crypto module. AGP skips this module's instrumented tests on
        // older emulators rather than failing to install.
        minSdk = 28
    }
    namespace = "$group.buffer.crypto"

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

    coordinates(publishedGroupId, "buffer-crypto", project.version.toString())

    pom {
        name.set("Buffer Crypto")
        description.set("Kotlin Multiplatform cryptographic primitives (SHA-256, HMAC, HKDF) for the buffer library")
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
