@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlinx.benchmark)
    signing
}

// Required for JMH @State classes
allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

group = "com.ditchoom"
val isRunningOnGithub = System.getenv("GITHUB_REPOSITORY")?.isNotBlank() == true
val isArm64 = System.getProperty("os.arch") == "aarch64"

apply(from = "../gradle/setup.gradle.kts")

@Suppress("UNCHECKED_CAST")
val getNextVersion = project.extra["getNextVersion"] as (Boolean) -> Any
project.version = getNextVersion(!isRunningOnGithub).toString()

repositories {
    google()
    mavenCentral()
    maven { setUrl("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/kotlin-js-wrappers/") }
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
        compilations.create("benchmark") {
            associateWith(this@jvm.compilations.getByName("main"))
        }
    }
    js {
        outputModuleName.set("buffer-compression-kt")
        browser()
        nodejs()
    }
    if (isRunningOnGithub) {
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
        linuxX64()
        linuxArm64()
    } else {
        if (HostManager.hostIsMac) {
            val osArch = System.getProperty("os.arch")
            if (osArch == "aarch64") {
                macosArm64()
            } else {
                macosX64()
            }
        } else if (HostManager.hostIsLinux) {
            linuxX64 {
                compilations.create("benchmark") {
                    associateWith(this@linuxX64.compilations.getByName("main"))
                }
            }
        }
    }
    // Link against simdutf (transitive dependency via :buffer module)
    if (HostManager.hostIsLinux || isRunningOnGithub) {
        targets.matching { it.name == "linuxX64" }.configureEach {
            val target = this as KotlinNativeTarget
            val simdutfLibDir = project(":buffer").projectDir.resolve("libs/simdutf/linux-x64/lib")
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
            val simdutfLibDir = project(":buffer").projectDir.resolve("libs/simdutf/linux-arm64/lib")
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
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        // Shared source set for JVM and Android (java.util.zip APIs)
        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain {
            dependsOn(jvmCommonMain)
        }
        androidMain {
            dependsOn(jvmCommonMain)
        }

        jsMain.dependencies {
            implementation(libs.kotlin.web)
            implementation(libs.kotlin.js)
        }

        // Benchmark source sets - all share the same source directory
        val jvmBenchmark by getting {
            kotlin.srcDir("src/commonBenchmark/kotlin")
            dependencies {
                implementation(libs.kotlinx.benchmark.runtime)
            }
        }
        if (HostManager.hostIsLinux) {
            val linuxX64Benchmark by getting {
                kotlin.srcDir("src/commonBenchmark/kotlin")
                dependencies {
                    implementation(libs.kotlinx.benchmark.runtime)
                }
            }
        }
    }
}

android {
    compileSdk = 36
    defaultConfig {
        minSdk = 19
    }
    namespace = "$group.buffer.compression"

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

    coordinates(publishedGroupId, "buffer-compression", project.version.toString())

    pom {
        name.set("Buffer Compression")
        description.set("Kotlin Multiplatform compression extensions for the buffer library")
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

// kotlinx-benchmark configuration
benchmark {
    targets {
        register("jvmBenchmark")
        if (HostManager.hostIsLinux) {
            register("linuxX64Benchmark")
        }
    }
    configurations {
        named("main") {
            warmups = 3
            iterations = 5
            iterationTime = 1000
            iterationTimeUnit = "ms"
        }
        register("quick") {
            warmups = 1
            iterations = 2
            iterationTime = 500
            iterationTimeUnit = "ms"
        }
    }
}

tasks.matching { it.name == "testBenchmarkUnitTest" }.configureEach {
    enabled = false
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
