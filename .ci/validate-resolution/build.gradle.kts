plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

val bufferVersion: String by project
val mavenRepoPath: String by project

repositories {
    maven(url = uri(file(mavenRepoPath)))
    mavenCentral()
}

kotlin {
    // JVM & JS & WASM
    jvm()
    js {
        browser()
        nodejs()
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }

    // Linux
    linuxX64()
    linuxArm64()

    // Apple – macOS
    macosArm64()
    macosX64()

    // Apple – iOS
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    // Apple – tvOS
    tvosArm64()
    tvosSimulatorArm64()
    tvosX64()

    // Apple – watchOS
    watchosArm64()
    watchosSimulatorArm64()
    watchosX64()

    sourceSets {
        commonMain.dependencies {
            implementation("com.ditchoom:buffer:$bufferVersion")
        }
    }
}

tasks.register("resolveAll") {
    description = "Resolves every resolvable configuration to verify Gradle module metadata"
    doLast {
        var resolved = 0
        configurations
            .filter { it.isCanBeResolved }
            .forEach { cfg ->
                try {
                    cfg.resolve()
                    resolved++
                } catch (e: Exception) {
                    throw GradleException("Failed to resolve configuration '${cfg.name}': ${e.message}", e)
                }
            }
        logger.lifecycle("Successfully resolved $resolved configurations")
    }
}
