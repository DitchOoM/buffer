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
    alias(libs.plugins.binary.compatibility.validator)
    // Provisions the single-pinned canonical BoringSSL bundle for the Linux native crypto backend
    // (boringssl-kmp binary factory). Version resolved in settings.gradle.kts resolutionStrategy.
    id("com.ditchoom.boringssl.provision")
    signing
}

// Binary-compatibility validation locks the published 6.0 public ABI so a later minor (e.g. the
// Hardware* key variants) is proven non-breaking by `apiCheck`. We validate the JVM ABI only: it is
// host-independent (this dev host is Linux/WSL and cannot build the Apple klibs) and the common
// public surface — which is what 6.0 freezes — is wholly contained in the JVM dump. Klib ABI
// validation is left off precisely because it would diverge between a partial-target dev host and
// the all-target Mac/Linux CI runners.
apiValidation {
    klib {
        enabled = false
    }
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
    // Scoped mavenLocal: resolves the canonical :boringssl-canonical OWNER klib (external-mode
    // linux BoringSSL supply) from ~/.m2. Filtered to com.ditchoom.boringssl.* so an unrelated
    // ~/.m2 artifact can never shadow a real Central dependency (mirrors settings.gradle.kts).
    mavenLocal {
        content { includeGroupByRegex("com\\.ditchoom\\.boringssl.*") }
    }
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
            inputs.property("runtimeCompatibilityVersion", "none")
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
                // #253: at deployment targets below iOS 17, swiftc emits force-load references to the
                // Swift back-compat archives (swiftCompatibility56/Concurrency/Packs), which exist ONLY
                // in the Xcode toolchain dir — not the SDK — so any link that doesn't carry a
                // machine-specific -L (notably a consumer's cinterop static-cache link) fails with
                // `_swift_FORCE_LOAD$_swiftCompatibility*` unresolved. The shim uses no Swift feature
                // that needs back-deployment patching (no async/await, no parameter packs; plain @_cdecl
                // over CryptoKit), so opt out of the compat archives entirely. Everything else the shim
                // auto-links (swiftCore, swiftFoundation, CryptoKit, ...) resolves from the SDK, which
                // every ld invocation already searches via -syslibroot.
                "-runtime-compatibility-version",
                "none",
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
// The backend statically links BoringSSL's libcrypto.a (EVP/EC/AEAD/HKDF/curve25519). Provisioning is
// delegated to the published `com.ditchoom.boringssl.provision` plugin (the boringssl-kmp binary
// factory): it downloads + sha256-verifies ONE canonical, single-pinned BoringSSL bundle and injects
// the cinterop link config — replacing the old ~150-line in-repo clone+cmake pipeline (which pinned its
// own BoringSSL commit and hand-patched glibc-compat symbols). The canonical pin now follows quiche and
// lives in boringssl-kmp's version catalog, not here. cryptoOnly links libcrypto.a alone (buffer-crypto
// has no TLS/DTLS surface), and the manylinux2014/glibc-2.17 bundle needs none of the old
// fortify/stack-protector/__isoc23_strtoull compat hacks.
//
// Bundle version + local dist dir are parameterized so the SAME script drives:
//   * the published GitHub Release (default, no -P overrides): the plugin's baked-in checksums +
//     default baseUrl fetch the release tarballs by stable direct URL (no TOFU).
//   * local dev against an unreleased candidate: -PboringsslLocalBundle at :boringssl-build/build/dist
//     (and -PboringsslBundleVersion / -PboringsslOwnerVersion / -PboringsslPluginVersion to pin it).
// ---------------------------------------------------------------------------
val boringsslBundleVersion = providers.gradleProperty("boringsslBundleVersion").getOrElse("0.0.6")
val boringsslLocalBundle = providers.gradleProperty("boringsslLocalBundle").orNull
// Version of the canonical :boringssl-canonical OWNER klib whose single libcrypto.a this
// EXTERNAL-mode consumer links against (api dep on linuxMain). Overridable via -P.
val boringsslOwnerVersion = providers.gradleProperty("boringsslOwnerVersion").getOrElse("0.0.6")

boringssl {
    version = boringsslBundleVersion
    // -PboringsslLocalBundle=<dir> points at a :boringssl-build build/dist holding
    // boringssl-<version>-<triple>.tar.gz(+.sha256). Relative paths resolve against rootDir; absolute
    // paths are used as-is. Left unset → the plugin falls back to its baked checksums + release fetch.
    boringsslLocalBundle?.let { p ->
        val f = File(p)
        localDist = if (f.isAbsolute) f else rootDir.resolve(p)
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
        browser {
            // Bundle the whole module graph (stdlib + coroutines + buffer + buffer-crypto) into one
            // self-contained webpack file that exposes the @JsExport surface as a `bufferCryptoKt`
            // global, so the docs site can load the real CryptoDemo facade with a single <script>.
            // Only adds a JS distribution artifact; the Maven publication remains the klib.
            webpackTask {
                output.library = "bufferCryptoKt"
                output.libraryTarget = "umd"
            }
        }
        nodejs()
        binaries.executable()
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
            // Linux native crypto via BoringSSL (libcrypto). linuxArm64 is registered so the klib is
            // published; on an x64 CI runner its libcrypto.a comes from the prebuilt linuxArm64
            // provision bundle (no cross-build).
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
        // Register BOTH linux targets locally (mirrors buffer-flow/buffer-codec) so the linuxArm64
        // klib publishes to mavenLocal even on an x64 host — socket-quic-quiche's linuxArm64 seam
        // swap (buffer-crypto.sha256) needs it to resolve. The non-host arch libcrypto.a comes from
        // the prebuilt linuxArm64 provision bundle (see boringssl { } above).
        linuxX64()
        linuxArm64()
    }

    // Linux native targets: wire the BoringSSL cinterop from the provision plugin. cinteropName MUST
    // stay "boringsslcrypto" so the generated klib keeps the package the Kotlin sources import
    // (com.ditchoom.buffer.crypto.cinterop.boringssl.*, carried by the .def's `package=` line); the
    // default name would rename the cinterop/klib and break those imports. cryptoOnly=true links
    // libcrypto.a alone; the .def declares no linkerOpts so the plugin's `-lpthread -ldl` floor fires.
    targets.matching { it.name == "linuxX64" || it.name == "linuxArm64" }.configureEach {
        boringssl.cinterop(
            target = this as KotlinNativeTarget,
            cinteropName = "boringsslcrypto",
            def = file("src/nativeInterop/cinterop/boringsslcrypto.def"),
            cryptoOnly = true,
            // EXTERNAL mode: this consumer no longer embeds its own libcrypto.a. The single canonical
            // archive comes from the :boringssl-canonical owner klib (api dep on linuxMain below); K/N
            // auto-propagates it to every final Linux link, so buffer-crypto + socket co-link ONE copy.
            embedArchive = false,
        )
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
                    // #253 root fix lives in the swiftc invocation above (-runtime-compatibility-version
                    // none): the shim archive embedded in this klib carries no Swift back-compat
                    // force-load references, so neither our link nor a consumer's cinterop static-cache
                    // link needs the toolchain's Swift lib dir. Do NOT pass -linkerOpts -L<toolchain>
                    // here: it bakes a machine-specific Xcode path into the published artifact and did
                    // not propagate to consumers' cache links anyway (klib manifests from extraOpts
                    // carried no linkerOpts key — see the 6.4.0 regression report on #253).
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
            dependencies {
                // `api`: BiometricPromptAuthenticator's constructor exposes FragmentActivity and
                // BiometricPrompt types, so consumers must see them on their compile classpath.
                api(libs.androidx.biometric)
            }
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
            dependencies {
                // Node has no IndexedDB; this shim installs a spec-compliant one on globalThis so the
                // WebCrypto/IndexedDB key store (WebCryptoKeyStoreTest) can run under jsNodeTest.
                implementation(npm("fake-indexeddb", "6.0.0"))
            }
        }
        wasmJsTest {
            dependsOn(jsAndWasmJsTest)
        }

        // EXTERNAL-mode linux: hard-depend on the single canonical BoringSSL owner klib so its ONE
        // libcrypto.a reaches every linux final link (buffer-crypto's own linuxX64Test AND downstream
        // co-links like socket-quic-quiche). Guarded to a linux host where the linuxMain default-
        // hierarchy source set actually exists (linux targets are only registered there).
        if (HostManager.hostIsLinux) {
            val linuxMain by getting {
                dependencies {
                    api("com.ditchoom.boringssl:boringssl-canonical:$boringsslOwnerVersion")
                }
            }
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

// Forward the tpm2-pkcs11 harness configuration into the JVM test process, so the TPM-backed
// provider tests can reach a real (or swtpm-emulated) token. Gradle -P properties become the
// library's system properties (fresh per invocation, immune to daemon-env staleness); the
// TPM2_PKCS11_* / DBUS_* environment is what the native tpm2-pkcs11 module itself reads.
tasks.withType<Test>().configureEach {
    listOf(
        "buffer.crypto.tpm2.pkcs11.module",
        "buffer.crypto.tpm2.pkcs11.pin",
        "buffer.crypto.tpm2.pkcs11.slotIndex",
        "buffer.crypto.require.tpm2",
        "buffer.crypto.require.p11.agreement",
    ).forEach { key ->
        (project.findProperty(key) as? String)?.let { systemProperty(key, it) }
    }
    listOf("TPM2_PKCS11_TCTI", "TPM2_PKCS11_STORE", "DBUS_SESSION_BUS_ADDRESS", "SOFTHSM2_CONF").forEach { key ->
        System.getenv(key)?.let { environment(key, it) }
    }
}
