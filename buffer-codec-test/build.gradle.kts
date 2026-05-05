@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
}

group = "com.ditchoom"

repositories {
    google()
    mavenCentral()
}

kotlin {
    jvmToolchain(21)

    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
    }
    js {
        browser()
        nodejs()
    }
    wasmJs {
        browser()
        nodejs()
    }
    if (HostManager.hostIsMac) {
        val osArch = System.getProperty("os.arch")
        if (osArch == "aarch64") macosArm64() else macosX64()
    } else if (HostManager.hostIsLinux) {
        linuxX64()
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            implementation(project(":buffer"))
            implementation(project(":buffer-codec"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// Run KSP once on the common metadata compilation so generated codecs land
// in commonMain and every target compilation sees the same symbols. This is
// the KSP2 common-multiplatform shape; per-target KSP runs would scatter
// generated sources across `jvmMain`/`jsMain`/etc. and break references
// from commonMain (e.g., `WavFmtChunkCodec` → `WavFmtBodyCodec`) and from
// commonTest.
dependencies {
    add("kspCommonMainMetadata", project(":buffer-codec-processor"))
    add("kspCommonMainMetadata", project(":buffer-codec"))
}

kotlin.sourceSets.named("commonMain") {
    kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    if (name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}

ktlint {
    verbose.set(true)
    outputToConsole.set(true)
    filter {
        exclude("**/generated/**")
        exclude("**/build/**")
        exclude { it.file.path.contains("/generated/") || it.file.path.contains("/build/") }
    }
}

// `commonMain` includes the KSP-generated `build/generated/ksp/metadata/commonMain/kotlin`
// srcDir (see above). Ktlint filters those files out of the actual lint pass, but Gradle
// still sees the task as reading from a directory written by `kspCommonMainKotlinMetadata`
// and reports an implicit-dependency validation error. Declare the dependency explicitly.
tasks
    .matching {
        it.name == "runKtlintCheckOverCommonMainSourceSet" ||
            it.name == "runKtlintFormatOverCommonMainSourceSet"
    }.configureEach {
        dependsOn("kspCommonMainKotlinMetadata")
    }

// JVM tests use JFR allocation tracking (see SimpleHeaderAllocationTest) to
// enforce Locked Decision row 16: zero `[B` allocations attributable to the
// codec on JVM. Disabling TLAB makes every allocation flow through
// `jdk.ObjectAllocationOutsideTLAB`, which is reported per-allocation rather
// than per-TLAB-rotation — without this, byte[] allocations that fit inside
// an existing TLAB never trigger an event and the assertion is non-deterministic.
tasks.named<Test>("jvmTest") {
    jvmArgs("-XX:-UseTLAB")
}
