package com.ditchoom.buffer.codec.test.alloc

import com.ditchoom.buffer.codec.test.FrameSizeWorkload
import kotlin.test.Test

/**
 * Kotlin/JS: reports Node's `heapUsed` across 100k `frameSize` calls, a batch that fits
 * the test timeout on CI runners. A Kotlin/JS `Long` is an object, so this platform is not
 * expected to be allocation-free; the numbers are printed for the record, not asserted. A
 * collection can run inside the loop, so the delta is a lower bound on what was allocated.
 */
class FrameSizeJsHeapTest {
    /** Node's `heapUsed`, or -1 outside Node. */
    private fun heapUsed(): Double {
        val bytes: Double = js("(typeof process !== 'undefined') ? process.memoryUsage().heapUsed : -1")
        return bytes
    }

    @Test
    fun reportHeapUsedAcrossPeeks() {
        val workload = FrameSizeWorkload()
        try {
            workload.run(WARMUP_CALLS)
            val before = heapUsed()
            val sink = workload.run(CALLS)
            val after = heapUsed()
            println("FrameSizeJsHeap: $CALLS calls, heapUsed $before -> $after (delta ${after - before} B), sink=$sink")
        } finally {
            workload.release()
        }
    }

    private companion object {
        const val WARMUP_CALLS = 10_000
        const val CALLS = 100_000
    }
}
