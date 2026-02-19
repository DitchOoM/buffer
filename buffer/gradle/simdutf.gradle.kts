/**
 * simdutf Build Configuration for Linux and Apple
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
 * simdutf is used on Linux and Apple native targets for SIMD-accelerated
 * UTF-8 transcoding. JVM has CharsetDecoder, JS/Wasm have TextDecoder.
 *
 * Exports (via extra properties):
 * - buildSimdutfLinuxX64: TaskProvider<Task> - builds simdutf for Linux x64
 * - buildSimdutfLinuxArm64: TaskProvider<Task> - builds simdutf for Linux arm64
 * - buildSimdutfAppleArm64: TaskProvider<Task> - builds simdutf for Apple arm64
 * - buildSimdutfAppleX64: TaskProvider<Task> - builds simdutf for Apple x64
 * - simdutfLibsDir: File - directory containing built libraries
 */

import java.net.URI
import java.security.MessageDigest

// Host OS detection (HostManager is not on the classpath in applied scripts)
val hostIsLinux = org.gradle.internal.os.OperatingSystem.current().isLinux
val hostIsMac = org.gradle.internal.os.OperatingSystem.current().isMacOsX

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
    // Fallback to system clang++ on macOS, g++ on Linux
    return if (hostIsMac) "clang++" else "g++"
}

fun findLlvmAr(): String {
    val llvmDir = findKonanDir("llvm-")
    val ar = llvmDir?.resolve("bin/llvm-ar")
    if (ar != null && ar.exists()) return ar.absolutePath
    return if (hostIsMac) "ar" else "ar"
}

fun findAarch64GccToolchain(): File? =
    findKonanDir("aarch64-unknown-linux-gnu-gcc-")

/**
 * Find macOS SDK sysroot path via xcrun.
 * K/N's bundled clang "essentials" doesn't include C++ headers,
 * so we need the SDK sysroot for C++ compilation on macOS.
 */
fun findMacOsSdkPath(): String {
    val process = ProcessBuilder("xcrun", "--show-sdk-path")
        .redirectErrorStream(true).start()
    val result = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()
    return result
}

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

/**
 * Creates a build task for simdutf targeting a specific platform/architecture.
 *
 * @param platform "linux" or "apple"
 * @param arch "x64" or "arm64"
 * @param hostCheck Lambda that returns true if this host can build for this target
 * @param crossFlags Lambda that returns extra compiler flags for cross-compilation
 */
fun createBuildSimdutfTask(
    platform: String,
    arch: String,
    hostCheck: () -> Boolean,
    crossFlags: () -> List<String>,
): TaskProvider<Task> {
    val capitalPlatform = platform.replaceFirstChar { it.uppercase() }
    val capitalArch = arch.replaceFirstChar { it.uppercase() }
    val taskName = "buildSimdutf${capitalPlatform}${capitalArch}"
    val outputDir = simdutfLibsDir.resolve("$platform-$arch")
    val markerFile = outputDir.resolve("lib/.built-$simdutfVersion")

    return tasks.register(taskName) {
        group = "build"
        description = "Build simdutf static library for $capitalPlatform $arch"

        inputs.property("simdutfVersion", simdutfVersion)
        inputs.property("simdutfSha256", simdutfSha256)
        inputs.file(projectDir.resolve("src/nativeInterop/cinterop/simdutf_wrapper.cpp"))
        outputs.file(markerFile)
        outputs.dir(outputDir)

        onlyIf { !markerFile.exists() && hostCheck() }

        doLast {
            val downloadDir = simdutfBuildDir.get().asFile
            val sourceDir = downloadAmalgamatedSource(downloadDir, simdutfVersion, simdutfSha256)

            // Use K/N's bundled clang++ and llvm-ar
            val cxx = findClangPP()
            val ar = findLlvmAr()
            val flags = crossFlags()
            logger.lifecycle("Using compiler: $cxx (flags: $flags)")

            // Create output dirs
            outputDir.resolve("lib").mkdirs()
            outputDir.resolve("include").mkdirs()

            // Compile simdutf from amalgamated single-file source
            val simdutfObj = outputDir.resolve("lib/simdutf.o")
            val simdutfLib = outputDir.resolve("lib/libsimdutf.a")

            logger.lifecycle("Compiling simdutf $simdutfVersion (amalgamated) for $platform-$arch...")
            val compileArgs = mutableListOf(
                cxx, "-c", "-O2", "-fPIC",
                "-ffunction-sections", "-fdata-sections",
                "-I${sourceDir.absolutePath}",
                "-o", simdutfObj.absolutePath,
                sourceDir.resolve("simdutf.cpp").absolutePath,
            )
            compileArgs.addAll(compileArgs.size - 1, flags)

            val compileSimdutfResult = ProcessBuilder(compileArgs)
                .inheritIO().start().waitFor()

            if (compileSimdutfResult != 0) {
                throw GradleException("simdutf compilation failed for $platform-$arch")
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

            logger.lifecycle("Compiling simdutf C wrapper for $platform-$arch...")
            val wrapperArgs = mutableListOf(
                cxx, "-c", "-O2", "-fPIC",
                "-I${outputDir.resolve("include").absolutePath}",
                "-o", wrapperObj.absolutePath,
                wrapperSrc.absolutePath,
            )
            wrapperArgs.addAll(wrapperArgs.size - 1, flags)

            val compileResult = ProcessBuilder(wrapperArgs)
                .inheritIO().start().waitFor()

            if (compileResult != 0) {
                throw GradleException("Failed to compile simdutf wrapper for $platform-$arch")
            }

            // Create static library from wrapper
            ProcessBuilder(ar, "rcs", wrapperLib.absolutePath, wrapperObj.absolutePath)
                .inheritIO().start().waitFor()

            // Write marker file
            markerFile.parentFile.mkdirs()
            markerFile.writeText("simdutf $simdutfVersion built on ${System.currentTimeMillis()}")

            logger.lifecycle("simdutf $simdutfVersion built successfully for $platform-$arch")
        }
    }
}

// =============================================================================
// Linux Build Tasks
// =============================================================================
val buildSimdutfLinuxX64: TaskProvider<Task> by extra {
    createBuildSimdutfTask("linux", "x64", hostCheck = { hostIsLinux }) {
        emptyList()
    }
}

val buildSimdutfLinuxArm64: TaskProvider<Task> by extra {
    createBuildSimdutfTask("linux", "arm64", hostCheck = { hostIsLinux }) {
        val isArmCross = System.getProperty("os.arch") != "aarch64"
        if (isArmCross) {
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
    }
}

// Backward-compatible aliases for existing build.gradle.kts references
val buildSimdutfX64: TaskProvider<Task> by extra { buildSimdutfLinuxX64 }
val buildSimdutfArm64: TaskProvider<Task> by extra { buildSimdutfLinuxArm64 }

// =============================================================================
// Apple Build Tasks
// =============================================================================
val buildSimdutfAppleArm64: TaskProvider<Task> by extra {
    createBuildSimdutfTask("apple", "arm64", hostCheck = { hostIsMac }) {
        // K/N's bundled clang needs macOS SDK sysroot for C++ standard library headers
        val sysroot = findMacOsSdkPath()
        if (System.getProperty("os.arch") == "aarch64") {
            // Native compilation on Apple Silicon
            listOf("-isysroot", sysroot)
        } else {
            // Cross-compile from Intel Mac to ARM64
            listOf("-isysroot", sysroot, "--target=arm64-apple-macosx")
        }
    }
}

val buildSimdutfAppleX64: TaskProvider<Task> by extra {
    createBuildSimdutfTask("apple", "x64", hostCheck = { hostIsMac }) {
        val sysroot = findMacOsSdkPath()
        if (System.getProperty("os.arch") != "aarch64") {
            // Native compilation on Intel Mac
            listOf("-isysroot", sysroot)
        } else {
            // Cross-compile from Apple Silicon to x64
            listOf("-isysroot", sysroot, "--target=x86_64-apple-macosx")
        }
    }
}

// =============================================================================
// Placeholder Libraries for Cross-Host Metadata Compilation
// =============================================================================
// On non-Linux hosts (macOS CI), create empty placeholder .a files so
// cinterop linuxX64/linuxArm64 metadata compilation can resolve the library path.
if (!hostIsLinux) {
    for (arch in listOf("x64", "arm64")) {
        val libDir = simdutfLibsDir.resolve("linux-$arch/lib")
        if (!libDir.resolve("libsimdutf.a").exists()) {
            libDir.mkdirs()
            libDir.resolve("libsimdutf.a").writeBytes(byteArrayOf())
            libDir.resolve("libsimdutf_wrapper.a").writeBytes(byteArrayOf())
        }
    }
}

// On non-macOS hosts (Linux CI), create empty placeholder .a files so
// cinterop Apple metadata compilation can resolve the library path.
if (!hostIsMac) {
    for (arch in listOf("arm64", "x64")) {
        val libDir = simdutfLibsDir.resolve("apple-$arch/lib")
        if (!libDir.resolve("libsimdutf.a").exists()) {
            libDir.mkdirs()
            libDir.resolve("libsimdutf.a").writeBytes(byteArrayOf())
            libDir.resolve("libsimdutf_wrapper.a").writeBytes(byteArrayOf())
        }
    }
}

// Export the libs directory for use in cinterop configuration
extra["simdutfLibsDir"] = simdutfLibsDir
