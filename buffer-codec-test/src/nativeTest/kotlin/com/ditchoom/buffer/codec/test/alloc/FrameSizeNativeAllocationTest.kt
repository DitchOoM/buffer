package com.ditchoom.buffer.codec.test.alloc

import com.ditchoom.buffer.codec.test.FrameSizeWorkload
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Kotlin/Native: 10M `frameSize` calls leave no garbage. A forced collection before and
 * after the loop brackets it; if the loop allocated, the heap seen by the second
 * collection grows (16 B a call would be ~160 MB) or a collection runs inside the loop.
 */
@OptIn(NativeRuntimeApi::class, ExperimentalStdlibApi::class)
class FrameSizeNativeAllocationTest {
    @Test
    fun tenMillionPeeksLeaveNoGarbage() {
        val workload = FrameSizeWorkload()
        try {
            workload.run(100_000) // warm up
            GC.collect()
            val before = GC.lastGCInfo ?: error("no GC info")
            val sink = workload.run(CALLS)
            GC.collect()
            val after = GC.lastGCInfo ?: error("no GC info")
            val liveBefore = before.memoryUsageAfter.values.sumOf { it.totalObjectsSizeBytes }
            val heapAtSecondCollect = after.memoryUsageBefore.values.sumOf { it.totalObjectsSizeBytes }
            val grown = heapAtSecondCollect - liveBefore
            println(
                "FrameSizeNativeAllocation: $CALLS calls, heap grew $grown bytes " +
                    "(${grown.toDouble() / CALLS} B/call), collections in between ${after.epoch - before.epoch - 1} (sink=$sink)",
            )
            assertEquals(1L, after.epoch - before.epoch, "a collection ran during the loop, so it allocated")
            assertTrue(grown < BUDGET_BYTES, "the loop left $grown bytes of garbage")
        } finally {
            workload.release()
        }
    }

    @Test
    fun perShapeDiagnostics() {
        val workload = FrameSizeWorkload()
        try {
            fun epochs(block: () -> Long): Long {
                GC.collect()
                val e0 = GC.lastGCInfo!!.epoch
                block()
                GC.collect()
                return GC.lastGCInfo!!.epoch - e0 - 1
            }
            println("DIAG peekByte: " + epochs { workload.runPeekByte(0, 2_000_000) })
            for (k in 0 until workload.size) println("DIAG shape $k: " + epochs { workload.runOne(k, 2_000_000) })
        } finally {
            workload.release()
        }
    }

    private companion object {
        const val CALLS = 10_000_000

        /** Under 0.01 B a call: runtime noise, three orders of magnitude below one box per call. */
        const val BUDGET_BYTES = 100_000L
    }
}
