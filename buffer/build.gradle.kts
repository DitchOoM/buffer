@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.atomicfu)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kover)
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
    // Ensure consistent JDK version across all developer machines and CI
    jvmToolchain(21)

    // Suppress Beta warning for expect/actual classes (BufferMismatchHelper)
    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }

    androidTarget {
        publishLibraryVariants("release")
        // Use JVM 1.8 for Android to maintain minSdk 19 compatibility
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
        // Include commonTest in Android instrumented tests
        instrumentedTestVariant {
            sourceSetTree.set(KotlinSourceSetTree.test)
        }
    }
    jvm {
        // Keep Java 8 bytecode for maximum compatibility; Java 11+ optimizations in META-INF/versions/11/
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
        compilations.create("benchmark") {
            associateWith(this@jvm.compilations.getByName("main"))
        }
        // Java 11 compilation for ByteBuffer.mismatch() optimization
        compilations.create("java11") {
            compilerOptions.configure {
                jvmTarget.set(JvmTarget.JVM_11)
            }
            defaultSourceSet {
                kotlin.srcDir("src/jvm11Main/kotlin")
            }
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
        // Create nonJvmMain source set shared by nativeMain and wasmJsMain
        // This contains ByteArrayBuffer which is used for managed memory on these platforms
        val nonJvmMain by creating {
            dependsOn(commonMain.get())
        }
        val nonJvmTest by creating {
            dependsOn(commonTest.get())
        }
        nativeMain {
            dependsOn(nonJvmMain)
        }
        nativeTest {
            dependsOn(nonJvmTest)
        }
        wasmJsMain {
            dependsOn(nonJvmMain)
        }
        wasmJsTest {
            dependsOn(nonJvmTest)
        }

        // Shared source set for JVM and Android (java.nio.ByteBuffer)
        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain {
            dependsOn(jvmCommonMain)
        }
        androidMain {
            dependsOn(jvmCommonMain)
        }

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

    // Use Java 1.8 for Android to maintain minSdk 19 compatibility
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

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

// Configure multi-release JAR for Java 11+ optimizations
tasks.named<Jar>("jvmJar") {
    manifest {
        attributes("Multi-Release" to "true")
    }
    // Include Java 11 classes in META-INF/versions/11/
    into("META-INF/versions/11") {
        from(
            kotlin
                .jvm()
                .compilations["java11"]
                .output
                .allOutputs,
        )
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

// Allow reflective access to internal JDK fields:
// - java.nio: DirectBufferAddressHelper accesses Buffer.address field
// - jdk.internal.misc: UnsafeMemory accesses sun.misc.Unsafe
tasks.withType<Test>().configureEach {
    jvmArgs(
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
    )
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
