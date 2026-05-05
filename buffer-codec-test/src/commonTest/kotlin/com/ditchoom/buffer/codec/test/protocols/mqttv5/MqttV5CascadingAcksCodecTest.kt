package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.test.protocols.payload.JpegImage
import com.ditchoom.buffer.codec.test.protocols.payload.JpegImageCodec
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Phase J.M.5 slice 4 — cascading-trailing-reason-code v5 acks. Drives
 * the new `@When("remaining >= 1")` grammar-2 predicate end-to-end. The
 * `[properties]` half of the cascade is deferred to slice 5+ (lifting
 * the conditional inner shape to accept `@LengthPrefixed @UseCodec val:
 * List<E>?`).
 *
 * Each ack tested across both wire forms:
 *   - RL=2: `header + RL=2 + pid` — reasonCode null on decode, omit
 *     from wire on encode.
 *   - RL=3: `header + RL=3 + pid + rc` — reasonCode populated.
 *
 * DISCONNECT and AUTH have no packet identifier, so RL=0/RL=1
 * variants apply instead. Tested via the `Disconnect` /  `Auth`
 * branches.
 */
class MqttV5CascadingAcksCodecTest {
    @Test
    fun encodesPubAckWithoutReasonCodeAsTwoByteBody() {
        // §3.4.2.1 — Reason Code can be omitted when "Success and no
        // properties". Caller signals "omit" with reasonCode = null.
        val msg =
            MqttV5Packet.PubAck(
                remainingLength = 2u,
                packetIdentifier = 0x002Au,
                reasonCode = null,
            )
        val buf = encode(msg)
        buf.resetForRead()
        assertContentEquals(
            byteArrayOf(0x40, 0x02, 0x00, 0x2A),
            buf.readByteArray(buf.remaining()),
        )
    }

    @Test
    fun encodesPubAckWithExplicitReasonCode() {
        val msg =
            MqttV5Packet.PubAck(
                remainingLength = 3u,
                packetIdentifier = 0x002Au,
                reasonCode = 0x10u, // No matching subscribers
            )
        val buf = encode(msg)
        buf.resetForRead()
        assertContentEquals(
            byteArrayOf(0x40, 0x03, 0x00, 0x2A, 0x10),
            buf.readByteArray(buf.remaining()),
        )
    }

    @Test
    fun decodesPubAckWithoutReasonCodeYieldsNull() {
        val wire = byteArrayOf(0x40, 0x02, 0x00, 0x2A)
        val buf =
            BufferFactory.Default
                .allocate(wire.size, ByteOrder.BIG_ENDIAN)
                .also {
                    it.writeBytes(wire)
                    it.resetForRead()
                }
        val decoded = jpegDispatcher().decode(buf, DecodeContext.Empty)
        val pubAck = assertIs<MqttV5Packet.PubAck>(decoded)
        assertEquals(0x002Au.toUShort(), pubAck.packetIdentifier)
        assertEquals(null, pubAck.reasonCode, "RL=2 leaves rc absent and reads back null")
    }

    @Test
    fun decodesPubAckWithExplicitReasonCode() {
        val wire = byteArrayOf(0x40, 0x03, 0x00, 0x2A, 0x10)
        val buf =
            BufferFactory.Default
                .allocate(wire.size, ByteOrder.BIG_ENDIAN)
                .also {
                    it.writeBytes(wire)
                    it.resetForRead()
                }
        val decoded = jpegDispatcher().decode(buf, DecodeContext.Empty)
        val pubAck = assertIs<MqttV5Packet.PubAck>(decoded)
        assertEquals(0x10u.toUByte(), pubAck.reasonCode)
    }

    @Test
    fun roundTripsPubAckBothCascadeForms() {
        val cases =
            listOf(
                MqttV5Packet.PubAck(remainingLength = 2u, packetIdentifier = 0x0001u, reasonCode = null),
                MqttV5Packet.PubAck(remainingLength = 3u, packetIdentifier = 0x0001u, reasonCode = 0x83u),
            )
        for (msg in cases) {
            val buf = encode(msg)
            buf.resetForRead()
            val decoded = jpegDispatcher().decode(buf, DecodeContext.Empty)
            assertEquals(msg, decoded, "round-trip $msg")
        }
    }

    @Test
    fun pubRecPubRelPubCompShareTheSameShape() {
        val pubRec = MqttV5Packet.PubRec(remainingLength = 3u, packetIdentifier = 0x0007u, reasonCode = 0x80u)
        val pubRel = MqttV5Packet.PubRel(remainingLength = 3u, packetIdentifier = 0x0007u, reasonCode = 0x92u)
        val pubComp = MqttV5Packet.PubComp(remainingLength = 3u, packetIdentifier = 0x0007u, reasonCode = 0x92u)
        val unsubAck = MqttV5Packet.UnsubAck(remainingLength = 3u, packetIdentifier = 0x0007u, reasonCode = 0x91u)
        for (msg in listOf(pubRec, pubRel, pubComp, unsubAck)) {
            val buf = encode(msg)
            buf.resetForRead()
            val decoded = jpegDispatcher().decode(buf, DecodeContext.Empty)
            assertEquals(msg, decoded, "round-trip $msg")
        }
    }

    @Test
    fun encodesDisconnectWithoutReasonCodeAsTwoBytes() {
        // §3.14.2.1 — DISCONNECT can be the bare `E0 00` (Normal,
        // no properties).
        val msg = MqttV5Packet.Disconnect(remainingLength = 0u, reasonCode = null)
        val buf = encode(msg)
        buf.resetForRead()
        assertContentEquals(byteArrayOf(0xE0.toByte(), 0x00), buf.readByteArray(buf.remaining()))
    }

    @Test
    fun encodesDisconnectWithExplicitReasonCode() {
        val msg = MqttV5Packet.Disconnect(remainingLength = 1u, reasonCode = 0x8Du) // Keep Alive timeout
        val buf = encode(msg)
        buf.resetForRead()
        assertContentEquals(
            byteArrayOf(0xE0.toByte(), 0x01, 0x8D.toByte()),
            buf.readByteArray(buf.remaining()),
        )
    }

    @Test
    fun roundTripsDisconnectBothCascadeForms() {
        val cases =
            listOf(
                MqttV5Packet.Disconnect(remainingLength = 0u, reasonCode = null),
                MqttV5Packet.Disconnect(remainingLength = 1u, reasonCode = 0x8Du),
            )
        for (msg in cases) {
            val buf = encode(msg)
            buf.resetForRead()
            val decoded = jpegDispatcher().decode(buf, DecodeContext.Empty)
            assertEquals(msg, decoded, "round-trip $msg")
        }
    }

    @Test
    fun roundTripsAuthBothCascadeForms() {
        val cases =
            listOf(
                MqttV5Packet.Auth(remainingLength = 0u, reasonCode = null),
                MqttV5Packet.Auth(remainingLength = 1u, reasonCode = 0x18u), // Continue authentication
            )
        for (msg in cases) {
            val buf = encode(msg)
            buf.resetForRead()
            val decoded = jpegDispatcher().decode(buf, DecodeContext.Empty)
            assertEquals(msg, decoded, "round-trip $msg")
        }
    }

    @Test
    fun peekFrameSizeForPubAckCompletes() {
        // The bounding RL upstream gives the total frame width — peek
        // returns Complete(1 + vbi_width + rl).
        val msg = MqttV5Packet.PubAck(remainingLength = 3u, packetIdentifier = 0x0001u, reasonCode = 0x10u)
        val buf = encode(msg)
        buf.resetForRead()
        val totalBytes = buf.remaining()

        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            stream.append(buf)
            assertEquals(PeekResult.Complete(totalBytes), jpegDispatcher().peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Test
    fun encodesPubAckWithEmptyPropertyBagAsRl4() {
        // §3.4.2.2.1 — RL=4 is the minimum that carries a Property
        // Length byte (0x00 = empty bag).
        val msg =
            MqttV5Packet.PubAck(
                remainingLength = 4u,
                packetIdentifier = 0x002Au,
                reasonCode = 0x10u,
                properties = emptyList(),
            )
        val buf = encode(msg)
        buf.resetForRead()
        assertContentEquals(
            byteArrayOf(0x40, 0x04, 0x00, 0x2A, 0x10, 0x00),
            buf.readByteArray(buf.remaining()),
        )
    }

    @Test
    fun encodesPubAckWithPopulatedPropertyBag() {
        // RL = 2 (pid) + 1 (rc) + 1 (propLen) + 5 (MessageExpiry) = 9
        val msg =
            MqttV5Packet.PubAck(
                remainingLength = 9u,
                packetIdentifier = 0x002Au,
                reasonCode = 0x00u,
                properties = listOf(MqttV5Property.MessageExpiryInterval(seconds = 60u)),
            )
        val buf = encode(msg)
        buf.resetForRead()
        assertContentEquals(
            byteArrayOf(
                0x40,
                0x09, // RL = 9
                0x00,
                0x2A, // pid = 42
                0x00, // rc = Success
                0x05, // propLen = 5
                0x02, // MessageExpiryInterval id
                0x00,
                0x00,
                0x00,
                0x3C, // expiry = 60
            ),
            buf.readByteArray(buf.remaining()),
        )
    }

    @Test
    fun roundTripsPubAckAllFourCascadeForms() {
        val cases =
            listOf(
                // Form 1: bare PUBACK, no rc, no props
                MqttV5Packet.PubAck(remainingLength = 2u, packetIdentifier = 0x0001u),
                // Form 2: rc only (RL<4 elides propLen)
                MqttV5Packet.PubAck(remainingLength = 3u, packetIdentifier = 0x0001u, reasonCode = 0x10u),
                // Form 3: rc + empty property bag (RL=4)
                MqttV5Packet.PubAck(
                    remainingLength = 4u,
                    packetIdentifier = 0x0001u,
                    reasonCode = 0x00u,
                    properties = emptyList(),
                ),
                // Form 4: rc + non-empty property bag
                MqttV5Packet.PubAck(
                    remainingLength = 22u,
                    packetIdentifier = 0x0001u,
                    reasonCode = 0x00u,
                    properties =
                        listOf(
                            MqttV5Property.MessageExpiryInterval(seconds = 3_600u),
                            MqttV5Property.ContentType(value = "text/plain"),
                        ),
                ),
            )
        for (msg in cases) {
            val buf = encode(msg)
            buf.resetForRead()
            val decoded = jpegDispatcher().decode(buf, DecodeContext.Empty)
            assertEquals(msg, decoded, "round-trip $msg")
        }
    }

    @Test
    fun roundTripsDisconnectWithProperties() {
        // body = 1 (rc) + 1 (propLen=5) + 5 (MessageExpiry) = 7
        val original =
            MqttV5Packet.Disconnect(
                remainingLength = 7u,
                reasonCode = 0x00u, // Normal disconnection (with reason for properties to attach)
                properties = listOf(MqttV5Property.MessageExpiryInterval(seconds = 60u)),
            )
        val buf = encode(original)
        buf.resetForRead()
        val decoded = jpegDispatcher().decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun peekFrameSizeForDisconnectDripFed() {
        val msg = MqttV5Packet.Disconnect(remainingLength = 1u, reasonCode = 0x8Du)
        val buf = encode(msg)
        buf.resetForRead()
        val totalBytes = buf.remaining()

        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            assertEquals(PeekResult.NeedsMoreData, jpegDispatcher().peekFrameSize(stream))
            // Drip-feed each byte; only the final byte makes the bounded RL
            // peek complete.
            for (i in 0 until totalBytes - 1) {
                val one = BufferFactory.Default.allocate(1)
                one.writeByte(buf.readByte())
                one.resetForRead()
                stream.append(one)
                assertEquals(
                    PeekResult.NeedsMoreData,
                    jpegDispatcher().peekFrameSize(stream),
                    "after ${i + 1} bytes",
                )
            }
            val last = BufferFactory.Default.allocate(1)
            last.writeByte(buf.readByte())
            last.resetForRead()
            stream.append(last)
            assertEquals(PeekResult.Complete(totalBytes), jpegDispatcher().peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Test
    fun pubAckRejectsPropertiesWithoutReasonCode() {
        // Phase J.M.5 audit-2c — caller cannot construct `(rc=null,
        // properties=non-null)` because the wire would misframe (the
        // propLen byte would land where rc should be on decode).
        // The init-block require fires immediately at construction.
        val ex =
            assertFailsWith<IllegalArgumentException> {
                MqttV5Packet.PubAck(
                    remainingLength = 4u,
                    packetIdentifier = 0x002Au,
                    reasonCode = null,
                    properties = emptyList(),
                )
            }
        assertEquals(
            true,
            ex.message?.contains("cascade invariant"),
            "expected cascade-invariant message, got: ${ex.message}",
        )
    }

    @Test
    fun pubAckRejectsNonEmptyPropertiesWithoutReasonCode() {
        assertFailsWith<IllegalArgumentException> {
            MqttV5Packet.PubAck(
                remainingLength = 7u,
                packetIdentifier = 0x002Au,
                reasonCode = null,
                properties = listOf(MqttV5Property.MessageExpiryInterval(seconds = 60u)),
            )
        }
    }

    @Test
    fun disconnectRejectsPropertiesWithoutReasonCode() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                MqttV5Packet.Disconnect(
                    remainingLength = 2u,
                    reasonCode = null,
                    properties = emptyList(),
                )
            }
        assertEquals(
            true,
            ex.message?.contains("cascade invariant"),
            "expected cascade-invariant message, got: ${ex.message}",
        )
    }

    @Test
    fun disconnectRejectsNonEmptyPropertiesWithoutReasonCode() {
        assertFailsWith<IllegalArgumentException> {
            MqttV5Packet.Disconnect(
                remainingLength = 5u,
                reasonCode = null,
                properties = listOf(MqttV5Property.ContentType(value = "application/json")),
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun encode(value: MqttV5Packet<*>) =
        BufferFactory.Default
            .allocate(64, ByteOrder.BIG_ENDIAN)
            .also {
                (jpegDispatcher() as com.ditchoom.buffer.codec.Codec<MqttV5Packet<*>>)
                    .encode(it, value, EncodeContext.Empty)
            }

    private fun jpegDispatcher(): MqttV5PacketCodec<JpegImage> = MqttV5PacketCodec(JpegImageCodec)
}
