/**
 * simdutf Build Configuration for Linux
 *
 * Downloads the pre-built amalgamated single-file source (simdutf.cpp + simdutf.h)
 * from GitHub releases, compiles with clang++ from Kotlin/Native's bundled LLVM,
 * and produces static libraries.
 *
 * All artifacts are built into build/simdutf/ (never committed to git).
 * Gradle's up-to-date checks (keyed on version + SHA256 in libs.versions.toml)
 * skip rebuilds when unchanged. CI caches build/simdutf/libs/ across runs.
 *
 * Uses Kotlin/Native's bundled LLVM toolchain (clang++) so no system compiler
 * packages are needed. ARM64 cross-compilation uses clang's --target flag with
 * K/N's bundled aarch64 GCC sysroot.
 *
 * simdutf is used only on Linux because:
 * - Apple platforms have optimized CoreFoundation APIs
 * - JVM has optimized CharsetDecoder
 * - JS/Wasm have native TextDecoder
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

// All simdutf artifacts go into build/simdutf/ (gitignored, cacheable)
val simdutfBuildDir = layout.buildDirectory.dir("simdutf")
val simdutfLibsDir = layout.buildDirectory.dir("simdutf/libs").get().asFile

// =============================================================================
// Kotlin/Native LLVM Toolchain Discovery
// =============================================================================
val konanDir = File(System.getProperty("user.home"), ".konan/dependencies")

fun findKonanDir(prefix: String): File? =
    konanDir.listFiles()?.firstOrNull { it.isDirectory && it.name.startsWith(prefix) }

fun findClangPP(): String {
    val llvmDir = findKonanDir("llvm-")
    val clang = llvmDir?.resolve("bin/clang++")
    if (clang != null && clang.exists()) return clang.absolutePath
    // Fallback to system g++ if K/N's LLVM is not available
    return "g++"
}

fun findLlvmAr(): String {
    val llvmDir = findKonanDir("llvm-")
    val ar = llvmDir?.resolve("bin/llvm-ar")
    if (ar != null && ar.exists()) return ar.absolutePath
    return "ar"
}

fun findAarch64GccToolchain(): File? =
    findKonanDir("aarch64-unknown-linux-gnu-gcc-")

// =============================================================================
// Download Amalgamated Source from GitHub Release
// =============================================================================
fun downloadAmalgamatedSource(downloadDir: File, version: String, sha256: String): File {
    val sourceDir = File(downloadDir, "amalgamated-$version")
    val cppFile = File(sourceDir, "simdutf.cpp")
    val hFile = File(sourceDir, "simdutf.h")

    if (cppFile.exists() && hFile.exists()) return sourceDir

    sourceDir.mkdirs()

    val baseUrl = "https://github.com/simdutf/simdutf/releases/download/v$version"

    // Download simdutf.cpp
    if (!cppFile.exists()) {
        logger.lifecycle("Downloading simdutf $version amalgamated source...")
        URI("$baseUrl/simdutf.cpp").toURL().openStream().use { input ->
            cppFile.outputStream().use { output -> input.copyTo(output) }
        }
    }

    // Download simdutf.h
    if (!hFile.exists()) {
        logger.lifecycle("Downloading simdutf $version amalgamated header...")
        URI("$baseUrl/simdutf.h").toURL().openStream().use { input ->
            hFile.outputStream().use { output -> input.copyTo(output) }
        }
    }

    // Verify SHA256 of the source file
    val digest = MessageDigest.getInstance("SHA-256")
    cppFile.inputStream().use { input ->
        val buffer = ByteArray(8192)
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            digest.update(buffer, 0, read)
        }
    }
    val actualSha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    if (actualSha256 != sha256) {
        cppFile.delete()
        hFile.delete()
        throw GradleException("simdutf.cpp SHA256 mismatch: expected $sha256, got $actualSha256")
    }

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
        outputs.dir(outputDir)

        onlyIf { !markerFile.exists() && hostIsLinux }

        doLast {
            val downloadDir = simdutfBuildDir.get().asFile
            val sourceDir = downloadAmalgamatedSource(downloadDir, simdutfVersion, simdutfSha256)
            val isArmCross = arch == "arm64" && System.getProperty("os.arch") != "aarch64"

            // Use K/N's bundled clang++ and llvm-ar
            val cxx = findClangPP()
            val ar = findLlvmAr()
            logger.lifecycle("Using compiler: $cxx")

            // Build cross-compilation flags for ARM64
            val crossFlags = if (isArmCross) {
                val gccToolchain = findAarch64GccToolchain()
                    ?: throw GradleException(
                        "Kotlin/Native aarch64 GCC toolchain not found in ${konanDir.absolutePath}. " +
                            "Run a K/N linuxArm64 build first to trigger dependency download."
                    )
                val sysroot = gccToolchain.resolve("aarch64-unknown-linux-gnu/sysroot")
                listOf(
                    "--target=aarch64-unknown-linux-gnu",
                    "--sysroot=${sysroot.absolutePath}",
                    "--gcc-toolchain=${gccToolchain.absolutePath}",
                )
            } else {
                emptyList()
            }

            // Create output dirs
            outputDir.resolve("lib").mkdirs()
            outputDir.resolve("include").mkdirs()

            // Compile simdutf from amalgamated single-file source
            val simdutfObj = outputDir.resolve("lib/simdutf.o")
            val simdutfLib = outputDir.resolve("lib/libsimdutf.a")

            logger.lifecycle("Compiling simdutf $simdutfVersion (amalgamated) for $arch...")
            val compileArgs = mutableListOf(
                cxx, "-c", "-O2", "-fPIC",
                "-ffunction-sections", "-fdata-sections",
                "-I${sourceDir.absolutePath}",
                "-o", simdutfObj.absolutePath,
                sourceDir.resolve("simdutf.cpp").absolutePath,
            )
            compileArgs.addAll(compileArgs.size - 1, crossFlags)

            val compileSimdutfResult = ProcessBuilder(compileArgs)
                .inheritIO().start().waitFor()

            if (compileSimdutfResult != 0) {
                throw GradleException("simdutf compilation failed for $arch")
            }

            // Create static library from simdutf object
            ProcessBuilder(ar, "rcs", simdutfLib.absolutePath, simdutfObj.absolutePath)
                .inheritIO().start().waitFor()

            // Remove the intermediate .o to save space
            simdutfObj.delete()

            // Copy the amalgamated header
            sourceDir.resolve("simdutf.h").copyTo(
                outputDir.resolve("include/simdutf.h"), overwrite = true
            )

            // Compile our C++ wrapper that provides C-linkage functions
            val wrapperSrc = projectDir.resolve("src/nativeInterop/cinterop/simdutf_wrapper.cpp")
            val wrapperObj = outputDir.resolve("lib/simdutf_wrapper.o")
            val wrapperLib = outputDir.resolve("lib/libsimdutf_wrapper.a")

            logger.lifecycle("Compiling simdutf C wrapper...")
            val wrapperArgs = mutableListOf(
                cxx, "-c", "-O2", "-fPIC",
                "-I${outputDir.resolve("include").absolutePath}",
                "-o", wrapperObj.absolutePath,
                wrapperSrc.absolutePath,
            )
            wrapperArgs.addAll(wrapperArgs.size - 1, crossFlags)

            val compileResult = ProcessBuilder(wrapperArgs)
                .inheritIO().start().waitFor()

            if (compileResult != 0) {
                throw GradleException("Failed to compile simdutf wrapper for $arch")
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
