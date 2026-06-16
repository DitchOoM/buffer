package com.ditchoom.buffer.codec.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.language.base.plugins.LifecycleBasePlugin

/**
 * `com.ditchoom.buffer.codec-schema` — a consumer-facing wire-compatibility gate for buffer-codec
 * (SCHEMA_DRIFT.md).
 *
 * Registers two tasks against the descriptor the KSP processor emits:
 * - **`checkCodecSchema`** — diffs the generated descriptor against the baseline, classifies drift,
 *   warns (and optionally fails on breaking). Wired into `check`.
 * - **`updateCodecSchema`** — overwrites the baseline with the generated descriptor.
 *
 * Both depend on the module's `ksp*` tasks so the descriptor is regenerated before they run, and
 * scan `build/generated/ksp` to locate it (see [AbstractCodecSchemaTask]).
 */
class CodecSchemaPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("codecSchema", CodecSchemaExtension::class.java)
        extension.failOnBreaking.convention(false)
        extension.baseline.convention(
            project.layout.projectDirectory.file("src/codecSchema/codec-schema.txt"),
        )

        val kspRoot = project.layout.buildDirectory.dir("generated/ksp")
        val isKspTask: (Task) -> Boolean = { it.name.startsWith("ksp") }

        val check =
            project.tasks.register("checkCodecSchema", CheckCodecSchemaTask::class.java) { task ->
                task.group = LifecycleBasePlugin.VERIFICATION_GROUP
                task.description = "Checks the generated codec schema descriptor against the baseline for wire drift."
                task.generatedRoots.from(kspRoot)
                task.baseline.set(extension.baseline)
                task.failOnBreaking.set(extension.failOnBreaking)
                task.dependsOn(project.tasks.matching(isKspTask))
            }

        project.tasks.register("updateCodecSchema", UpdateCodecSchemaTask::class.java) { task ->
            task.group = LifecycleBasePlugin.VERIFICATION_GROUP
            task.description = "Overwrites the codec schema baseline with the freshly-generated descriptor."
            task.generatedRoots.from(kspRoot)
            task.baseline.set(extension.baseline)
            task.dependsOn(project.tasks.matching(isKspTask))
        }

        // Wire into `check` whenever a lifecycle-providing plugin (base / KMP / java) is present.
        project.plugins.withType(LifecycleBasePlugin::class.java) {
            project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME).configure { it.dependsOn(check) }
        }
    }
}
