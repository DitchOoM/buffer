@file:Suppress("DEPRECATION") // the controls call the deprecated peekFrameSize / wireSize

package com.ditchoom.buffer.codec.test.alloc

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.Encoder
import com.ditchoom.buffer.codec.FrameDetector
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.UnsignedVarIntCodec
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.test.protocols.enums.Priority
import com.ditchoom.buffer.codec.test.protocols.enums.Tagged
import com.ditchoom.buffer.codec.test.protocols.enums.TaggedCodec
import com.ditchoom.buffer.codec.test.protocols.lengthprefixedusecodec.PropertyEntry
import com.ditchoom.buffer.codec.test.protocols.lengthprefixedusecodec.PropertyEntryCodec
import com.ditchoom.buffer.codec.test.protocols.simple.BytePrefixedString
import com.ditchoom.buffer.codec.test.protocols.simple.BytePrefixedStringCodec
import com.ditchoom.buffer.codec.test.protocols.simple.WideLengthFrame
import com.ditchoom.buffer.codec.test.protocols.simple.WideLengthFrameCodec
import com.ditchoom.buffer.codec.test.protocols.varintfield.VarintLengthFrame
import com.ditchoom.buffer.codec.test.protocols.varintfield.VarintLengthFrameCodec
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `frameSize` and `wireBytes` allocate nothing per call, through a megamorphic call site.
 *
 * Several codec classes go through one call site, so the JIT cannot inline a single
 * target and then scalar-replace a box: the zero has to hold by construction. The same
 * sites calling the deprecated `peekFrameSize` / `wireSize` are the controls: they box a
 * `PeekResult.Complete` / `WireSize.Exact` per call, which proves the meter sees
 * allocations at these sites.
 *
 * Measured with `ThreadMXBean.getThreadAllocatedBytes` (exact per-thread bytes).
 */
class FrameSizeAllocationTest {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private fun <T> streamOf(
        codec: Codec<T>,
        value: T,
    ): StreamProcessor {
        val buf = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
        codec.encode(buf, value, EncodeContext.Empty)
        buf.resetForRead()
        return StreamProcessor.create(BufferPool(), ByteOrder.BIG_ENDIAN).also { it.append(buf) }
    }

    private fun bytesPerCall(run: (calls: Int) -> Long): Double {
        assertTrue(threads.isThreadAllocatedMemorySupported, "per-thread allocation accounting unavailable")
        val tid = Thread.currentThread().id
        var sink = 0L
        repeat(WARMUP_ROUNDS) { sink += run(CALLS) }
        val before = threads.getThreadAllocatedBytes(tid)
        sink += run(CALLS)
        val bytes = threads.getThreadAllocatedBytes(tid) - before
        assertTrue(sink != 0L, "the loop must have consumed its results")
        return bytes.toDouble() / CALLS
    }

    @Test
    fun receiverFrameSizeAllocatesNothingAcrossCodecClasses() {
        val detectors: Array<FrameDetector> =
            arrayOf(WideLengthFrameCodec, BytePrefixedStringCodec, VarintLengthFrameCodec, TaggedCodec)
        val streams =
            arrayOf(
                streamOf(WideLengthFrameCodec, WideLengthFrame(2u, 7u, "hi")),
                streamOf(BytePrefixedStringCodec, BytePrefixedString("hello")),
                streamOf(VarintLengthFrameCodec, VarintLengthFrame(300uL, 9u)),
                streamOf(TaggedCodec, Tagged(Priority.High, 3u)),
            )
        try {
            val frameSize =
                bytesPerCall { calls ->
                    var sink = 0L
                    for (i in 0 until calls) {
                        val k = i and 3
                        sink += detectors[k].frameSize(streams[k], 0).fold({ it }, { -1 }, { -2 })
                    }
                    sink
                }
            val peekFrameSize =
                bytesPerCall { calls ->
                    var sink = 0L
                    for (i in 0 until calls) {
                        val k = i and 3
                        val r = detectors[k].peekFrameSize(streams[k], 0)
                        if (r is PeekResult.Complete) sink += r.bytes
                    }
                    sink
                }
            println("FrameSizeAllocation receive: frameSize=$frameSize B/call, peekFrameSize=$peekFrameSize B/call")
            assertTrue(frameSize <= NOISE_BYTES_PER_CALL, "frameSize allocated $frameSize B/call")
            assertTrue(peekFrameSize >= BOXED_BYTES_PER_CALL, "control: peekFrameSize allocated $peekFrameSize B/call")
        } finally {
            streams.forEach { it.release() }
        }
    }

    /** Overridden with a width-only scan, so no `VarLenPeek.Decoded` exists to allocate. */
    @Test
    fun theLibraryVarintFrameSizeAllocatesNothing() {
        val streams = arrayOf(streamOf(UnsignedVarIntCodec, 70_000u), streamOf(UnsignedVarIntCodec, 5u))
        try {
            val detector: FrameDetector = UnsignedVarIntCodec
            assertEquals(FrameSize.declared(3), detector.frameSize(streams[0]))
            val perCall =
                bytesPerCall { calls ->
                    var sink = 0L
                    for (i in 0 until calls) {
                        sink += detector.frameSize(streams[i and 1], 0).fold({ it }, { -1 }, { -2 })
                    }
                    sink
                }
            assertTrue(perCall <= NOISE_BYTES_PER_CALL, "UnsignedVarIntCodec.frameSize allocated $perCall B/call")
        } finally {
            streams.forEach { it.release() }
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun senderWireBytesAllocatesNothingAcrossCodecClasses() {
        val encoders: Array<Encoder<Any?>> =
            arrayOf<Encoder<*>>(WideLengthFrameCodec, TaggedCodec, VarintLengthFrameCodec, PropertyEntryCodec)
                .map { it as Encoder<Any?> }
                .toTypedArray()
        val values: Array<Any?> =
            arrayOf(
                WideLengthFrame(2u, 7u, "hi"),
                Tagged(Priority.High, 3u),
                VarintLengthFrame(300uL, 9u),
                PropertyEntry(1u, 2u),
            )
        val context = EncodeContext.Empty
        for (k in 0..3) {
            val size = encoders[k].wireBytes(values[k], context)
            assertTrue(size != WireByteCount.BackPatch, "values[$k] has an exact size")
        }
        val wireBytes =
            bytesPerCall { calls ->
                var sink = 0L
                for (i in 0 until calls) {
                    val k = i and 3
                    sink += encoders[k].wireBytes(values[k], context).fold(exact = { it }, backPatch = { -1 })
                }
                sink
            }
        val wireSize =
            bytesPerCall { calls ->
                var sink = 0L
                for (i in 0 until calls) {
                    val k = i and 3
                    val w = encoders[k].wireSize(values[k], context)
                    if (w is WireSize.Exact) sink += w.bytes
                }
                sink
            }
        println("FrameSizeAllocation send: wireBytes=$wireBytes B/call, wireSize=$wireSize B/call")
        assertTrue(wireBytes <= NOISE_BYTES_PER_CALL, "wireBytes allocated $wireBytes B/call")
        assertTrue(wireSize >= BOXED_BYTES_PER_CALL, "control: wireSize allocated only $wireSize B/call")
    }

    @Test
    fun generatedCodecsOverrideBothTheNewAndTheDeprecatedMethods() {
        val declared = WideLengthFrameCodec::class.java.declaredMethods.map { it.name }
        // A value-class return type mangles the JVM name (`frameSize-<hash>`).
        assertTrue(declared.any { it.startsWith("frameSize-") }, "no frameSize override in $declared")
        assertTrue("peekFrameSize" in declared, "the deprecated peekFrameSize forwarder must stay: $declared")
        assertTrue(declared.any { it.startsWith("wireBytes-") }, "no wireBytes override in $declared")
        assertTrue("wireSize" in declared, "the deprecated wireSize forwarder must stay: $declared")
    }

    private companion object {
        const val WARMUP_ROUNDS = 5
        const val CALLS = 200_000

        /** One-off JIT/runtime noise spread over [CALLS]; a per-call box is 16 B. */
        const val NOISE_BYTES_PER_CALL = 0.05

        /** The controls must see at least half a box per call. */
        const val BOXED_BYTES_PER_CALL = 8.0
    }
}
