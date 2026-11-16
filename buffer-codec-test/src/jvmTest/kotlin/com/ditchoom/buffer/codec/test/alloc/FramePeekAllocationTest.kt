package com.ditchoom.buffer.codec.test.alloc

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.FramePeek
import com.ditchoom.buffer.codec.FramePeekKind
import com.ditchoom.buffer.codec.test.protocols.simple.WideLengthFrameCodec
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A generated codec overrides `peekFrame`, and calling it allocates nothing:
 * `FramePeek` is a value class returned directly, where `peekFrameSize` boxes a
 * `PeekResult.Complete` on every call.
 *
 * Measured with `ThreadMXBean.getThreadAllocatedBytes` (exact per-thread bytes,
 * unaffected by TLAB rotation).
 */
class FramePeekAllocationTest {
    private fun completeFrameStream(): StreamProcessor {
        // length = 2 (big-endian UInt), flags, then a 2-byte payload.
        val bytes = byteArrayOf(0, 0, 0, 2, 0x07, 'h'.code.toByte(), 'i'.code.toByte())
        val buf = BufferFactory.Default.allocate(bytes.size, ByteOrder.BIG_ENDIAN)
        for (b in bytes) buf.writeByte(b)
        buf.resetForRead()
        return StreamProcessor.create(BufferPool(), ByteOrder.BIG_ENDIAN).also { it.append(buf) }
    }

    @Test
    fun generatedCodecOverridesPeekFrame() {
        val declared = WideLengthFrameCodec::class.java.declaredMethods.map { it.name }
        // The value-class return type mangles the JVM name (`peekFrame-<hash>`).
        assertTrue(declared.any { it.startsWith("peekFrame-") }, "no peekFrame override in $declared")
        assertTrue("peekFrameSize" in declared, "the older peekFrameSize must stay: $declared")
    }

    @Test
    fun generatedPeekFrameAgreesWithPeekFrameSize() {
        val stream = completeFrameStream()
        try {
            val peek = WideLengthFrameCodec.peekFrame(stream)
            assertEquals(FramePeekKind.Complete, peek.kind)
            assertEquals(7, peek.size)
            assertEquals(peek.toPeekResult(), WideLengthFrameCodec.peekFrameSize(stream))
            assertEquals(FramePeek.complete(7), peek)
        } finally {
            stream.release()
        }
    }

    @Test
    fun generatedPeekFrameAllocatesNothingPerCall() {
        val stream = completeFrameStream()
        try {
            val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            val tid = Thread.currentThread().id
            assertTrue(threads.isThreadAllocatedMemorySupported, "per-thread allocation accounting unavailable")
            var sizeSink = 0L
            repeat(WARMUP) { sizeSink += WideLengthFrameCodec.peekFrame(stream).size }

            val before = threads.getThreadAllocatedBytes(tid)
            repeat(CALLS) { sizeSink += WideLengthFrameCodec.peekFrame(stream).size }
            val peekFrameBytes = threads.getThreadAllocatedBytes(tid) - before

            // Reference: the older entry point boxes a PeekResult.Complete per call.
            val beforeOld = threads.getThreadAllocatedBytes(tid)
            repeat(CALLS) { if (WideLengthFrameCodec.peekFrameSize(stream) is com.ditchoom.buffer.codec.PeekResult.Complete) sizeSink++ }
            val peekFrameSizeBytes = threads.getThreadAllocatedBytes(tid) - beforeOld

            println(
                "FramePeekAllocation: peekFrame=${peekFrameBytes.toDouble() / CALLS} B/call, " +
                    "peekFrameSize=${peekFrameSizeBytes.toDouble() / CALLS} B/call (sink=$sizeSink)",
            )
            // Zero per call. The tolerance is a few hundred bytes of one-off JIT/runtime noise
            // across all CALLS, three orders of magnitude under a per-call allocation (a boxed
            // `Complete` is 16 B/call, 1.6 MB here).
            assertTrue(
                peekFrameBytes <= NOISE_BYTES,
                "peekFrame allocated $peekFrameBytes bytes over $CALLS calls (${peekFrameBytes.toDouble() / CALLS} B/call)",
            )
            assertTrue(
                peekFrameSizeBytes >= CALLS * 8L,
                "control failed: the boxing peekFrameSize allocated only $peekFrameSizeBytes bytes, so the meter is blind",
            )
        } finally {
            stream.release()
        }
    }

    private companion object {
        const val WARMUP = 50_000
        const val CALLS = 100_000
        const val NOISE_BYTES = 4_096L
    }
}
