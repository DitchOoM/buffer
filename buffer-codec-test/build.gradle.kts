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

kotlin.targets.configureEach {
    if (name != "metadata") {
        dependencies.add("ksp${name.replaceFirstChar { it.uppercase() }}", project(":buffer-codec-processor"))
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
