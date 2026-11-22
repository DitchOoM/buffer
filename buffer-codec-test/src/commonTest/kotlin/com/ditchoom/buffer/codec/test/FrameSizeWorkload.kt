package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.FrameDetector
import com.ditchoom.buffer.codec.test.protocols.http3.Http3FrameCodec
import com.ditchoom.buffer.codec.test.protocols.http3.Http3FrameSettingsCodec
import com.ditchoom.buffer.codec.test.protocols.http3fc.Http3FcFrameCodec
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttPacketCodec
import com.ditchoom.buffer.codec.test.protocols.simple.BytePrefixedStringCodec
import com.ditchoom.buffer.codec.test.protocols.usecodecscalar.BoundedFrameCodec
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor

/**
 * The platform allocation checks' workload: `frameSize` over six generated codec shapes
 * (`@FramedBy` dispatcher and packet, two bounding `@UseCodec` shapes, a length-prefixed
 * walk, an unframed varint dispatcher) through one call site.
 */
class FrameSizeWorkload {
    private val detectors: Array<FrameDetector> =
        arrayOf(
            Http3FcFrameCodec,
            MqttPacketCodec,
            BoundedFrameCodec,
            Http3FrameSettingsCodec,
            BytePrefixedStringCodec,
            Http3FrameCodec,
            Http3FrameCodec,
        )
    private val streams =
        arrayOf(
            streamOf(0x00, 0x03, 1, 2, 3),
            streamOf(0xC0, 0x00),
            streamOf(0x00, 0x01, 0x02, 0x00, 0x00, 0x00, 9, 9),
            streamOf(0x04, 0x02, 1, 2),
            streamOf(0x02, 'h'.code, 'i'.code),
            // Unframed dispatcher on a QUIC-varint discriminator: DATA (1-byte type) and an
            // extension frame (type 64, a 2-byte varint).
            streamOf(0x00, 0x03, 1, 2, 3),
            streamOf(0x40, 0x40, 0x01, 7),
        )

    private fun streamOf(vararg bytes: Int): StreamProcessor {
        val buf = BufferFactory.Default.allocate(bytes.size, ByteOrder.BIG_ENDIAN)
        for (b in bytes) buf.writeByte(b.toByte())
        buf.resetForRead()
        return StreamProcessor.create(BufferPool(), ByteOrder.BIG_ENDIAN).also { it.append(buf) }
    }

    /** Runs [calls] peeks and returns the sum of declared totals (so nothing is dead code). */
    fun run(calls: Int): Long {
        var sink = 0L
        val n = detectors.size
        for (i in 0 until calls) {
            val k = i % n
            sink += detectors[k].frameSize(streams[k], 0).foldTotal({ it }, { -1 }, { -2 })
        }
        return sink
    }

    fun release() = streams.forEach { it.release() }
}
