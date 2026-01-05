@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.atomicfu)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlinx.benchmark)
    signing
}

// Required for JMH @State classes
allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

apply(from = "../gradle/setup.gradle.kts")

group = "com.ditchoom"
val isRunningOnGithub = System.getenv("GITHUB_REPOSITORY")?.isNotBlank() == true
val isArm64 = System.getProperty("os.arch") == "aarch64"

@Suppress("UNCHECKED_CAST")
val getNextVersion = project.extra["getNextVersion"] as (Boolean) -> Any
project.version = getNextVersion(!isRunningOnGithub).toString()

repositories {
    google()
    mavenCentral()
    maven { setUrl("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/kotlin-js-wrappers/") }
}

kotlin {
    androidTarget {
        publishLibraryVariants("release")
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
    }
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
        compilations.create("benchmark") {
            associateWith(this@jvm.compilations.getByName("main"))
        }
    }
    js {
        outputModuleName.set("buffer-kt")
        browser()
        nodejs()
        compilations.create("benchmark") {
            associateWith(this@js.compilations.getByName("main"))
        }
    }

    wasmJs {
        browser()
        nodejs()
        compilations.create("benchmark") {
            associateWith(this@wasmJs.compilations.getByName("main"))
        }
    }
    if (isRunningOnGithub) {
        macosX64()
        macosArm64 {
            if (isArm64) {
                compilations.create("benchmark") {
                    associateWith(this@macosArm64.compilations.getByName("main"))
                }
            }
        }
        linuxX64()
        linuxArm64()
        iosArm64()
        iosSimulatorArm64()
        iosX64()
        watchosArm64()
        watchosSimulatorArm64()
        watchosX64()
        tvosArm64()
        tvosSimulatorArm64()
        tvosX64()
    } else {
        val osName = System.getProperty("os.name")
        if (osName == "Mac OS X") {
            val osArch = System.getProperty("os.arch")
            if (osArch == "aarch64") {
                macosArm64 {
                    compilations.create("benchmark") {
                        associateWith(this@macosArm64.compilations.getByName("main"))
                    }
                }
            } else {
                macosX64 {
                    compilations.create("benchmark") {
                        associateWith(this@macosX64.compilations.getByName("main"))
                    }
                }
            }
        }
    }
    applyDefaultHierarchyTemplate()
    sourceSets {
        // Use the existing webMain source set from the default hierarchy
        // It's automatically shared between JS and WASM targets

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }

        val androidInstrumentedTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.rules)
                implementation(libs.androidx.test.core.ktx)
                implementation(libs.androidx.test.ext.junit)
                implementation(libs.androidx.benchmark.junit4)
            }
        }

        jsMain.dependencies {
            implementation(libs.kotlin.web)
            implementation(libs.kotlin.js)
        }

        wasmJsMain.dependencies {
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
        val jsBenchmark by getting {
            kotlin.srcDir("src/commonBenchmark/kotlin")
            dependencies {
                implementation(libs.kotlinx.benchmark.runtime)
            }
        }
        val wasmJsBenchmark by getting {
            kotlin.srcDir("src/commonBenchmark/kotlin")
            dependencies {
                implementation(libs.kotlinx.benchmark.runtime)
            }
        }
        if (isArm64) {
            val macosArm64Benchmark by getting {
                kotlin.srcDir("src/commonBenchmark/kotlin")
                dependencies {
                    implementation(libs.kotlinx.benchmark.runtime)
                }
            }
        }
    }
}

android {
    buildFeatures {
        aidl = true
    }
    compileSdk = 36
    defaultConfig {
        minSdk = 19
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.output.enable"] = "true"
    }
    namespace = "com.ditchoom.buffer"

    buildTypes {
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-benchmark.pro",
            )
            matchingFallbacks += listOf("release")
        }
    }
    testBuildType = "benchmark"

    @Suppress("UnstableApiUsage")
    testOptions {
        targetSdk = 36
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
val artifactName: String by project
val libraryDescription: String by project
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

    coordinates(publishedGroupId, artifactName, project.version.toString())

    pom {
        name.set(libraryName)
        description.set(libraryDescription)
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

// kotlinx-benchmark configuration
benchmark {
    targets {
        register("jvmBenchmark")
        register("jsBenchmark")
        register("wasmJsBenchmark")
        if (isArm64) {
            register("macosArm64Benchmark")
        }
    }
    configurations {
        register("quick") {
            warmups = 1
            iterations = 1
            iterationTime = 100
            iterationTimeUnit = "ms"
            include("largeBufferOperations")
        }
        register("subset") {
            warmups = 2
            iterations = 3
            iterationTime = 500
            iterationTimeUnit = "ms"
            include("allocate.*")
        }
        register("nativeSafe") {
            warmups = 3
            iterations = 5
            exclude(".*sliceBuffer.*")
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

tasks.register("nextVersion") {
    println(getNextVersion(false))
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
