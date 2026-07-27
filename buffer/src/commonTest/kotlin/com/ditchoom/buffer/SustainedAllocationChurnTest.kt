package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.fail

/**
 * Isolation harness for the wasmJs out-of-memory failure first seen via PR #324's `toHexString`.
 *
 * The question this answers is whether that failure is a property of the hex conversion or of the
 * allocator underneath it. Every test here does the same thing — allocate a buffer per iteration,
 * write to it, release it — and varies **only which factory** the buffer comes from. No hex, no
 * codecs, no strings.
 *
 * [CHURN_BYTES] x [ITERATIONS] is 287 MB, deliberately above the 256 MB that wasmJs pre-allocates for
 * linear memory, and unremarkable for a GC on every other platform.
 *
 * Runs on all targets on purpose: the pass/fail split across platforms is the result. Originally
 * every row was print-only, because on wasmJs each of these died at exactly 65,536 allocations —
 * `LinearMemoryAllocator` had no deallocation entry point at all, so releasing a buffer reclaimed
 * nothing. They now assert, and are the cross-platform regression gate for that fix.
 *
 * Every row here releases each buffer, which is the contract on a platform without a collector for
 * native memory. The complementary case — allocating without ever releasing, which no collector can
 * rescue on wasmJs — is `LinearMemoryExhaustionTest` in wasmJsTest, where the leak can be cleaned up
 * afterwards instead of poisoning the rest of the suite. Mechanism-level assertions live in
 * `LinearMemoryReclamationTest`.
 */
class SustainedAllocationChurnTest {
    // `allocate` is last so it can be passed as a trailing lambda.
    private fun churn(
        label: String,
        release: (PlatformBuffer) -> Unit = {},
        allocate: (Int) -> PlatformBuffer,
    ) {
        var completed = 0
        var failure: Throwable? = null
        val outcome =
            try {
                while (completed < ITERATIONS) {
                    val buffer = allocate(CHURN_BYTES)
                    buffer.writeByte(1)
                    release(buffer)
                    completed++
                }
                "GREEN survived all $ITERATIONS"
            } catch (
                @Suppress("TooGenericExceptionCaught") t: Throwable,
            ) {
                failure = t
                "RED died after $completed allocations (${(completed.toLong() * CHURN_BYTES) / MIB} MiB): " +
                    (t::class.simpleName ?: "Throwable")
            }
        println("churn[$label] $outcome")
        if (failure != null) fail("churn[$label] $outcome")
    }

    /**
     * The documented explicit-cleanup path. CLAUDE.md promises the memory is "freed immediately when
     * the block exits, no GC needed", so this is the one case that must not depend on a collector.
     * `release` here is exactly what `use { }` runs on scope exit.
     */
    @Test
    fun deterministicFactoryWithUse() =
        churn(
            "BufferFactory.deterministic() + use",
            release = { it.freeNativeMemory() },
        ) { BufferFactory.deterministic().allocate(it) }

    /** Same loop, but explicitly freeing rather than relying on scope exit. */
    @Test
    fun defaultFactoryWithExplicitFree() =
        churn(
            "BufferFactory.Default + freeNativeMemory",
            release = { it.freeNativeMemory() },
        ) { BufferFactory.Default.allocate(it) }

    /** The GC-managed heap alternative. */
    @Test
    fun managedFactory() = churn("BufferFactory.managed()") { BufferFactory.managed().allocate(it) }

    private companion object {
        private const val CHURN_BYTES = 4096
        private const val ITERATIONS = 70_000
        private const val MIB = 1024 * 1024
    }
}
