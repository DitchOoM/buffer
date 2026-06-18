@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    // Test-only: parse vendored Wycheproof KAT vectors in commonTest.
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka)
    signing
}

group = "com.ditchoom"
val isRunningOnGithub = System.getenv("GITHUB_REPOSITORY")?.isNotBlank() == true

apply(from = "../gradle/setup.gradle.kts")

@Suppress("UNCHECKED_CAST")
val getNextVersion = project.extra["getNextVersion"] as (Boolean) -> Any
// Honor -Pversion so local publishes can pin a version without being clobbered by
// Maven Central's getNextVersion auto-increment.
if (!project.hasProperty("version") || project.version == "unspecified") {
    project.version = getNextVersion(!isRunningOnGithub).toString()
}

repositories {
    google()
    mavenCentral()
    maven { setUrl("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/kotlin-js-wrappers/") }
}

/**
 * Per-target wiring for the CryptoKit Swift shim. Returns null for non-Apple konan targets (they
 * have no CryptoKit). For an Apple target it registers a task that compiles
 * src/nativeInterop/swift/CryptoKitShim.swift into a per-target static archive
 * `lib<konan>cryptokitshim.a`, and exposes the archive name, output dir, and the toolchain's
 * per-SDK Swift runtime lib dir for the cinterop + final-binary linker.
 */
class AppleSwiftShim(
    val task: TaskProvider<Exec>,
    val archiveName: String,
    val outDir: File,
    val swiftLibDir: String,
)

fun appleSwiftShim(
    project: org.gradle.api.Project,
    konanTarget: org.jetbrains.kotlin.konan.target.KonanTarget,
): AppleSwiftShim? {
    // (swift -target triple, xcrun --sdk name, swift runtime lib subdir under .../lib/swift/)
    val spec: Triple<String, String, String> =
        when (konanTarget.name) {
            "macos_arm64" -> Triple("arm64-apple-macos11", "macosx", "macosx")
            "macos_x64" -> Triple("x86_64-apple-macos11", "macosx", "macosx")
            "ios_arm64" -> Triple("arm64-apple-ios14", "iphoneos", "iphoneos")
            "ios_simulator_arm64" -> Triple("arm64-apple-ios14-simulator", "iphonesimulator", "iphonesimulator")
            "ios_x64" -> Triple("x86_64-apple-ios14-simulator", "iphonesimulator", "iphonesimulator")
            "tvos_arm64" -> Triple("arm64-apple-tvos14", "appletvos", "appletvos")
            "tvos_simulator_arm64" -> Triple("arm64-apple-tvos14-simulator", "appletvsimulator", "appletvsimulator")
            "tvos_x64" -> Triple("x86_64-apple-tvos14-simulator", "appletvsimulator", "appletvsimulator")
            "watchos_simulator_arm64" -> Triple("arm64-apple-watchos7-simulator", "watchsimulator", "watchsimulator")
            "watchos_x64" -> Triple("x86_64-apple-watchos7-simulator", "watchsimulator", "watchsimulator")
            else -> return null
        }
    val (triple, sdkName, swiftSubdir) = spec
    val swiftLibDir =
        File(
            project.providers
                .exec { commandLine("xcrun", "--find", "swiftc") }
                .standardOutput.asText
                .get()
                .trim(),
        ).parentFile.parentFile // .../usr/bin/swiftc -> .../usr
            .resolve("lib/swift/$swiftSubdir")
            .absolutePath
    val sdkPath =
        project.providers
            .exec { commandLine("xcrun", "--sdk", sdkName, "--show-sdk-path") }
            .standardOutput.asText
            .get()
            .trim()
    val outDir =
        project.layout.buildDirectory
            .dir("cryptokitshim/${konanTarget.name}")
            .get()
            .asFile
    val archiveName = "${konanTarget.name}cryptokitshim"
    val src = project.file("src/nativeInterop/swift/CryptoKitShim.swift")
    val archive = File(outDir, "lib$archiveName.a")
    val task =
        project.tasks.register("compileCryptoKitShim${konanTarget.name.replaceFirstChar { it.uppercase() }}", Exec::class.java) {
            inputs.file(src)
            inputs.property("triple", triple)
            inputs.property("sdkPath", sdkPath)
            outputs.file(archive)
            doFirst { outDir.mkdirs() }
            commandLine(
                "xcrun",
                "swiftc",
                "-emit-library",
                "-static",
                "-target",
                triple,
                "-sdk",
                sdkPath,
                "-O",
                "-o",
                archive.absolutePath,
                src.absolutePath,
            )
        }
    return AppleSwiftShim(task, archiveName, outDir, swiftLibDir)
}

// ---------------------------------------------------------------------------
// Linux: BoringSSL provisioning + cinterop wiring for the native crypto backend.
//
// The backend statically links BoringSSL's libcrypto.a (EVP/EC/AEAD/HKDF/curve25519).
// Provisioning is self-contained for CI: `buildBoringssl<Arch>` shallow-clones BoringSSL at a
// pinned commit and builds the static lib + headers into libs/boringssl/linux-$arch (gitignored,
// never committed). The build is skipped when the libs already exist, so a dev box can drop in a
// prebuilt tree (e.g. symlink the sibling :socket: module's libs/boringssl/linux-x64) and avoid the
// multi-minute BoringSSL build entirely.
// ---------------------------------------------------------------------------

// Pinned BoringSSL commit, fetched from the GitHub mirror (which — unlike googlesource — reliably
// serves `git fetch --depth 1 <sha>`). Bump deliberately; the SHA gates the rebuild marker file.
val boringSslCommit = "63893acb3684fc756ddfa1ca4c6bab9e7b924e53"
val boringSslRepo = "https://github.com/google/boringssl.git"
val boringSslBuildScratch = layout.buildDirectory.dir("boringssl")

fun createBuildBoringSslTask(arch: String): TaskProvider<Task> {
    val taskName = "buildBoringssl${arch.replaceFirstChar { it.uppercase() }}"
    val outputDir = project.projectDir.resolve("libs/boringssl/linux-$arch")
    val markerFile = outputDir.resolve("lib/.built-$boringSslCommit")

    return tasks.register(taskName) {
        group = "build"
        description = "Build BoringSSL static libcrypto for Linux $arch"
        inputs.property("boringSslCommit", boringSslCommit)
        outputs.file(markerFile)
        onlyIf { !markerFile.exists() }

        doLast {
            val scratch = boringSslBuildScratch.get().asFile
            val srcDir = File(scratch, "boringssl")
            if (!File(srcDir, "include").exists()) {
                scratch.mkdirs()
                srcDir.deleteRecursively()
                logger.lifecycle("Cloning BoringSSL @ $boringSslCommit ...")

                fun run(
                    vararg cmd: String,
                    dir: File,
                ) {
                    val rc =
                        ProcessBuilder(*cmd)
                            .directory(dir)
                            .redirectErrorStream(true)
                            .start()
                            .also { it.inputStream.bufferedReader().forEachLine { l -> logger.lifecycle(l) } }
                            .waitFor()
                    if (rc != 0) throw GradleException("command failed (${cmd.joinToString(" ")}): exit $rc")
                }
                run("git", "init", "boringssl", dir = scratch)
                run("git", "remote", "add", "origin", boringSslRepo, dir = srcDir)
                run("git", "fetch", "--depth", "1", "origin", boringSslCommit, dir = srcDir)
                run("git", "checkout", "FETCH_HEAD", dir = srcDir)
            }

            val cmakeBuildDir = File(srcDir, "build-$arch")
            if (cmakeBuildDir.exists()) cmakeBuildDir.deleteRecursively()
            cmakeBuildDir.mkdirs()

            // Compatibility flags so the produced libcrypto.a only references symbols present in
            // Kotlin/Native's bundled (older) glibc. Modern Ubuntu gcc + glibc 2.38+ otherwise emit:
            //   * __*_chk (fortify) — from the distro's default _FORTIFY_SOURCE; disabled here.
            //   * __stack_chk_fail — from -fstack-protector; disabled here.
            //   * __isoc23_strtoull — glibc redirects strtoull under gcc 13+/C23; a tiny compat
            //     translation unit (below, appended to the archive) provides it.
            // K/N's ld.lld links against its own glibc, where these newer symbols are absent.
            val compatCFlags = "-fPIC -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=0 -fno-stack-protector"
            val cmakeArgs =
                mutableListOf(
                    "cmake",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DBUILD_SHARED_LIBS=OFF",
                    "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
                    "-DCMAKE_C_FLAGS=$compatCFlags",
                    "-DCMAKE_CXX_FLAGS=$compatCFlags",
                    "-G",
                    "Unix Makefiles",
                )
            if (arch == "arm64" && System.getProperty("os.arch") != "aarch64") {
                cmakeArgs.addAll(
                    listOf(
                        "-DCMAKE_SYSTEM_NAME=Linux",
                        "-DCMAKE_SYSTEM_PROCESSOR=aarch64",
                        "-DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc",
                        "-DCMAKE_CXX_COMPILER=aarch64-linux-gnu-g++",
                        "-DCMAKE_C_FLAGS=$compatCFlags -mno-outline-atomics",
                        "-DCMAKE_CXX_FLAGS=$compatCFlags -mno-outline-atomics",
                    ),
                )
            }
            cmakeArgs.add("..")

            fun runIn(
                dir: File,
                vararg cmd: String,
            ) {
                val rc =
                    ProcessBuilder(*cmd)
                        .directory(dir)
                        .redirectErrorStream(true)
                        .start()
                        .also { it.inputStream.bufferedReader().forEachLine { l -> logger.lifecycle(l) } }
                        .waitFor()
                if (rc != 0) throw GradleException("command failed (${cmd.joinToString(" ")}): exit $rc")
            }

            logger.lifecycle("Configuring BoringSSL for $arch ...")
            runIn(cmakeBuildDir, *cmakeArgs.toTypedArray())
            logger.lifecycle("Building BoringSSL crypto for $arch ...")
            val cpu = Runtime.getRuntime().availableProcessors()
            runIn(cmakeBuildDir, "make", "-j$cpu", "crypto")

            val builtCrypto =
                cmakeBuildDir.walk().firstOrNull { it.name == "libcrypto.a" }
                    ?: throw GradleException("libcrypto.a not found under ${cmakeBuildDir.absolutePath}")

            // Append a tiny compat translation unit providing __isoc23_strtoull (glibc 2.38+/gcc-13
            // redirects strtoull to it; K/N's bundled glibc lacks the symbol). It just forwards to
            // the classic strtoull, which is present everywhere.
            val compatC = File(cmakeBuildDir, "kn_glibc_compat.c")
            compatC.writeText(
                """
                #include <stdlib.h>
                unsigned long long __isoc23_strtoull(const char *nptr, char **endptr, int base) {
                    return strtoull(nptr, endptr, base);
                }
                """.trimIndent(),
            )
            val compatO = File(cmakeBuildDir, "kn_glibc_compat.o")
            val cc = if (arch == "arm64" && System.getProperty("os.arch") != "aarch64") "aarch64-linux-gnu-gcc" else "cc"
            runIn(cmakeBuildDir, cc, "-fPIC", "-c", compatC.absolutePath, "-o", compatO.absolutePath)
            runIn(cmakeBuildDir, "ar", "r", builtCrypto.absolutePath, compatO.absolutePath)

            outputDir.resolve("lib").mkdirs()
            builtCrypto.copyTo(outputDir.resolve("lib/libcrypto.a"), overwrite = true)

            val includeOutput = outputDir.resolve("include")
            val srcInclude = srcDir.resolve("src/include")
            val topInclude = srcDir.resolve("include")
            (if (srcInclude.exists()) srcInclude else topInclude).copyRecursively(includeOutput, overwrite = true)

            markerFile.writeText("BoringSSL $boringSslCommit built ${System.currentTimeMillis()}")
            logger.lifecycle("BoringSSL ($arch) provisioned at ${outputDir.absolutePath}")
        }
    }
}

val buildBoringSslX64 = createBuildBoringSslTask("x64")
val buildBoringSslArm64 = createBuildBoringSslTask("arm64")

fun KotlinNativeTarget.configureBoringSslCinterop(arch: String) {
    val boringsslDir = project.projectDir.resolve("libs/boringssl/linux-$arch")
    val libDir = boringsslDir.resolve("lib")
    val incDir = boringsslDir.resolve("include")
    val buildTask = if (arch == "x64") buildBoringSslX64 else buildBoringSslArm64

    compilations.getByName("main").cinterops.create("boringsslcrypto") {
        defFile(project.file("src/nativeInterop/cinterop/boringsslcrypto.def"))
        includeDirs(incDir.absolutePath)
        extraOpts("-libraryPath", libDir.absolutePath, "-staticLibrary", "libcrypto.a")
        // Build BoringSSL on demand (no-op if libs/boringssl is already populated).
        tasks.named(interopProcessingTaskName).configure { dependsOn(buildTask) }
    }
    binaries.all {
        linkerOpts("-L${libDir.absolutePath}", "-lcrypto", "-lpthread")
    }
}

kotlin {
    jvmToolchain(21)

    // SHA-256 / HMAC are modeled as expect/actual classes (multiplatform impl is a Beta feature).
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidTarget {
        publishLibraryVariants("release")
        // Use JVM 1.8 for Android to maintain maximum compatibility
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
        // Run the full common suite on a real emulator (ART), not just the host JVM.
        instrumentedTestVariant {
            sourceSetTree.set(KotlinSourceSetTree.test)
        }
    }
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
    }
    js {
        outputModuleName.set("buffer-crypto-kt")
        browser()
        nodejs()
    }
    wasmJs {
        browser()
        nodejs()
    }
    // Native crypto is backed by each platform's system library: JCA (JVM/Android),
    // CommonCrypto/Security (Apple). WebCrypto's digest is async-only, so js/wasm fall
    // back to a synchronous pure-Kotlin SHA-256/HMAC. Linux (BoringSSL) is deferred —
    // no native target is registered for it yet.
    if (isRunningOnGithub) {
        if (HostManager.hostIsMac) {
            macosX64()
            macosArm64()
            iosArm64()
            iosSimulatorArm64()
            iosX64()
            // watchosArm64 (arm64_32) is intentionally omitted: it is the only 32-bit
            // Apple target, and appleMain calls CommonCrypto/Security size_t functions plus
            // the commoncryptogcm cinterop below. Its 32-bit size_t width cannot be verified
            // on the 64-bit hosts/CI we build on, so we keep it out rather than ship unverified
            // crypto. watchOS is still covered by the simulator + x64 (64-bit) targets below.
            watchosSimulatorArm64()
            watchosX64()
            tvosArm64()
            tvosSimulatorArm64()
            tvosX64()
        }
        if (HostManager.hostIsLinux) {
            // Linux native crypto via BoringSSL (libcrypto). linuxArm64 is registered so the klib
            // is published; on an x64 CI runner its libcrypto.a is cross-built (mno-outline-atomics).
            linuxX64()
            linuxArm64()
        }
    } else if (HostManager.hostIsMac) {
        if (System.getProperty("os.arch") == "aarch64") {
            macosArm64()
        } else {
            macosX64()
        }
    } else if (HostManager.hostIsLinux) {
        if (System.getProperty("os.arch") == "aarch64") {
            linuxArm64()
        } else {
            linuxX64()
        }
    }

    // AES-GCM on Apple needs CommonCrypto's streaming GCM entry points, which live in the
    // SPI header and are absent from Kotlin/Native's platform.CoreCrypto binding. Bind them
    // via a small cinterop (forward declarations only; symbols resolve from libcommonCrypto).
    // Registered uniformly on every Apple target so the commonizer exposes it to appleMain.
    // Linux native targets: wire the BoringSSL cinterop + static libcrypto link.
    targets.matching { it.name == "linuxX64" }.configureEach {
        (this as KotlinNativeTarget).configureBoringSslCinterop("x64")
    }
    targets.matching { it.name == "linuxArm64" }.configureEach {
        (this as KotlinNativeTarget).configureBoringSslCinterop("arm64")
    }

    // Apple native targets: CommonCrypto GCM + CryptoKit shim. Guarded to Apple konan targets so it
    // never tries to bind CommonCrypto/Security headers on Linux.
    targets.withType<KotlinNativeTarget>().matching { it.konanTarget.family.isAppleFamily }.configureEach {
        compilations.getByName("main").cinterops.create("commoncryptogcm") {
            defFile(project.file("src/nativeInterop/cinterop/commoncryptogcm.def"))
        }

        // CryptoKit shim: ChaCha20-Poly1305, Ed25519, X25519, and ECDSA-sign-from-scalar live in
        // CryptoKit, which is Swift-only with no Objective-C/C interface — Kotlin/Native cinterop
        // cannot bind it directly the way it binds CommonCrypto/Security. We compile a tiny Swift
        // shim (src/nativeInterop/swift/CryptoKitShim.swift) that re-exposes those primitives through
        // an `@_cdecl` plain-C surface into a per-target static archive, then cinterop against the
        // hand-written header and link the archive + the Swift runtime. Registered on every Apple
        // target so the commonizer exposes it to appleMain.
        val konan = konanTarget
        appleSwiftShim(project, konan)?.let { shim ->
            compilations.getByName("main").cinterops.create("cryptokitshim") {
                defFile(project.file("src/nativeInterop/cinterop/cryptokitshim.def"))
                includeDirs(project.file("src/nativeInterop/swift"))
                extraOpts(
                    "-staticLibrary",
                    "lib${shim.archiveName}.a",
                    "-libraryPath",
                    shim.outDir.absolutePath,
                )
            }
            // The cinterop task is registered by the KMP plugin under a derived name; wire the
            // Swift-archive build ahead of it.
            val capName = konan.name.split("_").joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
            project.tasks.named("cinteropCryptokitshim$capName").configure { dependsOn(shim.task) }
            // The Swift static archive references the Swift runtime; point the final-binary linker
            // at the toolchain's per-SDK Swift libs and the CryptoKit/Foundation frameworks.
            binaries.all {
                linkerOpts(
                    "-L${shim.swiftLibDir}",
                    "-lswiftCore",
                    "-lswiftFoundation",
                    "-framework",
                    "CryptoKit",
                    "-framework",
                    "Foundation",
                )
            }
        }
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            api(project(":buffer"))
            // The suspend `*Async` wrappers (AEAD, signatures, key agreement) await WebCrypto
            // Promises on js/wasmJs via kotlinx.coroutines.await, so coroutines-core is part
            // of the public API surface.
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            // Parses vendored Wycheproof known-answer vectors (test-only; not shipped).
            implementation(libs.kotlinx.serialization.json)
            // Drives the suspend `*Async` entry points (AEAD seal/open, sign/verify, key
            // agreement) from tests via runTest. WebCrypto is async-only on JS/WASM.
            implementation(libs.kotlinx.coroutines.test)
        }

        // Shared TEST source set for JVM and Android: both run the common suite over the JCA
        // actuals, so platform test-support actuals (e.g. KeyAgreementTestSupport) live here
        // rather than in jvmTest alone — otherwise the Android instrumented-test compile sees
        // the commonTest `expect`s with no `actual`.
        val jvmCommonTest by creating {
            dependsOn(commonTest.get())
        }

        val androidInstrumentedTest by getting {
            dependsOn(jvmCommonTest)
            dependencies {
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.rules)
                implementation(libs.androidx.test.core.ktx)
                implementation(libs.androidx.test.ext.junit)
            }
        }

        // Android host unit-test compile (compileDebugUnitTestKotlinAndroid) also needs the
        // shared JVM/Android test actuals.
        val androidUnitTest by getting {
            dependsOn(jvmCommonTest)
        }

        // Shared source set for JVM and Android: both use the JCA (java.security /
        // javax.crypto) for MessageDigest, Mac, and SecureRandom.
        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain {
            dependsOn(jvmCommonMain)
        }
        androidMain {
            dependsOn(jvmCommonMain)
        }
        jvmTest {
            dependsOn(jvmCommonTest)
        }

        // Shared source set for JS and wasmJs: pure-Kotlin SHA-256/HMAC (WebCrypto's
        // SubtleCrypto.digest is async-only and cannot satisfy a synchronous contract).
        val jsAndWasmJsMain by creating {
            dependsOn(commonMain.get())
        }
        val jsAndWasmJsTest by creating {
            dependsOn(commonTest.get())
        }
        jsMain {
            dependsOn(jsAndWasmJsMain)
        }
        wasmJsMain {
            dependsOn(jsAndWasmJsMain)
        }
        jsTest {
            dependsOn(jsAndWasmJsTest)
        }
        wasmJsTest {
            dependsOn(jsAndWasmJsTest)
        }
    }
}

android {
    compileSdk = 36
    defaultConfig {
        // API 28+: the secure/deterministic key-material buffers route through
        // sun.misc.Unsafe.allocateMemory, which is absent from the Android runtime
        // before API 26 (NoSuchMethodError on API 21). 28 is a conservative modern
        // floor for a crypto module. AGP skips this module's instrumented tests on
        // older emulators rather than failing to install.
        minSdk = 28
    }
    namespace = "$group.buffer.crypto"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
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

    coordinates(publishedGroupId, "buffer-crypto", project.version.toString())

    pom {
        name.set("Buffer Crypto")
        description.set("Kotlin Multiplatform cryptographic primitives (SHA-256, HMAC, HKDF) for the buffer library")
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

ktlint {
    verbose.set(true)
    outputToConsole.set(true)
    android.set(true)
    filter {
        exclude("**/generated/**")
    }
}

dokka {
    dokkaSourceSets.configureEach {
        externalDocumentationLinks.register("kotlin-stdlib") {
            url("https://kotlinlang.org/api/latest/jvm/stdlib/")
            packageListUrl("https://kotlinlang.org/api/latest/jvm/stdlib/package-list")
        }
        reportUndocumented.set(false)
    }
}
