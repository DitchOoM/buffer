package com.ditchoom.buffer.codec.test.alloc

import com.ditchoom.buffer.codec.test.FrameSizeWorkload
import kotlin.test.Test

/**
 * Kotlin/JS: reports Node's `heapUsed` across 1M `frameSize` calls (10M exceeds the test timeout). A Kotlin/JS `Long`
 * is an object, so this platform is not expected to be allocation-free; the numbers are
 * printed for the record, not asserted. A collection can run inside the loop, so the
 * delta is a lower bound on what was allocated.
 */
class FrameSizeJsHeapTest {
    private fun heapUsed(): Double = js("(typeof process !== 'undefined') ? process.memoryUsage().heapUsed : -1") as Double

    @Test
    fun reportHeapUsedAcrossOneMillionPeeks() {
        val workload = FrameSizeWorkload()
        try {
            workload.run(100_000)
            val before = heapUsed()
            val sink = workload.run(CALLS)
            val after = heapUsed()
            println("FrameSizeJsHeap: $CALLS calls, heapUsed $before -> $after (delta ${after - before} bytes), sink=$sink")
        } finally {
            workload.release()
        }
    }

    private companion object {
        const val CALLS = 1_000_000
    }
}
