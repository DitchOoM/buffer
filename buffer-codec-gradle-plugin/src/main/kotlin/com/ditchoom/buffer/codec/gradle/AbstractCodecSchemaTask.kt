package com.ditchoom.buffer.codec.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * Shared plumbing for [CheckCodecSchemaTask] and [UpdateCodecSchemaTask]: locating the
 * freshly-generated `codec-schema.txt` among the KSP output directories.
 *
 * The descriptor is emitted by the processor into the KSP generated-resources tree
 * (`build/generated/ksp/<target>/<sourceSet>/resources/codec-schema.txt`). Rather than hardcode the
 * `metadata/commonMain` subpath — which shifts with the KMP source-set layout — [generatedRoots]
 * points at the `build/generated/ksp` root and the file is discovered within it, so the resolution
 * survives layout changes. The tasks `dependsOn` the `ksp*` tasks (wired in [CodecSchemaPlugin]) so
 * the descriptor is regenerated before either task runs.
 */
@DisableCachingByDefault(because = "Schema diffing is fast and reads a source-controlled baseline; caching adds no value")
abstract class AbstractCodecSchemaTask : DefaultTask() {
    /**
     * The KSP generated-output root(s) to scan for `codec-schema.txt`. Wired to
     * `build/generated/ksp` by the plugin.
     */
    @get:InputFiles
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val generatedRoots: ConfigurableFileCollection

    /**
     * Find the single generated descriptor under [generatedRoots], or `null` when none was emitted
     * (no `@ProtocolMessage` types in the module). Multiple source sets may each emit a copy; they
     * are deduplicated by content. Distinct contents across source sets are out of scope for the v1
     * aggregate baseline and fail with an actionable message.
     */
    protected fun locateDescriptor(): File? {
        val matches =
            generatedRoots.asFileTree
                .matching { it.include("**/codec-schema.txt") }
                .files
        if (matches.isEmpty()) return null
        val distinctByContent = matches.groupBy { it.readText() }
        if (distinctByContent.size > 1) {
            throw GradleException(
                "found ${matches.size} differing codec-schema.txt descriptors across KSP source sets " +
                    "(${matches.map { it.path }.sorted().joinToString()}); a single aggregate baseline " +
                    "cannot represent diverging per-source-set schemas. Per-source-set baselines are " +
                    "tracked separately (see SCHEMA_DRIFT.md).",
            )
        }
        // Deterministic pick among identical-content copies.
        return matches.minByOrNull { it.invariantSeparatorsPath }
    }
}
