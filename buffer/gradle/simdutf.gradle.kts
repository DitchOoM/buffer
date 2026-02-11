/**
 * simdutf Build Configuration for Linux
 *
 * This file contains the build logic for downloading, compiling, and configuring
 * simdutf (SIMD-accelerated Unicode validation and transcoding) for Linux targets.
 *
 * simdutf is used only on Linux because:
 * - Apple platforms have optimized CoreFoundation APIs
 * - JVM has optimized CharsetDecoder
 * - JS/Wasm have native TextDecoder
 *
 * CI Requirements:
 * - cmake (sudo apt install cmake)
 * - build-essential (sudo apt install build-essential)
 * - For ARM64 cross-compilation: gcc-aarch64-linux-gnu g++-aarch64-linux-gnu
 *
 * Exports (via extra properties):
 * - buildSimdutfX64: TaskProvider<Task> - builds simdutf for x64
 * - buildSimdutfArm64: TaskProvider<Task> - builds simdutf for arm64
 * - simdutfLibsDir: File - directory containing built libraries
 */

import java.net.URI
import java.security.MessageDigest

// Equivalent to HostManager.hostIsLinux (HostManager is not on the classpath in applied scripts)
val hostIsLinux = org.gradle.internal.os.OperatingSystem.current().isLinux

// =============================================================================
// Version Configuration (from libs.versions.toml)
// =============================================================================
val simdutfVersion: String by extra {
    providers.gradleProperty("simdutf").getOrElse(
        project.extensions.getByType<VersionCatalogsExtension>().named("libs")
            .findVersion("simdutf").get().requiredVersion
    )
}

val simdutfSha256: String by extra {
    providers.gradleProperty("simdutfSha256").getOrElse(
        project.extensions.getByType<VersionCatalogsExtension>().named("libs")
            .findVersion("simdutfSha256").get().requiredVersion
    )
}

val simdutfBuildDir = layout.buildDirectory.dir("simdutf")
val simdutfLibsDir = projectDir.resolve("libs/simdutf")

// =============================================================================
// Download and Verify Source
// =============================================================================
fun downloadSimdutfSource(buildDir: File, version: String, sha256: String): File {
    val tarball = File(buildDir, "simdutf-$version.tar.gz")
    val sourceDir = File(buildDir, "simdutf-$version")

    if (sourceDir.exists()) return sourceDir

    buildDir.mkdirs()

    // Download if not present
    if (!tarball.exists()) {
        logger.lifecycle("Downloading simdutf $version...")
        val url = URI("https://github.com/simdutf/simdutf/archive/refs/tags/v$version.tar.gz").toURL()
        url.openStream().use { input ->
            tarball.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    // Verify SHA256
    val digest = MessageDigest.getInstance("SHA-256")
    tarball.inputStream().use { input ->
        val buffer = ByteArray(8192)
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            digest.update(buffer, 0, read)
        }
    }
    val actualSha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    if (actualSha256 != sha256) {
        tarball.delete()
        throw GradleException("simdutf SHA256 mismatch: expected $sha256, got $actualSha256")
    }

    // Extract
    logger.lifecycle("Extracting simdutf source...")
    ProcessBuilder("tar", "xzf", tarball.name)
        .directory(buildDir)
        .inheritIO()
        .start()
        .waitFor()

    return sourceDir
}

// =============================================================================
// Build Task Factory
// =============================================================================
fun createBuildSimdutfTask(arch: String): TaskProvider<Task> {
    val taskName = "buildSimdutf${arch.replaceFirstChar { it.uppercase() }}"
    val outputDir = simdutfLibsDir.resolve("linux-$arch")
    val markerFile = outputDir.resolve("lib/.built-$simdutfVersion")

    return tasks.register(taskName) {
        group = "build"
        description = "Build simdutf static library for Linux $arch"

        inputs.property("simdutfVersion", simdutfVersion)
        inputs.property("simdutfSha256", simdutfSha256)
        inputs.file(projectDir.resolve("src/nativeInterop/cinterop/simdutf_wrapper.cpp"))
        outputs.file(markerFile)

        onlyIf { !markerFile.exists() && hostIsLinux }

        doLast {
            val buildDir = simdutfBuildDir.get().asFile
            val sourceDir = downloadSimdutfSource(buildDir, simdutfVersion, simdutfSha256)
            val cmakeBuildDir = File(sourceDir, "build-$arch")

            // Determine compilers for cross-compilation
            val isArmCross = arch == "arm64" && System.getProperty("os.arch") != "aarch64"
            val cxx = if (isArmCross) "aarch64-linux-gnu-g++" else "g++"
            val ar = if (isArmCross) "aarch64-linux-gnu-ar" else "ar"

            // Check for cross-compiler if needed
            if (isArmCross) {
                val result = ProcessBuilder("which", cxx).start().waitFor()
                if (result != 0) {
                    throw GradleException(
                        """
                        Cross-compiler not found for ARM64. Install with:
                          sudo apt install gcc-aarch64-linux-gnu g++-aarch64-linux-gnu
                        """.trimIndent()
                    )
                }
            }

            // Clean previous build if exists
            if (cmakeBuildDir.exists()) {
                cmakeBuildDir.deleteRecursively()
            }

            // Configure CMake
            // -O2: enables SIMD auto-vectorization (required for simdutf performance)
            // -ffunction-sections/-fdata-sections + --gc-sections: strip unused code
            logger.lifecycle("Configuring simdutf $simdutfVersion for $arch...")
            val cmakeArgs = mutableListOf(
                "cmake",
                "-B", cmakeBuildDir.absolutePath,
                "-S", sourceDir.absolutePath,
                "-DSIMDUTF_TESTS=OFF",
                "-DSIMDUTF_BENCHMARKS=OFF",
                "-DSIMDUTF_TOOLS=OFF",
                "-DSIMDUTF_ICONV=OFF",
                "-DBUILD_SHARED_LIBS=OFF",
                "-DCMAKE_BUILD_TYPE=Release",
                "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
                "-DCMAKE_CXX_FLAGS=-O2 -ffunction-sections -fdata-sections",
                "-DCMAKE_EXE_LINKER_FLAGS=-Wl,--gc-sections"
            )

            if (isArmCross) {
                cmakeArgs.addAll(listOf(
                    "-DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc",
                    "-DCMAKE_CXX_COMPILER=aarch64-linux-gnu-g++",
                    "-DCMAKE_SYSTEM_NAME=Linux",
                    "-DCMAKE_SYSTEM_PROCESSOR=aarch64"
                ))
            }

            val configureResult = ProcessBuilder(cmakeArgs)
                .directory(sourceDir)
                .inheritIO()
                .start()
                .waitFor()

            if (configureResult != 0) {
                throw GradleException("simdutf cmake configure failed")
            }

            // Build simdutf
            logger.lifecycle("Building simdutf...")
            val cpuCount = Runtime.getRuntime().availableProcessors()
            val buildResult = ProcessBuilder(
                "cmake", "--build", cmakeBuildDir.absolutePath,
                "--config", "Release",
                "-j$cpuCount"
            )
                .directory(sourceDir)
                .inheritIO()
                .start()
                .waitFor()

            if (buildResult != 0) {
                throw GradleException("simdutf build failed")
            }

            // Copy artifacts
            outputDir.resolve("lib").mkdirs()

            // Copy simdutf static library
            val libFile = cmakeBuildDir.resolve("src/libsimdutf.a")
            if (!libFile.exists()) {
                throw GradleException("simdutf library not found at ${libFile.absolutePath}")
            }
            libFile.copyTo(outputDir.resolve("lib/libsimdutf.a"), overwrite = true)

            // Copy headers (entire include directory with subdirectories)
            val srcIncludeDir = sourceDir.resolve("include")
            val destIncludeDir = outputDir.resolve("include")
            if (destIncludeDir.exists()) {
                destIncludeDir.deleteRecursively()
            }
            srcIncludeDir.copyRecursively(destIncludeDir, overwrite = true)

            // Compile our C++ wrapper that provides C-linkage functions
            val wrapperSrc = projectDir.resolve("src/nativeInterop/cinterop/simdutf_wrapper.cpp")
            val wrapperObj = outputDir.resolve("lib/simdutf_wrapper.o")
            val wrapperLib = outputDir.resolve("lib/libsimdutf_wrapper.a")

            logger.lifecycle("Compiling simdutf C wrapper...")
            val compileResult = ProcessBuilder(
                cxx, "-c", "-O2", "-fPIC",
                "-I${outputDir.resolve("include").absolutePath}",
                "-o", wrapperObj.absolutePath,
                wrapperSrc.absolutePath
            ).inheritIO().start().waitFor()

            if (compileResult != 0) {
                throw GradleException("Failed to compile simdutf wrapper")
            }

            // Create static library from wrapper
            ProcessBuilder(ar, "rcs", wrapperLib.absolutePath, wrapperObj.absolutePath)
                .inheritIO().start().waitFor()

            // Write marker file
            markerFile.parentFile.mkdirs()
            markerFile.writeText("simdutf $simdutfVersion built on ${System.currentTimeMillis()}")

            logger.lifecycle("simdutf $simdutfVersion built successfully for $arch")
        }
    }
}

// =============================================================================
// Register Build Tasks and Export Properties
// =============================================================================
val buildSimdutfX64: TaskProvider<Task> by extra { createBuildSimdutfTask("x64") }
val buildSimdutfArm64: TaskProvider<Task> by extra { createBuildSimdutfTask("arm64") }

// Export the libs directory for use in cinterop configuration
extra["simdutfLibsDir"] = simdutfLibsDir
