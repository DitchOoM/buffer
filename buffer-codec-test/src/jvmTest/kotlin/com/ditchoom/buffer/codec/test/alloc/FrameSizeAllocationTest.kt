package com.ditchoom.buffer.codec.test.alloc

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.Encoder
import com.ditchoom.buffer.codec.FrameDetector
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.UnsignedVarIntCodec
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.test.protocols.enums.Priority
import com.ditchoom.buffer.codec.test.protocols.enums.Tagged
import com.ditchoom.buffer.codec.test.protocols.enums.TaggedCodec
import com.ditchoom.buffer.codec.test.protocols.http3.Http3FrameCodec
import com.ditchoom.buffer.codec.test.protocols.http3.Http3FrameSettingsCodec
import com.ditchoom.buffer.codec.test.protocols.http3fc.Http3FcFrameCodec
import com.ditchoom.buffer.codec.test.protocols.lengthprefixedusecodec.PropertyBagFrame
import com.ditchoom.buffer.codec.test.protocols.lengthprefixedusecodec.PropertyBagFrameCodec
import com.ditchoom.buffer.codec.test.protocols.lengthprefixedusecodec.PropertyEntry
import com.ditchoom.buffer.codec.test.protocols.lengthprefixedusecodec.PropertyEntryCodec
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttPacketCodec
import com.ditchoom.buffer.codec.test.protocols.quic.QuicVarintCodec
import com.ditchoom.buffer.codec.test.protocols.simple.BytePrefixedStringCodec
import com.ditchoom.buffer.codec.test.protocols.simple.WideLengthFrame
import com.ditchoom.buffer.codec.test.protocols.simple.WideLengthFrameCodec
import com.ditchoom.buffer.codec.test.protocols.usecodecscalar.BoundedFrameCodec
import com.ditchoom.buffer.codec.test.protocols.varintfield.VarintLengthFrame
import com.ditchoom.buffer.codec.test.protocols.varintfield.VarintLengthFrameCodec
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `frameSize` and `wireBytes` allocate nothing per call, through megamorphic call sites.
 *
 * Several codec classes go through one call site, so the JIT cannot inline a single
 * target. The build also runs this class with escape analysis off
 * (`jvmTestNoEscapeAnalysis`, `-XX:-DoEscapeAnalysis`), so a zero here cannot come from
 * scalar replacement: it holds by construction. Each site has a control that stores the
 * result into an array, which forces a box, proving the meter sees allocations there.
 *
 * Measured with `ThreadMXBean.getThreadAllocatedBytes` (exact per-thread bytes).
 */
class FrameSizeAllocationTest {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    private val escape = arrayOfNulls<Any>(8)

    private fun streamOf(vararg bytes: Int): StreamProcessor {
        val buf = BufferFactory.Default.allocate(bytes.size, ByteOrder.BIG_ENDIAN)
        for (b in bytes) buf.writeByte(b.toByte())
        buf.resetForRead()
        return StreamProcessor.create(BufferPool(), ByteOrder.BIG_ENDIAN).also { it.append(buf) }
    }

    private fun <T> streamOf(
        codec: Encoder<T>,
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

    private fun total(size: FrameSize): Long = size.foldTotal({ it }, { -1 }, { -2 })

    /** One frameSize site over [detectors], plus its boxing control. */
    private fun assertPeeksAllocateNothing(
        label: String,
        detectors: Array<FrameDetector>,
        streams: Array<StreamProcessor>,
    ) {
        val n = detectors.size
        for (k in 0 until n) {
            assertTrue(total(detectors[k].frameSize(streams[k])) > 0, "$label[$k] must declare a size")
        }
        val perCall =
            bytesPerCall { calls ->
                var sink = 0L
                for (i in 0 until calls) {
                    val k = i % n
                    sink += total(detectors[k].frameSize(streams[k], 0))
                }
                sink
            }
        val control =
            bytesPerCall { calls ->
                var sink = 0L
                for (i in 0 until calls) {
                    val k = i % n
                    val size = detectors[k].frameSize(streams[k], 0)
                    escape[i and 7] = size // boxes: proves the meter sees this site
                    sink += total(size)
                }
                sink
            }
        println("FrameSizeAllocation $label: frameSize=$perCall B/call, boxing control=$control B/call")
        assertTrue(perCall <= NOISE_BYTES_PER_CALL, "$label: frameSize allocated $perCall B/call")
        assertTrue(control >= BOXED_BYTES_PER_CALL, "$label: control allocated only $control B/call")
    }

    @Test
    fun generatedLengthDrivenPeeksAllocateNothing() {
        val streams =
            arrayOf(
                streamOf(WideLengthFrameCodec, WideLengthFrame(2u, 7u, "hi")),
                streamOf(0x05, 'h'.code, 'e'.code, 'l'.code, 'l'.code, 'o'.code),
                streamOf(VarintLengthFrameCodec, VarintLengthFrame(300uL, 9u)),
                streamOf(TaggedCodec, Tagged(Priority.High, 3u)),
            )
        try {
            assertPeeksAllocateNothing(
                "generated walk/varint/enum",
                arrayOf(WideLengthFrameCodec, BytePrefixedStringCodec, VarintLengthFrameCodec, TaggedCodec),
                streams,
            )
        } finally {
            streams.forEach { it.release() }
        }
    }

    @Test
    fun framedByAndBoundingPeeksAllocateNothing() {
        val streams =
            arrayOf(
                // HTTP/3-style @FramedBy dispatcher: type 0x00 (DATA), QUIC-varint length 3, body.
                streamOf(0x00, 0x03, 1, 2, 3),
                // MQTT-style @FramedBy packet: PINGREQ header byte, remaining length 0.
                streamOf(0xC0, 0x00),
                // Bounding @UseCodec after fixed fields: tag, 4-byte LE length 2, body.
                streamOf(0x00, 0x01, 0x02, 0x00, 0x00, 0x00, 9, 9),
                // Bounding @UseCodec after a varint prior: HTTP/3 SETTINGS, length 2, body.
                streamOf(0x04, 0x02, 1, 2),
            )
        try {
            assertPeeksAllocateNothing(
                "@FramedBy + bounding",
                arrayOf(Http3FcFrameCodec, MqttPacketCodec, BoundedFrameCodec, Http3FrameSettingsCodec),
                streams,
            )
        } finally {
            streams.forEach { it.release() }
        }
    }

    @Test
    fun theUnframedVarintDispatcherPeeksWithoutAllocating() {
        val streams =
            arrayOf(
                // DATA: type 0x00 (1-byte varint), length 3, body.
                streamOf(0x00, 0x03, 1, 2, 3),
                // Extension: type 64 (2-byte varint 0x40 0x40), length 1, body.
                streamOf(0x40, 0x40, 0x01, 7),
                // HEADERS: type 0x01, length 2, body.
                streamOf(0x01, 0x02, 4, 5),
            )
        try {
            assertPeeksAllocateNothing(
                "varint dispatcher",
                arrayOf(Http3FrameCodec, Http3FrameCodec, Http3FrameCodec),
                streams,
            )
        } finally {
            streams.forEach { it.release() }
        }
    }

    @Test
    fun theVarintCodecsDeclareTheirWidthWithoutAllocating() {
        val streams = arrayOf(streamOf(UnsignedVarIntCodec, 70_000u), streamOf(QuicVarintCodec, 300uL))
        try {
            assertEquals(FrameSize.declared(0, 3), UnsignedVarIntCodec.frameSize(streams[0]))
            assertEquals(FrameSize.declared(0, 2), QuicVarintCodec.frameSize(streams[1]))
            assertPeeksAllocateNothing("varint codecs", arrayOf(UnsignedVarIntCodec, QuicVarintCodec), streams)
        } finally {
            streams.forEach { it.release() }
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun senderWireBytesAllocatesNothingAcrossCodecClasses() {
        val encoders: Array<Encoder<Any?>> =
            arrayOf<Encoder<*>>(
                WideLengthFrameCodec,
                TaggedCodec,
                VarintLengthFrameCodec,
                PropertyEntryCodec,
                PropertyBagFrameCodec,
            ).map { it as Encoder<Any?> }
                .toTypedArray()
        val values: Array<Any?> =
            arrayOf(
                WideLengthFrame(2u, 7u, "hi"),
                Tagged(Priority.High, 3u),
                VarintLengthFrame(300uL, 9u),
                PropertyEntry(1u, 2u),
                PropertyBagFrame(listOf(PropertyEntry(1u, 2u), PropertyEntry(3u, 4u))),
            )
        val context = EncodeContext.Empty
        val n = encoders.size
        for (k in 0 until n) {
            val size = encoders[k].wireBytes(values[k], context)
            assertTrue(size != WireByteCount.BackPatch, "values[$k] has an exact size")
        }
        val perCall =
            bytesPerCall { calls ->
                var sink = 0L
                for (i in 0 until calls) {
                    val k = i % n
                    sink += encoders[k].wireBytes(values[k], context).fold({ it }, { -1 })
                }
                sink
            }
        val control =
            bytesPerCall { calls ->
                var sink = 0L
                for (i in 0 until calls) {
                    val k = i % n
                    val size = encoders[k].wireBytes(values[k], context)
                    escape[i and 7] = size
                    sink += size.fold({ it }, { -1 })
                }
                sink
            }
        println("FrameSizeAllocation send: wireBytes=$perCall B/call, boxing control=$control B/call")
        assertTrue(perCall <= NOISE_BYTES_PER_CALL, "wireBytes allocated $perCall B/call")
        assertTrue(control >= BOXED_BYTES_PER_CALL, "control allocated only $control B/call")
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
