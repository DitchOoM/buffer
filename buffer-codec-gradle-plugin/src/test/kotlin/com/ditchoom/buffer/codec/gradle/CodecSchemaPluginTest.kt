package com.ditchoom.buffer.codec.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for the `com.ditchoom.buffer.codec-schema` plugin (SCHEMA_DRIFT.md test
 * obligations). These drive the plugin through Gradle TestKit against a pre-staged descriptor in
 * the KSP output tree, so they exercise descriptor location, baseline creation, drift
 * classification, and the `failOnBreaking` escalation without paying a full KMP+KSP compile (the
 * end-to-end emit is covered by the processor's `CodecSchemaDescriptorCodegenTest`, and the full
 * chain by the `buffer-codec-test` dogfood).
 */
class CodecSchemaPluginTest {
    private val projectDir = Files.createTempDirectory("codec-schema-plugin-test").toFile()

    @AfterTest
    fun cleanup() {
        projectDir.deleteRecursively()
    }

    private val baseDescriptor =
        """
        enum p.Color default=Unknown
          0 Unknown
          1 Red
          2 Green
        """.trimIndent() + "\n"

    /** Reorder ordinals 1 and 2 — both names persist at swapped ordinals → BREAKING. */
    private val reorderedDescriptor =
        """
        enum p.Color default=Unknown
          0 Unknown
          1 Green
          2 Red
        """.trimIndent() + "\n"

    /** Rename ordinal 1 (Red → Crimson), no other entry takes 'Red' → ADVISORY. */
    private val renamedDescriptor =
        """
        enum p.Color default=Unknown
          0 Unknown
          1 Crimson
          2 Green
        """.trimIndent() + "\n"

    @Test
    fun `no baseline creates one and warns to commit it`() {
        writeProject(generated = baseDescriptor)

        val result = run("checkCodecSchema")

        assertEquals(TaskOutcome.SUCCESS, result.task(":checkCodecSchema")?.outcome)
        assertTrue(result.output.contains("baseline created"), "should announce baseline creation")
        assertEquals(baseDescriptor, baselineFile().readText(), "baseline must equal the generated descriptor")
    }

    @Test
    fun `matching schema passes silently`() {
        writeProject(generated = baseDescriptor, baseline = baseDescriptor)

        val result = run("checkCodecSchema")

        assertEquals(TaskOutcome.SUCCESS, result.task(":checkCodecSchema")?.outcome)
        assertTrue(!result.output.contains("codec schema drift"), "no drift should be reported")
    }

    @Test
    fun `breaking drift warns but passes by default`() {
        writeProject(generated = reorderedDescriptor, baseline = baseDescriptor)

        val result = run("checkCodecSchema")

        assertEquals(TaskOutcome.SUCCESS, result.task(":checkCodecSchema")?.outcome)
        assertTrue(result.output.contains("codec schema drift (breaking): p.Color"), "breaking drift reported")
        assertTrue(result.output.contains("failOnBreaking = true"), "should hint at the opt-in escalation")
    }

    @Test
    fun `breaking drift fails the build under failOnBreaking`() {
        writeProject(generated = reorderedDescriptor, baseline = baseDescriptor, failOnBreaking = true)

        val result = runAndFail("checkCodecSchema")

        assertEquals(TaskOutcome.FAILED, result.task(":checkCodecSchema")?.outcome)
        assertTrue(result.output.contains("codec schema drift (breaking): p.Color"), "breaking drift reported")
        assertTrue(result.output.contains("breaking wire compatibility"), "build failure message present")
    }

    @Test
    fun `advisory rename warns but never fails even under failOnBreaking`() {
        writeProject(generated = renamedDescriptor, baseline = baseDescriptor, failOnBreaking = true)

        val result = run("checkCodecSchema")

        assertEquals(TaskOutcome.SUCCESS, result.task(":checkCodecSchema")?.outcome)
        assertTrue(result.output.contains("codec schema drift (advisory): p.Color"), "advisory drift reported")
        assertTrue(result.output.contains("[advisory]"), "advisory severity tag present")
    }

    @Test
    fun `updateCodecSchema rewrites the baseline`() {
        writeProject(generated = reorderedDescriptor, baseline = baseDescriptor)

        val result = run("updateCodecSchema")

        assertEquals(TaskOutcome.SUCCESS, result.task(":updateCodecSchema")?.outcome)
        assertEquals(reorderedDescriptor, baselineFile().readText(), "baseline must be overwritten with generated")
    }

    @Test
    fun `checkCodecSchema is wired into check`() {
        writeProject(generated = baseDescriptor, baseline = baseDescriptor)

        val result = run("check")

        assertEquals(TaskOutcome.SUCCESS, result.task(":checkCodecSchema")?.outcome, "check must run checkCodecSchema")
    }

    // ---- fixtures ---------------------------------------------------------

    private fun baselineFile() = File(projectDir, "src/codecSchema/codec-schema.txt")

    private fun writeProject(
        generated: String? = null,
        baseline: String? = null,
        failOnBreaking: Boolean = false,
    ) {
        File(projectDir, "settings.gradle.kts").writeText("rootProject.name = \"codec-schema-fixture\"\n")
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                base
                id("com.ditchoom.buffer.codec-schema")
            }

            codecSchema {
                failOnBreaking.set($failOnBreaking)
            }
            """.trimIndent() + "\n",
        )
        if (generated != null) {
            // Mimic the processor's KSP output location for common types.
            val out = File(projectDir, "build/generated/ksp/metadata/commonMain/resources")
            out.mkdirs()
            File(out, "codec-schema.txt").writeText(generated)
        }
        if (baseline != null) {
            baselineFile().parentFile.mkdirs()
            baselineFile().writeText(baseline)
        }
    }

    private fun run(vararg args: String) = gradleRunner(*args).build()

    private fun runAndFail(vararg args: String) = gradleRunner(*args).buildAndFail()

    private fun gradleRunner(vararg args: String): GradleRunner =
        GradleRunner
            .create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*args, "--stacktrace")
            .forwardOutput()
}
