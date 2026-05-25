package com.ditchoom.buffer.codec.test.snapshot

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Golden-file snapshot of every codec the KSP processor emits for the
 * fixtures in this module.
 *
 * On each run: the test walks `build/generated/ksp/metadata/commonMain/kotlin/`
 * (which `kspCommonMainKotlinMetadata` populates as part of the normal build),
 * walks `codec-snapshots/` (checked into git), and compares the two trees
 * file-by-file with line-ending normalization.
 *
 * Why this exists: the v4 → v5.0 strip-and-rebuild of `buffer-codec-processor`
 * silently changed the emit shape in several places (nested codec naming
 * regressed → issue #156; the batching optimizer was deleted → silent perf
 * regression; the SPI was removed → silent source-break for v4 extenders).
 * Round-trip tests pass on *different but still valid* output, so none of
 * these surfaced until an external user hit one in production. This test
 * makes every emit-shape change a reviewable diff at PR time.
 *
 * Regen workflow:
 *   ./gradlew :buffer-codec-test:jvmTest -Dupdate.snapshots=true
 *   git add buffer-codec-test/codec-snapshots
 *   git commit
 *
 * The diff that lands in the regen commit becomes the change's migration note.
 */
class CodecSnapshotTest {
    private val generatedRoot: File =
        System
            .getProperty("codec.snapshot.generated")
            ?.let(::File)
            ?: error(
                "codec.snapshot.generated system property not set — " +
                    "the gradle test task should pass it. See buffer-codec-test/build.gradle.kts.",
            )

    private val baselineRoot: File =
        System
            .getProperty("codec.snapshot.baseline")
            ?.let(::File)
            ?: error(
                "codec.snapshot.baseline system property not set — " +
                    "the gradle test task should pass it. See buffer-codec-test/build.gradle.kts.",
            )

    private val updateMode: Boolean = System.getProperty("update.snapshots", "false") == "true"

    @Test
    fun `generated codec output matches checked-in baselines`() {
        if (!generatedRoot.exists()) {
            fail(
                "KSP output directory does not exist: $generatedRoot\n" +
                    "Run `./gradlew :buffer-codec-test:kspCommonMainKotlinMetadata` " +
                    "to populate it. If that fails, fix the processor first — " +
                    "this test cannot snapshot what KSP did not generate.",
            )
        }

        val generatedByRel = collectKtFiles(generatedRoot)
        val baselineByRel = if (baselineRoot.exists()) collectKtFiles(baselineRoot) else emptyMap()

        if (generatedByRel.isEmpty()) {
            fail(
                "KSP produced zero .kt files under $generatedRoot. " +
                    "Either KSP silently failed or the fixture set has no @ProtocolMessage classes — investigate.",
            )
        }

        val mismatches = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val orphans = mutableListOf<String>()

        for ((rel, generated) in generatedByRel) {
            val generatedContent = normalize(generated.readText())
            val baseline = baselineByRel[rel]
            if (baseline == null) {
                if (updateMode) {
                    val target = File(baselineRoot, rel)
                    target.parentFile.mkdirs()
                    target.writeText(generatedContent)
                } else {
                    missing += rel
                }
            } else {
                val baselineContent = normalize(baseline.readText())
                if (generatedContent != baselineContent) {
                    if (updateMode) {
                        baseline.writeText(generatedContent)
                    } else {
                        mismatches += rel
                    }
                }
            }
        }

        for ((rel, baseline) in baselineByRel) {
            if (rel !in generatedByRel) {
                if (updateMode) {
                    baseline.delete()
                } else {
                    orphans += rel
                }
            }
        }

        if (updateMode) {
            println(
                "codec snapshots updated: ${generatedByRel.size} generated, " +
                    "${missing.size} new, ${mismatches.size} changed, ${orphans.size} removed.",
            )
            return
        }

        if (mismatches.isEmpty() && missing.isEmpty() && orphans.isEmpty()) return

        fail(buildFailureMessage(generatedByRel, baselineByRel, mismatches, missing, orphans))
    }

    private fun collectKtFiles(root: File): Map<String, File> =
        root
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associateBy { it.relativeTo(root).invariantSeparatorsPath }

    private fun normalize(content: String): String = content.replace("\r\n", "\n")

    private fun buildFailureMessage(
        generated: Map<String, File>,
        baseline: Map<String, File>,
        mismatches: List<String>,
        missing: List<String>,
        orphans: List<String>,
    ): String {
        val sb = StringBuilder()
        sb.appendLine(
            "Codec snapshot mismatch: " +
                "${mismatches.size} changed, ${missing.size} new, ${orphans.size} removed.",
        )
        sb.appendLine()

        if (mismatches.isNotEmpty()) {
            sb.appendLine("=== Changed files ===")
            for (rel in mismatches.take(MAX_FILES_REPORTED)) {
                sb.appendLine("- $rel")
                val expected = normalize(baseline.getValue(rel).readText())
                val actual = normalize(generated.getValue(rel).readText())
                sb.append(firstLineDifferences(expected, actual, MAX_DIFF_LINES_PER_FILE))
            }
            if (mismatches.size > MAX_FILES_REPORTED) {
                sb.appendLine("- ... and ${mismatches.size - MAX_FILES_REPORTED} more")
            }
            sb.appendLine()
        }

        if (missing.isNotEmpty()) {
            sb.appendLine("=== New files (no baseline) ===")
            missing.take(MAX_FILES_REPORTED).forEach { sb.appendLine("- $it") }
            if (missing.size > MAX_FILES_REPORTED) {
                sb.appendLine("- ... and ${missing.size - MAX_FILES_REPORTED} more")
            }
            sb.appendLine()
        }

        if (orphans.isNotEmpty()) {
            sb.appendLine("=== Removed files (baseline orphaned) ===")
            orphans.take(MAX_FILES_REPORTED).forEach { sb.appendLine("- $it") }
            if (orphans.size > MAX_FILES_REPORTED) {
                sb.appendLine("- ... and ${orphans.size - MAX_FILES_REPORTED} more")
            }
            sb.appendLine()
        }

        sb.appendLine("To accept the current generated output as the new baseline, run:")
        sb.appendLine("  ./gradlew :buffer-codec-test:jvmTest -Dupdate.snapshots=true")
        sb.appendLine("Then `git add buffer-codec-test/codec-snapshots` and commit.")
        sb.appendLine("Review the diff carefully — every change here is a wire-format")
        sb.appendLine("or codec-shape change that downstream users will see.")
        return sb.toString()
    }

    private fun firstLineDifferences(
        expected: String,
        actual: String,
        maxLines: Int,
    ): String {
        val sb = StringBuilder()
        val expectedLines = expected.lines()
        val actualLines = actual.lines()
        val limit = maxOf(expectedLines.size, actualLines.size)
        var emitted = 0
        for (i in 0 until limit) {
            val e = expectedLines.getOrNull(i)
            val a = actualLines.getOrNull(i)
            if (e != a) {
                sb.appendLine("    L${i + 1} - ${e ?: "<missing>"}")
                sb.appendLine("    L${i + 1} + ${a ?: "<missing>"}")
                emitted++
                if (emitted >= maxLines) {
                    sb.appendLine("    ... (more differences truncated)")
                    break
                }
            }
        }
        return sb.toString()
    }

    private companion object {
        const val MAX_FILES_REPORTED = 10
        const val MAX_DIFF_LINES_PER_FILE = 20
    }
}
