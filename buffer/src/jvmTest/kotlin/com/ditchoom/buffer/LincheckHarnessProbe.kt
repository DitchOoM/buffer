package com.ditchoom.buffer

import org.jetbrains.lincheck.LincheckAssertionError
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Proves Lincheck's instrumentation is actually live in this module, by model checking a counter
 * that is definitely broken and asserting that it is caught.
 *
 * ## Why a test whose subject is the test framework
 *
 * Lincheck fails *open*. When its bytecode instrumentation cannot hook a class it logs
 * `Unable to transform <class>, proceeding without instrumentation` to stderr, explores nothing, and
 * reports the run as passing. Gradle surfaces a green check. Nothing distinguishes "no interleaving
 * violates the invariant" from "no interleaving was ever tried".
 *
 * That is not hypothetical here. Kover's coverage agent instruments the test JVM, and Lincheck
 * cannot analyse classes it has already rewritten: every one of the 22 classes touched by
 * [PooledBufferLincheckTest] failed with `IndexOutOfBoundsException` inside ASM's `AnalyzerAdapter`,
 * and the suite passed in 1.5s having model checked nothing. `:buffer:lincheckTest` exists to run
 * without that agent — this probe is what makes the difference observable rather than trusted.
 *
 * If this test ever fails, Lincheck stopped detecting a guaranteed bug: **every other Lincheck
 * result in this module is void until it passes again**, whatever they report.
 */
class LincheckHarnessProbe {
    /** Plain `Int`, incremented non-atomically: two threads must be able to lose an update. */
    private var counter = 0

    @Operation
    fun increment(): Int = ++counter

    @Operation
    fun get(): Int = counter

    @Test
    fun lincheckDetectsAGuaranteedRace() {
        assertFailsWith<LincheckAssertionError>(
            "Lincheck passed a non-atomic counter under 2 concurrent incrementers. Its " +
                "instrumentation is inert — check stderr for 'Unable to transform', and confirm " +
                "no coverage or bytecode agent is attached to this task's JVM.",
        ) {
            ModelCheckingOptions()
                .threads(2)
                .actorsPerThread(2)
                .check(this::class)
        }
    }
}
