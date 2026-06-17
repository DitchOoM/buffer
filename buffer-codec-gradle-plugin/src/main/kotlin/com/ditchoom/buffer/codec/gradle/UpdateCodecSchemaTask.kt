package com.ditchoom.buffer.codec.gradle

import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Copies the freshly-generated `codec-schema.txt` over the baseline (SCHEMA_DRIFT.md). The deliberate
 * "I meant to change this" gesture — run after an intentional, reviewed schema change to accept the
 * new wire shape (the analogue of accepting a snapshot).
 */
@DisableCachingByDefault(because = "Overwrites a source-controlled baseline; an explicit accept gesture, never cached")
abstract class UpdateCodecSchemaTask : AbstractCodecSchemaTask() {
    /** The baselined descriptor to overwrite. */
    @get:Internal
    abstract val baseline: RegularFileProperty

    @TaskAction
    fun update() {
        val generated =
            locateDescriptor()
                ?: throw GradleException(
                    "no generated codec-schema.txt found under the KSP output — run a build first " +
                        "(or check that the module has @ProtocolMessage types and the KSP processor applied).",
                )
        val baselineFile = baseline.get().asFile
        baselineFile.parentFile?.mkdirs()
        baselineFile.writeText(generated.readText())
        logger.lifecycle("updated codec schema baseline at ${baselineFile.path}")
    }
}
