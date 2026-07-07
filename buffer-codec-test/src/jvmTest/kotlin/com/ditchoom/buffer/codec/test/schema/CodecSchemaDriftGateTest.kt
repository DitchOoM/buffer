package com.ditchoom.buffer.codec.test.schema

import com.ditchoom.buffer.codec.schema.CodecSchemaClassifier
import com.ditchoom.buffer.codec.schema.CodecSchemaParser
import com.ditchoom.buffer.codec.schema.DriftSeverity
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Wire-compatibility drift gate (SCHEMA_DRIFT.md, step 4) — the library dogfooding its own
 * schema-drift checker on this module's protocol fixtures.
 *
 * On each run it reuses the **same** [CodecSchemaParser] + [CodecSchemaClassifier] the published
 * `com.ditchoom.buffer.codec-schema` Gradle plugin runs for consumers, comparing the freshly-
 * generated aggregate descriptor (`build/generated/ksp/metadata/commonMain/resources/codec-schema.txt`,
 * populated by `kspCommonMainKotlinMetadata`) against the committed baseline
 * (`src/codecSchema/codec-schema.txt`). A [DriftSeverity.BREAKING] delta fails the build
 * (`failOnBreaking = true` semantics); an [DriftSeverity.ADVISORY] rename only prints a warning, and
 * [DriftSeverity.SAFE] additions pass silently.
 *
 * Why a test and not the plugin applied to this module: a Gradle plugin defined as a sibling
 * subproject in the same build cannot be put on another subproject's buildscript classpath (Gradle
 * forbids buildscript project dependencies), so the library exercises the plugin's pure-logic core
 * directly here. The plugin's task wiring / descriptor location is covered by
 * `buffer-codec-gradle-plugin`'s TestKit suite.
 *
 * Regen workflow (after an intentional, reviewed wire change):
 *   ./gradlew :buffer-codec-test:jvmTest -Dupdate.snapshots=true
 *   git add buffer-codec-test/src/codecSchema/codec-schema.txt
 *   git commit
 */
class CodecSchemaDriftGateTest {
    private val generated: File =
        System
            .getProperty("codec.schema.generated")
            ?.let(::File)
            ?: error(
                "codec.schema.generated system property not set — " +
                    "the gradle test task should pass it. See buffer-codec-test/build.gradle.kts.",
            )

    private val baseline: File =
        System
            .getProperty("codec.schema.baseline")
            ?.let(::File)
            ?: error(
                "codec.schema.baseline system property not set — " +
                    "the gradle test task should pass it. See buffer-codec-test/build.gradle.kts.",
            )

    private val updateMode: Boolean = System.getProperty("update.snapshots", "false") == "true"

    @Test
    fun `generated codec schema is wire-compatible with the committed baseline`() {
        if (!generated.exists()) {
            fail(
                "generated codec-schema.txt does not exist: $generated\n" +
                    "Run `./gradlew :buffer-codec-test:kspCommonMainKotlinMetadata` to populate it. " +
                    "If that fails, fix the processor first — this gate cannot check what KSP did not emit.",
            )
        }

        val generatedText = generated.readText()

        if (!baseline.exists()) {
            baseline.parentFile.mkdirs()
            baseline.writeText(generatedText)
            println(
                "codec schema baseline created at ${baseline.path} — commit it so future builds can " +
                    "detect wire-breaking drift.",
            )
            return
        }

        val drifts =
            CodecSchemaClassifier.classify(
                CodecSchemaParser.parse(baseline.readText()),
                CodecSchemaParser.parse(generatedText),
            )
        val breaking = drifts.filter { it.severity == DriftSeverity.BREAKING }
        val advisory = drifts.filter { it.severity == DriftSeverity.ADVISORY }

        if (updateMode) {
            baseline.writeText(generatedText)
            println(
                "codec schema baseline updated: ${breaking.size} breaking, ${advisory.size} advisory, " +
                    "${drifts.count { it.severity == DriftSeverity.SAFE }} safe delta(s) accepted.",
            )
            return
        }

        // Advisory (pure rename) warns but never fails — matches the plugin's failOnBreaking contract.
        advisory.forEach { println("codec schema drift (advisory): ${it.typeName} — ${it.detail}") }

        if (breaking.isNotEmpty()) {
            fail(buildFailureMessage(breaking))
        }
    }

    private fun buildFailureMessage(breaking: List<com.ditchoom.buffer.codec.schema.SchemaDrift>): String {
        val sb = StringBuilder()
        sb.appendLine(
            "Codec schema drift is breaking wire compatibility: ${breaking.size} " +
                "${if (breaking.size == 1) "delta" else "deltas"}.",
        )
        sb.appendLine()
        for ((typeName, typeDrifts) in breaking.groupBy { it.typeName }.toSortedMap()) {
            sb.appendLine("=== $typeName ===")
            typeDrifts.forEach { sb.appendLine("- ${it.detail}") }
        }
        sb.appendLine()
        sb.appendLine("Bytes already on the wire for peers without the new code would be misread.")
        sb.appendLine("If this change is intentional and reviewed, accept the new baseline with:")
        sb.appendLine("  ./gradlew :buffer-codec-test:jvmTest -Dupdate.snapshots=true")
        sb.appendLine("Then `git add buffer-codec-test/src/codecSchema/codec-schema.txt` and commit.")
        return sb.toString()
    }
}
