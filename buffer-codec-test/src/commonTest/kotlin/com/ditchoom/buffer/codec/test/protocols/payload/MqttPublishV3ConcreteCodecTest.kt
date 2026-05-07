package com.ditchoom.buffer.codec.test.protocols.payload

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttFixedHeader
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stage H slice 10a doctrine vector — full round-trip for
 * `MqttPublishV3Concrete` with a typed `JpegImage` payload routed through
 * the user-supplied `JpegImageCodec`.
 *
 * Acceptance points (mapped to PHASE_9_RESET §Stage H):
 *   1. Typed-payload PUBLISH round-trips byte-exact (acceptance #1).
 *   2. `wireSize` is `BackPatch` for slice 10a's conservative path.
 *   3. `peekFrameSize` is `NoFraming` — slice 10d's outer dispatcher
 *      will own peek via the fixed header's `@RemainingLength`.
 *   4. Caller-set `buffer.limit()` bounds the payload region; trailing
 *      bytes after the bounded region are not consumed.
 */
class MqttPublishV3ConcreteCodecTest {
    @Test
    fun encodesByteExactWireFormat() {
        val msg =
            MqttPublishV3Concrete(
                header = MqttFixedHeader(0x30u),
                topic = "a/b",
                packetId = PacketId(0x1234u),
                payload =
                    JpegImage(
                        width = 4u,
                        height = 8u,
                        data = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
                    ),
            )
        val buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        MqttPublishV3ConcreteCodec.encode(buf, msg, EncodeContext.Empty)
        val written = buf.position()
        // 1 (header) + 2 (topic prefix) + 3 (topic body) + 2 (packetId) +
        // 4 (jpeg width+height) + 4 (jpeg data) = 16
        assertEquals(16, written)
        buf.resetForRead()
        val actual = buf.readByteArray(written)
        val expected =
            byteArrayOf(
                0x30, // fixed header
                0x00,
                0x03, // topic length prefix
                'a'.code.toByte(),
                '/'.code.toByte(),
                'b'.code.toByte(),
                0x12,
                0x34, // packet id
                0x00,
                0x04, // jpeg width
                0x00,
                0x08, // jpeg height
                0xDE.toByte(),
                0xAD.toByte(),
                0xBE.toByte(),
                0xEF.toByte(),
            )
        assertContentEquals(expected, actual)
    }

    @Test
    fun roundTripsFromExactlyBoundedBuffer() {
        val original =
            MqttPublishV3Concrete(
                header = MqttFixedHeader(0x30u),
                topic = "sensors/room/42",
                packetId = PacketId(0x0042u),
                payload =
                    JpegImage(
                        width = 320u,
                        height = 240u,
                        data = ByteArray(64) { (it * 7).toByte() },
                    ),
            )
        val buf = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
        MqttPublishV3ConcreteCodec.encode(buf, original, EncodeContext.Empty)
        buf.resetForRead()
        val decoded = MqttPublishV3ConcreteCodec.decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun decodeRespectsCallerLimitForPayloadRegion() {
        // Caller writes a publish + trailing junk. The buffer's outer
        // limit covers everything; slice 10a relies on the caller
        // narrowing the limit before decode (slice 10d's outer
        // dispatcher will do this via the @RemainingLength var-int).
        val original =
            MqttPublishV3Concrete(
                header = MqttFixedHeader(0x30u),
                topic = "t",
                packetId = PacketId(1u),
                payload = JpegImage(2u, 3u, byteArrayOf(0x11, 0x22, 0x33)),
            )
        val buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        MqttPublishV3ConcreteCodec.encode(buf, original, EncodeContext.Empty)
        val publishBytes = buf.position()
        // Append trailing bytes that the publish decode must NOT read.
        buf.writeByte(0xCA.toByte())
        buf.writeByte(0xFE.toByte())
        buf.writeByte(0xBA.toByte())
        buf.writeByte(0xBE.toByte())
        buf.resetForRead()
        // Caller narrows the limit to the publish bytes only; equivalent
        // to slice 10d's outer dispatcher reading @RemainingLength and
        // calling setLimit before delegating.
        buf.setLimit(publishBytes)
        val decoded = MqttPublishV3ConcreteCodec.decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
        assertEquals(publishBytes, buf.position(), "decode advanced exactly through the publish")
    }

    @Test
    fun wireSizeIsBackPatchForSlice10aConservativePath() {
        val msg =
            MqttPublishV3Concrete(
                header = MqttFixedHeader(0x30u),
                topic = "x",
                packetId = PacketId(1u),
                payload = JpegImage(0u, 0u, ByteArray(0)),
            )
        // Slice 10a unconditionally returns BackPatch when a
        // RemainingBytesPayload field is present, regardless of whether
        // the user codec itself returns Exact (JpegImageCodec does).
        // Promotion to runtime-Exact is a follow-on with a vector that
        // measurably benefits.
        assertEquals(WireSize.BackPatch, MqttPublishV3ConcreteCodec.wireSize(msg, EncodeContext.Empty))
    }

    @Test
    fun peekFrameSizeIsNoFraming() {
        // Slice 10a deliberately does not peek the publish frame.
        // The outer dispatcher (slice 10d) will own peek via the
        // fixed-header @RemainingLength var-int. peekFrameSize stays
        // NoFraming regardless of what the underlying stream contains.
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            assertEquals(PeekResult.NoFraming, MqttPublishV3ConcreteCodec.peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Test
    fun userCodecReceivesEncodeContextThroughGeneratedCall() {
        // Smoke test: the generated encode forwards the EncodeContext to
        // JpegImageCodec.encode without losing it. JpegImageCodec doesn't
        // currently consume the context (no keys are read), so this is a
        // shape check, not a value check.
        val msg =
            MqttPublishV3Concrete(
                header = MqttFixedHeader(0x30u),
                topic = "k",
                packetId = PacketId(7u),
                payload = JpegImage(1u, 1u, byteArrayOf(0x42)),
            )
        val buf = BufferFactory.Default.allocate(32, ByteOrder.BIG_ENDIAN)
        MqttPublishV3ConcreteCodec.encode(buf, msg, EncodeContext.Empty)
        assertTrue(buf.position() > 0, "generated encode delegated to JpegImageCodec without throwing")
    }
}
