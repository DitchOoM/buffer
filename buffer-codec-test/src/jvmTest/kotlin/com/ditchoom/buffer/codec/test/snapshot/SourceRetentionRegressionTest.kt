package com.ditchoom.buffer.codec.test.snapshot

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards that codegen-only annotations carrying a `KClass` argument stay
 * `@Retention(SOURCE)`, so neither the annotation nor its (possibly nested)
 * `KClass` arg leaks into the `.class` file **or** Kotlin's `@Metadata`.
 *
 * Why it matters: Kotlin 2.4 copies annotations into `@Metadata` by default
 * (annotations-in-metadata). A **nested** `KClass` arg — e.g.
 * `@ForwardCompatible(unknown = ForwardCompatibleOp.Unknown::class)` — is stored
 * there under its dotted Kotlin name (`ForwardCompatibleOp.Unknown`), which
 * proguard-core 9.3.2 (the latest, as of this writing) cannot map back to the
 * JVM name `ForwardCompatibleOp$Unknown`. It reports a bogus "can't find
 * referenced class" and **aborts the shrink** of any consumer applying the
 * annotation to a nested sink — a false positive, since the class is present and
 * kept by ordinary bytecode refs. `@Retention(SOURCE)` removes the annotation
 * (and its KClass arg) from both bytecode and metadata, so codegen is unchanged
 * (KSP still reads it off the same-round source symbol) and the shrink succeeds.
 *
 * `ForwardCompatibleOp` is the witness: it applies all three lifted annotations —
 * `@ForwardCompatible(unknown = ForwardCompatibleOp.Unknown::class)` (a *nested*
 * KClass arg), `@DispatchOn(OpCode::class)`, and
 * `@FramedBy(MqttRemainingLengthCodec::class)`.
 *
 * Reflection cannot distinguish SOURCE from BINARY here (both are non-RUNTIME, so
 * invisible to `Class.getAnnotations()`), so this scans the raw class bytes for
 * the annotation type's internal name, which appears in the constant pool iff the
 * annotation survived to bytecode or `@Metadata`.
 */
class SourceRetentionRegressionTest {
    private val witness =
        "com.ditchoom.buffer.codec.test.protocols.forwardcompat.ForwardCompatibleOp"

    /** Internal-name fragments of the codegen-only annotations that must NOT survive. */
    private val sourceOnlyAnnotations =
        listOf(
            "codec/annotations/ForwardCompatible",
            "codec/annotations/DispatchOn",
            "codec/annotations/FramedBy",
        )

    private fun classText(binaryName: String): String {
        val resource = binaryName.replace('.', '/') + ".class"
        val bytes =
            javaClass.classLoader.getResourceAsStream(resource)?.use { it.readBytes() }
                ?: error("class not on test classpath: $resource")
        // ISO-8859-1 is a 1:1 byte->char mapping, so substring scans are exact over raw bytes.
        return bytes.toString(Charsets.ISO_8859_1)
    }

    @Test
    fun codegenOnlyAnnotationsDoNotLeakIntoBytecodeOrMetadata() {
        val text = classText(witness)
        for (annotation in sourceOnlyAnnotations) {
            assertFalse(
                text.contains(annotation),
                "$annotation must be @Retention(SOURCE): its descriptor was found in " +
                    "$witness.class (bytecode or @Metadata). BINARY retention re-embeds the " +
                    "annotation's (nested) KClass arg, which proguard-core 9.3.2 cannot resolve — " +
                    "aborting consumer ProGuard/R8 shrinks. Keep it @Retention(SOURCE).",
            )
        }
    }

    /**
     * Sanity check that the scan is not vacuously green: a BINARY-retained,
     * KClass-argument annotation that IS expected on the wire — `@DispatchValue`,
     * read cross-module off the resolved discriminator, so it must stay BINARY —
     * still appears in the discriminator value class's bytecode. If this ever
     * disappears the scan technique is broken and the assertions above are worthless.
     */
    @Test
    fun scanDetectsAnAnnotationThatIsLegitimatelyRetained() {
        val opCode = "com.ditchoom.buffer.codec.test.protocols.forwardcompat.OpCode"
        assertTrue(
            classText(opCode).contains("codec/annotations/DispatchValue"),
            "expected @DispatchValue (BINARY, read cross-module) to be present in $opCode.class — " +
                "if absent, the raw-bytes scan no longer detects retained annotations and the " +
                "SOURCE-retention assertions in this class are not actually testing anything.",
        )
    }
}
