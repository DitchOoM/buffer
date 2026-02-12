@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.HostManager

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

// simdutf - SIMD-accelerated Unicode transcoding for Linux
// CI Requirements: cmake, build-essential, gcc-aarch64-linux-gnu (for ARM64 cross-compilation)
apply(from = "gradle/simdutf.gradle.kts")

group = "com.ditchoom"
val isRunningOnGithub = System.getenv("GITHUB_REPOSITORY")?.isNotBlank() == true
val isArm64 = System.getProperty("os.arch") == "aarch64"

@Suppress("UNCHECKED_CAST")
val getNextVersion = project.extra["getNextVersion"] as (Boolean) -> Any
project.version = getNextVersion(!isRunningOnGithub).toString()

// Get simdutf build tasks and libs directory from simdutf.gradle.kts
@Suppress("UNCHECKED_CAST")
val buildSimdutfX64 = extra["buildSimdutfX64"] as TaskProvider<Task>

@Suppress("UNCHECKED_CAST")
val buildSimdutfArm64 = extra["buildSimdutfArm64"] as TaskProvider<Task>
val simdutfLibsDir = extra["simdutfLibsDir"] as File

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
        // Use JVM 1.8 for Android to maintain maximum compatibility
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
        // Java 21 compilation for FFM (Foreign Function & Memory) API
        compilations.create("java21") {
            compilerOptions.configure {
                jvmTarget.set(JvmTarget.JVM_21)
            }
            defaultSourceSet {
                kotlin.srcDir("src/jvm21Main/kotlin")
                dependencies {
                    // Access main compilation output for common types (Charset, ReadBuffer, etc.)
                    implementation(
                        this@jvm
                            .compilations
                            .getByName("main")
                            .output
                            .classesDirs,
                    )
                }
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
        // CI: register all targets for the current host OS
        if (HostManager.hostIsMac) {
            macosX64()
            macosArm64 {
                if (isArm64) {
                    compilations.create("benchmark") {
                        associateWith(this@macosArm64.compilations.getByName("main"))
                    }
                }
            }
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
            if (isArm64) {
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
        } else if (HostManager.hostIsLinux) {
            linuxX64()
        }
    }
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        compilations.getByName("main") {
            cinterops {
                create("simd") {
                    defFile(project.file("src/nativeInterop/cinterop/simd.def"))
                }
            }
        }
    }

    // Configure simdutf for Linux targets (SIMD-accelerated Unicode transcoding)
    if (HostManager.hostIsLinux) {
        targets.matching { it.name == "linuxX64" }.configureEach {
            val target = this as KotlinNativeTarget
            val simdutfLibDir = simdutfLibsDir.resolve("linux-x64/lib")
            target.compilations["main"].cinterops {
                create("simdutf") {
                    defFile(project.file("src/nativeInterop/cinterop/simdutf.def"))
                    extraOpts("-libraryPath", simdutfLibDir.absolutePath)
                    // Only depend on simdutf build when on Linux (cmake/g++ not available on macOS)
                    if (HostManager.hostIsLinux) {
                        tasks.named(interopProcessingTaskName) {
                            dependsOn(buildSimdutfX64)
                        }
                    }
                }
            }
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
            val simdutfLibDir = simdutfLibsDir.resolve("linux-arm64/lib")
            target.compilations["main"].cinterops {
                create("simdutf") {
                    defFile(project.file("src/nativeInterop/cinterop/simdutf.def"))
                    extraOpts("-libraryPath", simdutfLibDir.absolutePath)
                    // Only depend on simdutf build when on Linux (cmake/g++ not available on macOS)
                    if (HostManager.hostIsLinux) {
                        tasks.named(interopProcessingTaskName) {
                            dependsOn(buildSimdutfArm64)
                        }
                    }
                }
            }
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
        val jvmCommonTest by creating {
            dependsOn(commonTest.get())
        }
        jvmMain {
            dependsOn(jvmCommonMain)
        }
        jvmTest {
            dependsOn(jvmCommonTest)
        }
        androidMain {
            dependsOn(jvmCommonMain)
        }
        androidUnitTest {
            dependsOn(jvmCommonTest)
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

    // Use Java 1.8 for Android to maintain maximum compatibility
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
        register("scoped") {
            warmups = 2
            iterations = 3
            iterationTime = 500
            iterationTimeUnit = "ms"
            include(".*ScopedBuffer.*")
        }
        register("bulk") {
            warmups = 3
            iterations = 5
            iterationTime = 1000
            iterationTimeUnit = "ms"
            include("BulkOperations")
        }
        // Fast configuration for WASM - runs only key benchmarks to avoid long run times
        register("wasmFast") {
            warmups = 2
            iterations = 2
            include("allocateScopedBuffer")
            include("bulkWriteIntsScoped")
            include("readWriteIntScoped")
        }
    }
}

// Configure multi-release JAR for Java 11+ and Java 21+ optimizations
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
    // Include Java 21 classes in META-INF/versions/21/
    into("META-INF/versions/21") {
        from(
            kotlin
                .jvm()
                .compilations["java21"]
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

// Fix: Kotlin metadata compilation for intermediate source sets (appleMain, nativeMain, etc.)
// doesn't include per-target cinterop klibs, causing "Unresolved reference 'cinterop'" errors.
// Add a representative cinterop klib to the metadata compilation classpath. All targets produce
// the same API from the same .def file, so any target's klib works for type resolution.
// Also fix: kotlinx-benchmark's NativeSourceGeneratorWorker doesn't include cinterop klibs
// in its inputDependencies, causing KLIB resolution to fail. Add them explicitly.
// Also fix: the benchmark executable link task doesn't include cinterop klibs,
// causing IrLinkageError at runtime for cinterop functions.
afterEvaluate {
    // Split publishing metadata fix: When publishing from split CI jobs (Linux + Apple),
    // each host only registers its own targets. The root module metadata (.module file)
    // generated on Linux would be missing Apple variants, breaking KMP resolution for
    // Apple consumers. Fix: on Linux, inject Apple variant references into the generated
    // .module file. On Apple, skip the root metadata publication (Linux publishes it).
    if (isRunningOnGithub) {
        if (HostManager.hostIsLinux) {
            tasks.named("generateMetadataFileForKotlinMultiplatformPublication") {
                doLast {
                    val moduleFile = outputs.files.singleFile
                    injectAppleVariantsIntoModuleMetadata(moduleFile, project.version.toString(), "buffer")
                }
            }
        }
        if (HostManager.hostIsMac) {
            // Skip root metadata publication — published from Linux with all variant references
            tasks
                .matching {
                    it.name.startsWith("publishKotlinMultiplatformPublication")
                }.configureEach { enabled = false }
        }
    }

    // Determine which target's cinterop klib to use for metadata compilation
    val metadataCinteropTarget =
        when {
            isArm64 -> "macosArm64"
            HostManager.hostIsMac -> "macosX64"
            else -> "linuxX64"
        }
    val metadataCinteropKlib =
        project.file(
            "${project.layout.buildDirectory.get()}/classes/kotlin/$metadataCinteropTarget/main/cinterop/buffer-cinterop-simd",
        )
    val metadataCinteropTaskName = "cinteropSimd${metadataCinteropTarget.replaceFirstChar { it.uppercase() }}"

    // Add cinterop klib to intermediate source set metadata compilation tasks
    tasks
        .matching {
            it.name.startsWith("compile") && it.name.endsWith("KotlinMetadata")
        }.configureEach {
            dependsOn(metadataCinteropTaskName)
            if (this is org.jetbrains.kotlin.gradle.tasks.AbstractKotlinCompileTool<*>) {
                libraries.from(metadataCinteropKlib)
            }
        }
    // Disable cross-platform metadata compilation tasks that can't resolve on the current host.
    // CPointer arithmetic in linuxMain can't compile to metadata on macOS and vice versa.
    val isLinux = HostManager.hostIsLinux
    val isMacOs = HostManager.hostIsMac
    tasks
        .matching {
            it.name.endsWith("KotlinMetadata") &&
                (
                    (!isLinux && it.name.contains("Linux", ignoreCase = true)) ||
                        (
                            !isMacOs &&
                                (
                                    it.name.contains("Apple", ignoreCase = true) ||
                                        it.name.contains("Ios", ignoreCase = true) ||
                                        it.name.contains("Macos", ignoreCase = true) ||
                                        it.name.contains("Tvos", ignoreCase = true) ||
                                        it.name.contains("Watchos", ignoreCase = true)
                                )
                        )
                )
        }.configureEach {
            enabled = false
        }
    tasks.withType(kotlinx.benchmark.gradle.NativeSourceGeneratorTask::class.java).configureEach {
        val gradleTarget = name.substringBefore("Benchmark")
        val cinteropKlib =
            project.file(
                "${project.layout.buildDirectory.get()}/classes/kotlin/$gradleTarget/main/cinterop/buffer-cinterop-simd",
            )
        inputDependencies = inputDependencies + project.files(cinteropKlib)
    }
    tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink::class.java).configureEach {
        if (name.contains("BenchmarkBenchmark")) {
            val targetName =
                when {
                    name.contains("MacosArm64", ignoreCase = true) -> "macosArm64"
                    name.contains("MacosX64", ignoreCase = true) -> "macosX64"
                    name.contains("LinuxX64", ignoreCase = true) -> "linuxX64"
                    name.contains("LinuxArm64", ignoreCase = true) -> "linuxArm64"
                    else -> return@configureEach
                }
            val cinteropKlib =
                project.file(
                    "${project.layout.buildDirectory.get()}/classes/kotlin/$targetName/main/cinterop/buffer-cinterop-simd",
                )
            libraries.from(cinteropKlib)
        }
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

/**
 * Split publishing metadata fix: inject Apple variant references into the Gradle Module Metadata
 * (.module) file. When publishing from split CI jobs, the Linux host generates the root metadata
 * but only has Linux/JVM/JS/WASM/Android targets registered. Apple variants must be injected so
 * that KMP consumers on Apple platforms can resolve the dependency.
 */
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
