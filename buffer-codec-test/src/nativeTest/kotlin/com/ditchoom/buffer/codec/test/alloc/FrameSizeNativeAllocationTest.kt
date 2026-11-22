package com.ditchoom.buffer.codec.test.alloc

import com.ditchoom.buffer.codec.test.FrameSizeWorkload
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import kotlin.native.runtime.GC
import kotlin.native.runtime.GCInfo
import kotlin.native.runtime.MemoryUsage
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Kotlin/Native: `frameSize` allocates nothing per call.
 *
 * Runs only in the release test binary (the `allocation` test run): a debug binary
 * allocates a wrapper per `CPointer` element read, which has nothing to do with the code
 * under test. Each round brackets a batch with `GC.collect()` and reads the bytes
 * allocated between the two collections from the heap the second one found. A round with
 * a collection inside the batch is dropped, since that collection already freed some of
 * the batch. The heap is metered in allocator pages, so a box control proves the meter
 * sees a per-call allocation at this batch size.
 */
@OptIn(NativeRuntimeApi::class, ExperimentalNativeApi::class, ExperimentalStdlibApi::class)
class FrameSizeNativeAllocationTest {
    private val escape = arrayOfNulls<Any>(ESCAPE_SLOTS)

    @Test
    fun frameSizeAllocatesNothingPerCall() {
        assertFalse(Platform.isDebugBinary, "run through the release `allocation` test run")
        val workload = FrameSizeWorkload()
        try {
            workload.run(WARMUP_CALLS)
            val perCall = bytesPerCall(PEEK_CALLS) { workload.run(it) }
            println("FrameSizeNativeAllocation: frameSize=$perCall B/call")
            assertTrue(perCall <= NOISE_BYTES_PER_CALL, "frameSize allocated $perCall B/call")
        } finally {
            workload.release()
        }
    }

    @Test
    fun theMeterSeesAPerCallBox() {
        val perCall =
            bytesPerCall(CONTROL_CALLS) { calls ->
                for (i in 0 until calls) escape[i and (ESCAPE_SLOTS - 1)] = Any()
                calls.toLong()
            }
        println("FrameSizeNativeAllocation: box control=$perCall B/call")
        assertTrue(perCall >= BOXED_BYTES_PER_CALL, "the box control measured only $perCall B/call")
    }

    /** Bytes per call over the rounds, after a first unmetered one, that no collection interrupted. */
    private fun bytesPerCall(
        calls: Int,
        batch: (calls: Int) -> Long,
    ): Double {
        var sink = 0L
        var bytes = 0L
        var cleanRounds = 0
        repeat(ROUNDS + 1) { round ->
            val before = collect()
            sink += batch(calls)
            val after = collect()
            // Round 0 also meters the runtime's one-off pages, so it is not counted.
            if (round > 0 && after.epoch - before.epoch == 1L) {
                bytes += heapBytes(after.memoryUsageBefore) - heapBytes(before.memoryUsageAfter)
                cleanRounds++
            }
        }
        assertTrue(sink != 0L, "the batch must consume its results")
        assertTrue(cleanRounds >= MIN_CLEAN_ROUNDS, "only $cleanRounds of $ROUNDS rounds ran without a collection")
        return bytes.toDouble() / (cleanRounds.toLong() * calls)
    }

    private fun collect(): GCInfo {
        GC.collect()
        return assertNotNull(GC.lastGCInfo, "the runtime reported no collection")
    }

    private fun heapBytes(usage: Map<String, MemoryUsage>): Long = usage.values.sumOf { it.totalObjectsSizeBytes }

    private companion object {
        const val ROUNDS = 8
        const val MIN_CLEAN_ROUNDS = 4
        const val WARMUP_CALLS = 100_000
        const val PEEK_CALLS = 1_000_000
        const val CONTROL_CALLS = 100_000
        const val ESCAPE_SLOTS = 1024

        /** One allocator page (up to 128 KiB) per round is 0.13 B/call; a box is 16 B. */
        const val NOISE_BYTES_PER_CALL = 0.5

        /** The control must see at least half a box per call. */
        const val BOXED_BYTES_PER_CALL = 8.0
    }
}
