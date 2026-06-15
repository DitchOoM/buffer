@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.konan.target.HostManager

// :buffer-1brc — a showcase BENCHMARK module that implements the One Billion Row Challenge
// on top of the core :buffer primitives (readFixedDecimalTenths / hashRange / regionEquals /
// indexOf). It is intentionally a SEPARATE module (not buffer/src/commonBenchmark) so its
// androidx.collection dependency never leaks into the published :buffer artifact.
//
// The solver lives in commonMain with expect/actual seams (MappedFile, runChunks, generateDataset);
// the same tests and benchmark run on JVM and Native. Native (linux + apple) shares one posix-mmap
// implementation in nativeMain.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kotlinx.benchmark)
}

group = "com.ditchoom"

// kotlinx-benchmark requires @State classes to be open (it subclasses them in generated harnesses).
allOpen {
    annotation("kotlinx.benchmark.State")
}

repositories {
    google()
    mavenCentral()
}

kotlin {
    jvmToolchain(21)

    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
        compilations.create("benchmark") {
            associateWith(this@jvm.compilations.getByName("main"))
        }
    }

    // Native: the same KMP solver runs via posix mmap -> wrapNativeAddress (zero-copy, no cinterop).
    // The actuals live in the shared nativeMain source set, so every native target reuses them.
    linuxX64 {
        binaries {
            executable {
                entryPoint = "com.ditchoom.onebrc.main"
            }
        }
        compilations.create("benchmark") {
            associateWith(this@linuxX64.compilations.getByName("main"))
        }
    }
    linuxArm64()
    // Apple targets reuse the identical nativeMain posix-mmap code; only buildable on macOS.
    if (HostManager.hostIsMac) {
        macosArm64()
        macosX64()
        iosArm64()
        iosSimulatorArm64()
    }

    // JS / WASM: no mmap — read the file via node fs into a PlatformBuffer, single-threaded.
    // Shared webMain holds both actuals (the `= js("…")` interop works for js and wasmJs alike).
    js {
        nodejs()
        compilations.create("benchmark") {
            associateWith(this@js.compilations.getByName("main"))
        }
    }
    wasmJs {
        nodejs()
        compilations.create("benchmark") {
            associateWith(this@wasmJs.compilations.getByName("main"))
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(project(":buffer"))
            implementation(libs.androidx.collection)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // Native parallelism uses coroutines Dispatchers.Default (multi-threaded under K/N's MM).
        nativeMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        // webMain (js + wasmJs) is provided by applyDefaultHierarchyTemplate(); its node-fs based
        // actuals live in src/webMain/kotlin.
        // Benchmark source is shared across platforms (kotlinx.benchmark annotations).
        val jvmBenchmark by getting {
            kotlin.srcDir("src/commonBenchmark/kotlin")
            dependencies {
                implementation(libs.kotlinx.benchmark.runtime)
            }
        }
        val linuxX64Benchmark by getting {
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
    }
}

// kotlinx-benchmark — the cross-platform solve() @Benchmark in src/commonBenchmark.
benchmark {
    targets {
        register("jvmBenchmark")
        register("linuxX64Benchmark")
        register("jsBenchmark")
        register("wasmJsBenchmark")
    }
    configurations {
        // 1BRC is a wall-clock-to-completion benchmark; avgt (ms/op) works on all platforms.
        register("quick") {
            warmups = 0
            iterations = 1
            iterationTime = 1
            iterationTimeUnit = "s"
            mode = "avgt"
            outputTimeUnit = "ms"
        }
        register("full") {
            warmups = 3
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            mode = "avgt"
            outputTimeUnit = "ms"
        }
    }
}

// Ad-hoc timing runner: ./gradlew :buffer-1brc:onebrcRun -Ponebrc.rows=1000000000
tasks.register<JavaExec>("onebrcRun") {
    group = "application"
    description = "Generate a dataset (if needed) and run the 1BRC solver, printing wall-clock time."
    val mainCompilation = kotlin.jvm().compilations.getByName("main")
    dependsOn(mainCompilation.compileTaskProvider)
    classpath = files(mainCompilation.output.allOutputs, mainCompilation.runtimeDependencyFiles)
    mainClass.set("com.ditchoom.onebrc.MainKt")
    jvmArgs("-Xmx8g")
    (project.findProperty("onebrc.rows") as String?)?.let { args("--rows", it) }
    (project.findProperty("onebrc.file") as String?)?.let { args("--file", it) }
    (project.findProperty("onebrc.workers") as String?)?.let { args("--workers", it) }
    (project.findProperty("onebrc.out") as String?)?.let { args("--out", it) }
}

ktlint {
    verbose.set(true)
    outputToConsole.set(true)
    filter {
        exclude("**/generated/**")
    }
}
