// Root build file for multi-module buffer project.
// Module-specific configuration is in buffer/build.gradle.kts, buffer-compression/build.gradle.kts,
// buffer-flow/build.gradle.kts, buffer-codec/build.gradle.kts, buffer-codec-processor/build.gradle.kts,
// and buffer-codec-test/build.gradle.kts

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.allopen) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.atomicfu) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.kotlinx.benchmark) apply false
    // Applied (apply false here) so every module can pull it in via the allprojects block below.
    // detekt is the only static analyzer that sees the Kotlin/Native (appleMain, linuxMain) and
    // JS/WASM source sets — CodeQL's java-kotlin extractor only traces JVM bytecode, so detekt
    // fills the multiplatform coverage gap. Run non-blocking with committed per-module baselines.
    alias(libs.plugins.detekt) apply false
}

// ---- detekt: multiplatform static analysis (non-blocking; see .github/workflows/detekt.yaml) ----
// Applied to every project so each module gets its own `detekt` / `detektBaseline` task that
// covers commonMain + every platform actual (appleMain, linuxMain, jsMain, wasmJsMain,
// androidMain, jvmMain). The root `detektAll` aggregate fans out to all of them.
// Findings are surfaced, not enforced: the CI run is non-blocking and committed per-module
// baselines (<module>/config/detekt/baseline.xml) suppress existing findings so only NEW
// issues show up. Regenerate with `./gradlew detektBaseline`.
allprojects {
    apply(plugin = "dev.detekt")

    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        // Apply detekt's bundled default ruleset (no custom config file needed for the rollout).
        buildUponDefaultConfig.set(true)
        // Suppress all findings recorded at rollout time; new findings still surface.
        baseline.set(layout.projectDirectory.file("config/detekt/baseline.xml"))
        parallel.set(true)
        // The CI workflow is already non-blocking (continue-on-error), but keep the task from
        // red-failing local `./gradlew detekt` runs on pre-existing findings too.
        ignoreFailures.set(true)
        // detekt's default source roots are the JVM layout (src/main/kotlin, src/test/kotlin),
        // which a Kotlin Multiplatform module does NOT use — its sources live under
        // src/<sourceSet>/kotlin (commonMain, appleMain, linuxMain, jsMain, wasmJsMain, ...).
        // Point detekt at every existing src/*/kotlin dir so the analysis actually covers the
        // Native/JS/WASM actuals that CodeQL can't see. This is what makes detekt the
        // multiplatform-coverage tool here. Missing dirs are ignored by detekt at runtime.
        val ktSourceRoots = layout.projectDirectory.dir("src").asFile
            .listFiles { f -> f.isDirectory }
            ?.map { layout.projectDirectory.dir("src/${it.name}/kotlin") }
            ?: emptyList()
        if (ktSourceRoots.isNotEmpty()) {
            source.setFrom(ktSourceRoots)
        }
    }
}

// Aggregate detekt run across every module — the entry point CI invokes.
tasks.register("detektAll") {
    description = "Run detekt static analysis across all modules and Kotlin source sets (non-blocking)."
    group = "verification"
    dependsOn(subprojects.map { "${it.path}:detekt" })
    dependsOn("detekt")
}

// Aggregate tasks for convenience
tasks.register("allTests") {
    description = "Run tests for all modules and platforms"
    group = "verification"
    dependsOn(":buffer:allTests", ":buffer-compression:allTests", ":buffer-flow:allTests", ":buffer-codec:allTests", ":buffer-codec-schema:test", ":buffer-codec-processor:test", ":buffer-codec-gradle-plugin:test", ":buffer-codec-test:allTests")
}

// Pre-publish gate: superset of `allTests` that also covers Android-host JVM unit tests
// (testDebugUnitTest) and JS browser tests (jsBrowserTest) — the two suites that
// `allTests` skips and that have historically masked Android-only / browser-only bugs
// (e.g. WrapNativeAddressTest JNI lookup, AllocatorLeakSharedTest SharedArrayBuffer + TextDecoder).
// Run before publishToMavenLocal to surface those classes of bug at publish time.
tasks.register("prePublishCheck") {
    description = "allTests + Android-host unit tests + JS browser tests. Run before publishToMavenLocal."
    group = "verification"
    dependsOn("allTests")
    dependsOn(
        // :buffer pins testBuildType = "benchmark", so its only Android-host unit-test
        // task is testBenchmarkUnitTest (AGP 9 no longer generates testDebugUnitTest for it).
        ":buffer:testBenchmarkUnitTest",
        ":buffer-compression:testDebugUnitTest",
        ":buffer-flow:testDebugUnitTest",
        ":buffer-codec:testDebugUnitTest",
    )
    dependsOn(
        ":buffer:jsBrowserTest",
        ":buffer-compression:jsBrowserTest",
        ":buffer-flow:jsBrowserTest",
        ":buffer-codec:jsBrowserTest",
        ":buffer-codec-test:jsBrowserTest",
    )
}

// Android instrumented variant of prePublishCheck — requires a connected emulator/device.
// Separate task so developers without an emulator aren't blocked by the cheaper gate.
tasks.register("prePublishCheckAndroid") {
    description = "prePublishCheck + Android instrumented tests. Requires a connected emulator/device."
    group = "verification"
    dependsOn("prePublishCheck")
    dependsOn(":buffer:connectedBenchmarkAndroidTest")
    dependsOn(
        ":buffer-compression:connectedDebugAndroidTest",
        ":buffer-flow:connectedDebugAndroidTest",
        ":buffer-codec:connectedDebugAndroidTest",
    )
}

// Every publishToMavenLocal across the repo must run the prePublishCheck gate first,
// so the test suites that historically masked Android-only / browser-only bugs run
// before any artifact lands in the local Maven cache.
subprojects {
    tasks.matching { it.name == "publishToMavenLocal" }.configureEach {
        dependsOn(rootProject.tasks.named("prePublishCheck"))
    }
}

tasks.register("buildAll") {
    description = "Build all modules"
    group = "build"
    dependsOn(":buffer:build", ":buffer-compression:build", ":buffer-flow:build", ":buffer-codec:build", ":buffer-codec-schema:build", ":buffer-codec-processor:build", ":buffer-codec-gradle-plugin:build", ":buffer-codec-test:build")
}

// minSdk-aware aggregate for Android instrumented tests on a SPECIFIC emulator API level.
//
// Why this exists: AGP's `connectedAndroidTest` does NOT skip a module whose
// `minSdk` exceeds the connected device's API level — it logs
//   "Skipping device '<emu>' for ':module:': minSdkVersion [N] > deviceApiLevel [M]"
//   "> : No compatible devices connected.[TestRunner] FAILED"
// and FAILS the task (there is no AGP flag to turn that into a skip). On the CI
// emulator matrix that means any module with a minSdk above the matrix's lowest
// API level (e.g. an API-21 leg) red-fails the whole leg even though the emulator
// booted fine and every compatible module passed. See PR discussion / the
// `android_integration.yaml` API-21 leg failures.
//
// Fix: schedule only the connected-test tasks for modules whose `minSdk` is <= the
// emulator API level. The level is passed by CI as `-PdeviceApiLevel=<api-level>`
// (the same value as the emulator matrix entry). When the property is absent every
// Android module is included (local `connectedCheck`-style runs against whatever
// device is attached keep their existing all-modules behaviour).
tasks.register("connectedAndroidTestCompatible") {
    description = "Run connectedDebugAndroidTest only for Android modules whose minSdk <= -PdeviceApiLevel (defaults to all modules when unset)."
    group = "verification"

    val deviceApiLevel = (findProperty("deviceApiLevel") as String?)?.toIntOrNull()

    // Android library modules instrumented in CI, paired with the module's declared
    // minSdk (see each module's build.gradle.kts `android { defaultConfig { minSdk } }`).
    // This mirrors exactly the set the previous CI command
    // (`connectedAndroidTest -x connectedBenchmarkAndroidTest`) scheduled: the
    // debug-variant connected tests. `:buffer` is intentionally absent — it pins
    // testBuildType = "benchmark", so its only connected variant is
    // connectedBenchmarkAndroidTest, which the previous command excluded with `-x`
    // (emulator timings are not representative) and which therefore stays excluded here.
    // When a new module is added (e.g. buffer-crypto, minSdk 28), add it here with its
    // minSdk so the API-21 leg cleanly skips it instead of failing.
    val androidConnectedTests =
        listOf(
            Triple(":buffer-compression", ":buffer-compression:connectedDebugAndroidTest", 21),
            Triple(":buffer-flow", ":buffer-flow:connectedDebugAndroidTest", 21),
            Triple(":buffer-codec", ":buffer-codec:connectedDebugAndroidTest", 21),
        )

    androidConnectedTests.forEach { (_, testTaskPath, minSdk) ->
        val compatible = deviceApiLevel == null || minSdk <= deviceApiLevel
        if (compatible) {
            dependsOn(testTaskPath)
        } else {
            logger.lifecycle(
                "connectedAndroidTestCompatible: skipping $testTaskPath " +
                    "(minSdk=$minSdk > deviceApiLevel=$deviceApiLevel)",
            )
        }
    }
}

// Copy Dokka output to Docusaurus static directory
tasks.register<Copy>("copyDokkaToDocusaurus") {
    description = "Generate and copy API documentation to Docusaurus"
    group = "documentation"
    dependsOn(":buffer:dokkaGenerateHtml", ":buffer-compression:dokkaGenerateHtml", ":buffer-flow:dokkaGenerateHtml", ":buffer-codec:dokkaGenerateHtml", ":buffer-crypto:dokkaGenerateHtml")

    from(layout.projectDirectory.dir("buffer/build/dokka/html")) {
        into("buffer")
    }
    from(layout.projectDirectory.dir("buffer-compression/build/dokka/html")) {
        into("buffer-compression")
    }
    from(layout.projectDirectory.dir("buffer-flow/build/dokka/html")) {
        into("buffer-flow")
    }
    from(layout.projectDirectory.dir("buffer-codec/build/dokka/html")) {
        into("buffer-codec")
    }
    from(layout.projectDirectory.dir("buffer-crypto/build/dokka/html")) {
        into("buffer-crypto")
    }
    into(layout.projectDirectory.dir("docs/static/api"))
}
