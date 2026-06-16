package com.ditchoom.buffer.codec.gradle

import com.ditchoom.buffer.codec.schema.CodecSchemaClassifier
import com.ditchoom.buffer.codec.schema.CodecSchemaParser
import com.ditchoom.buffer.codec.schema.DriftSeverity
import com.ditchoom.buffer.codec.schema.SchemaDrift
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Diffs the freshly-generated `codec-schema.txt` against the baselined one and classifies drift
 * (SCHEMA_DRIFT.md). Wired into `check`.
 *
 * - **No baseline yet** → writes the generated descriptor to [baseline] and asks the author to
 *   commit it (the deliberate first-adoption gesture).
 * - **Safe-only drift** → silent pass.
 * - **Advisory drift** (a pure rename) → warns; never fails, even under [failOnBreaking].
 * - **Breaking drift** → warns by default; fails the build when [failOnBreaking] is `true`.
 */
@DisableCachingByDefault(because = "A wire-compat gate that may write a missing baseline; not worth caching")
abstract class CheckCodecSchemaTask : AbstractCodecSchemaTask() {
    /** The baselined descriptor (read; created from the generated one when absent). */
    @get:Internal
    abstract val baseline: RegularFileProperty

    /** Escalate breaking drift from a warning to a build failure. */
    @get:Input
    abstract val failOnBreaking: Property<Boolean>

    @TaskAction
    fun check() {
        val generated = locateDescriptor()
        if (generated == null) {
            logger.info("codec schema: no descriptor generated (no @ProtocolMessage types?) — nothing to check")
            return
        }

        val baselineFile = baseline.get().asFile
        if (!baselineFile.exists()) {
            baselineFile.parentFile?.mkdirs()
            baselineFile.writeText(generated.readText())
            logger.warn(
                "codec schema baseline created at ${baselineFile.path} — commit it to source control so " +
                    "future builds can detect wire-breaking drift.",
            )
            return
        }

        val drifts =
            CodecSchemaClassifier.classify(
                CodecSchemaParser.parse(baselineFile.readText()),
                CodecSchemaParser.parse(generated.readText()),
            )
        val reportable = drifts.filter { it.severity != DriftSeverity.SAFE }
        if (reportable.isEmpty()) {
            logger.info("codec schema: no wire-significant drift")
            return
        }

        logger.warn(renderReport(reportable, failOnBreaking.get()))

        val breaking = reportable.filter { it.severity == DriftSeverity.BREAKING }
        if (failOnBreaking.get() && breaking.isNotEmpty()) {
            throw GradleException(
                "codec schema drift is breaking wire compatibility (${breaking.size} " +
                    "${if (breaking.size == 1) "delta" else "deltas"}); see the warnings above. If the " +
                    "change is intentional, run updateCodecSchema to accept the new baseline.",
            )
        }
    }

    /** Group deltas by type, breaking before advisory, into a single multi-line structured warning. */
    private fun renderReport(
        drifts: List<SchemaDrift>,
        failOnBreaking: Boolean,
    ): String =
        buildString {
            val hasBreaking = drifts.any { it.severity == DriftSeverity.BREAKING }
            for ((typeName, typeDrifts) in drifts.groupBy { it.typeName }.toSortedMap()) {
                val severity = if (typeDrifts.any { it.severity == DriftSeverity.BREAKING }) "breaking" else "advisory"
                appendLine("codec schema drift ($severity): $typeName")
                for (drift in typeDrifts.sortedBy { it.severity.ordinal }) {
                    appendLine("  [${drift.severity.name.lowercase()}] ${drift.detail}")
                }
            }
            if (hasBreaking && !failOnBreaking) {
                append("(set codecSchema.failOnBreaking = true to make breaking drift fail the build)")
            } else if (drifts.all { it.severity == DriftSeverity.ADVISORY }) {
                append("(advisory drift never fails the build; run updateCodecSchema to accept intentional renames)")
            }
        }.trimEnd('\n')
}
