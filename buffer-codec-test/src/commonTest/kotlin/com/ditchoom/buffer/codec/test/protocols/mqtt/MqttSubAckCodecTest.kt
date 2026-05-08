package com.ditchoom.buffer.codec.test.protocols.mqtt

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.test.protocols.mqtt.suback.FailureCodec
import com.ditchoom.buffer.codec.test.protocols.mqtt.suback.MqttV3SubAckReturnCode
import com.ditchoom.buffer.codec.test.protocols.mqtt.suback.SuccessMaximumQoS0Codec
import com.ditchoom.buffer.codec.test.protocols.mqtt.suback.SuccessMaximumQoS1Codec
import com.ditchoom.buffer.codec.test.protocols.mqtt.suback.SuccessMaximumQoS2Codec
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Doctrine vector — full MQTT v3.1.1 SUBACK packet
 * per §3.9. Validates the `@RemainingLength` var-int field's
 * read/write behavior, the implicit `setLimit` bounding decode of
 * subsequent fields, and the var-int byte-count contribution to
 * `wireSize` and `peekFrameSize`.
 *
 * Var-int boundary tests (§2.2.3) cover the 1→2, 2→3, 3→4 byte
 * transitions and the 0/max boundaries.
 *
 * Folded onto the `MqttPacket.SubAck` sealed
 * variant. Drives `SubAckCodec` (the per-variant codec object emitted
 * by the dispatcher) per the brief's option 1: same byte
 * exact assertions, same Partial / Aggregator / peekFrameSize
 * coverage, with the per-variant codec reachable directly. The
 * standalone `MqttSubAck` data class + `MqttSubAckCodec` are gone
 * with this fold.
 */
class MqttSubAckCodecTest {
    @Test
    fun encodesSingleReturnCodeByteExact() {
        // SUBACK type=9 << 4 = 0x90, remainingLength=3 (packet id + 1 return code).
        val msg =
            MqttPacket.SubAck(
                header = MqttFixedHeader(0x90u),
                packetIdentifier = 1u,
                returnCodes = listOf(MqttV3SubAckReturnCode.SuccessMaximumQoS0),
            )
        val expected =
            byteArrayOf(
                0x90.toByte(), // fixed header
                0x03, // remaining length (1-byte var-int)
                0x00,
                0x01, // packet id
                0x00, // return code
            )
        encodeAndAssertBytes(msg, expected)
    }

    @Test
    fun encodesMultipleReturnCodesByteExact() {
        // remainingLength = 2 (packet id) + 3 (return codes) = 5
        val msg =
            MqttPacket.SubAck(
                header = MqttFixedHeader(0x90u),
                packetIdentifier = 0x1234u,
                returnCodes =
                    listOf(
                        MqttV3SubAckReturnCode.SuccessMaximumQoS0,
                        MqttV3SubAckReturnCode.SuccessMaximumQoS1,
                        MqttV3SubAckReturnCode.Failure,
                    ),
            )
        val expected =
            byteArrayOf(
                0x90.toByte(),
                0x05,
                0x12,
                0x34,
                0x00,
                0x01,
                0x80.toByte(),
            )
        encodeAndAssertBytes(msg, expected)
    }

    @Test
    fun decodesSingleReturnCodeFromSpecBytes() {
        val wire = byteArrayOf(0x90.toByte(), 0x03, 0x00, 0x01, 0x00)
        val buf = bigEndianBufferOf(wire)
        val decoded = SubAckCodec.decode(buf, DecodeContext.Empty)
        assertEquals(MqttFixedHeader(0x90u), decoded.header)
        assertEquals(1u.toUShort(), decoded.packetIdentifier)
        assertEquals(listOf(MqttV3SubAckReturnCode.SuccessMaximumQoS0), decoded.returnCodes)
    }

    @Test
    fun decodeRespectsRemainingLengthBoundEvenWithTrailingBytes() {
        // Wire has trailing bytes after the SUBACK — they must NOT be
        // consumed. The @RemainingLength bound (3 bytes) limits the
        // decode region to packet-id (2) + 1 return code.
        val wire =
            byteArrayOf(
                0x90.toByte(),
                0x03,
                0x00,
                0x01,
                0x00,
                // Trailing bytes (would be the next MQTT packet)
                0xC0.toByte(),
                0x00,
                0xDE.toByte(),
                0xAD.toByte(),
            )
        val buf = bigEndianBufferOf(wire)
        val decoded = SubAckCodec.decode(buf, DecodeContext.Empty)
        assertEquals(1, decoded.returnCodes.size, "decode bounded by remainingLength, not buffer remaining")
        assertEquals(5, buf.position(), "decode advanced exactly through SUBACK")
        assertEquals(4, buf.remaining(), "trailing 4 bytes left in buffer for next packet")
    }

    @Test
    fun decodeRestoresBufferLimitAfterCompletion() {
        // Decode sets the buffer's limit to bound subsequent fields,
        // then restores via try/finally. Caller observes original limit.
        val buf = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        buf.writeByte(0x90.toByte())
        buf.writeByte(0x03)
        buf.writeShort(0x0001.toShort())
        buf.writeByte(0x00.toByte())
        buf.resetForRead()
        val originalLimit = buf.limit()
        SubAckCodec.decode(buf, DecodeContext.Empty)
        assertEquals(originalLimit, buf.limit(), "decode restored the outer limit")
    }

    @Test
    fun roundTripsSpecExample() {
        val original =
            MqttPacket.SubAck(
                header = MqttFixedHeader(0x90u),
                packetIdentifier = 0xCAFEu,
                returnCodes =
                    listOf(
                        MqttV3SubAckReturnCode.SuccessMaximumQoS0,
                        MqttV3SubAckReturnCode.SuccessMaximumQoS2,
                        MqttV3SubAckReturnCode.Failure,
                    ),
            )
        val buf = encode(original)
        assertEquals(original, SubAckCodec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun varIntEncodesAt1ByteBoundary() {
        // remainingLength = 127 → 1-byte var-int (0x7F)
        val msg = makeAckWithRemainingLength(127u)
        val buf = encode(msg)
        // 1 (header) + 1 (var-int) + 127 (body) = 129
        assertEquals(129, buf.remaining())
        buf.readByte() // header
        assertEquals(0x7F.toByte(), buf.readByte(), "127 fits in 1 var-int byte")
    }

    @Test
    fun varIntEncodesAt2ByteBoundary() {
        // 0x01)
        val msg = makeAckWithRemainingLength(128u)
        val buf = encode(msg)
        assertEquals(1 + 2 + 128, buf.remaining())
        buf.readByte() // header
        assertEquals(0x80.toByte(), buf.readByte(), "byte 0 has continuation bit")
        assertEquals(0x01.toByte(), buf.readByte(), "byte 1 = 128 / 128 = 1")
    }

    @Test
    fun varIntEncodesAt3ByteBoundary() {
        // 0x80, 0x01)
        val msg = makeAckWithRemainingLength(16384u)
        val buf = encode(msg)
        buf.readByte()
        assertEquals(0x80.toByte(), buf.readByte())
        assertEquals(0x80.toByte(), buf.readByte())
        assertEquals(0x01.toByte(), buf.readByte())
    }

    @Test
    fun varIntEncodesAt4ByteBoundary() {
        // 0x80, 0x80, 0x01)
        val msg = makeAckWithRemainingLength(2_097_152u)
        val buf = encode(msg)
        buf.readByte()
        assertEquals(0x80.toByte(), buf.readByte())
        assertEquals(0x80.toByte(), buf.readByte())
        assertEquals(0x80.toByte(), buf.readByte())
        assertEquals(0x01.toByte(), buf.readByte())
    }

    @Test
    fun varIntRoundTripsAcrossAllByteWidths() {
        // SUBACK requires remainingLength >= 2 (packet id) + 1 (at least one
        // return code per spec) = 3, so we test boundary values >= 3.
        // 127 (1 byte boundary), 128 (2 byte first), 16383 (2 byte boundary),
        // 16384 (3 byte first), 2_097_151 (3 byte boundary), 2_097_152
        // (4 byte first).
        //
        // The 4-byte VBI cases force a ~2 M-element list decode. Phase
        // Flipped `MqttV3SubAckReturnCode` variants to
        // `data object` singletons under `@DispatchOn(value class)`,
        // collapsing per-element allocation to zero (decoded list
        // entries reuse the same four singleton instances regardless
        // of N). Earlier the data-class variant shape cost one
        // allocation per decoded entry on JS Node and tripped Mocha's
        // 2 s default test timeout on the slower GitHub Actions
        // runner; with the singleton shape the full sweep completes
        // well under the timeout.
        //
        // Real SUBACK packets carry < 100 return codes (one per
        // matching topic filter); 2 M elements is purely synthetic
        // test data.
        for (rl in listOf(127u, 128u, 16383u, 16384u, 2_097_151u, 2_097_152u)) {
            val msg = makeAckWithRemainingLength(rl)
            val buf = encode(msg)
            val decoded = SubAckCodec.decode(buf, DecodeContext.Empty)
            assertEquals(msg, decoded, "full round-trip remainingLength=$rl")
        }
    }

    @Test
    fun decodeRejectsMalformedVarInt() {
        // 5 bytes all with continuation bit set — exceeds the 4-byte var-int max.
        val wire =
            byteArrayOf(
                0x90.toByte(),
                0x80.toByte(),
                0x80.toByte(),
                0x80.toByte(),
                0x80.toByte(),
                0x80.toByte(),
            )
        val buf = bigEndianBufferOf(wire)
        val ex =
            assertFailsWith<DecodeException> {
                SubAckCodec.decode(buf, DecodeContext.Empty)
            }
        // FieldPath is now controlled by the codec
        // (`MqttRemainingLengthCodec.decode`'s own throw site); the
        // emit's `<owner>.<field>` prefix is gone with the migration.
        assertEquals("MqttRemainingLength", ex.fieldPath)
    }

    @Test
    fun peekFrameSizeWalksDripFedSubAck() {
        val pool = BufferPool()
        val original =
            MqttPacket.SubAck(
                header = MqttFixedHeader(0x90u),
                packetIdentifier = 1u,
                returnCodes =
                    listOf(
                        MqttV3SubAckReturnCode.SuccessMaximumQoS0,
                        MqttV3SubAckReturnCode.SuccessMaximumQoS1,
                        MqttV3SubAckReturnCode.Failure,
                    ),
            )
        val encoded = encode(original)
        val totalBytes = encoded.remaining()
        // 1 (header) + 1 (var-int) + 5 (body) = 7
        assertEquals(7, totalBytes)

        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            for (i in 0 until totalBytes - 1) {
                val one = BufferFactory.Default.allocate(1)
                one.writeByte(encoded.readByte())
                one.resetForRead()
                stream.append(one)
                assertEquals(
                    PeekResult.NeedsMoreData,
                    SubAckCodec.peekFrameSize(stream),
                    "after ${i + 1} bytes",
                )
            }
            val last = BufferFactory.Default.allocate(1)
            last.writeByte(encoded.readByte())
            last.resetForRead()
            stream.append(last)
            assertEquals(PeekResult.Complete(totalBytes), SubAckCodec.peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Test
    fun peekFrameSizeHandlesMultiByteVarIntDripFed() {
        // Construct a SUBACK with a 2-byte var-int (remainingLength=200).
        val pool = BufferPool()
        val original = makeAckWithRemainingLength(200u)
        val encoded = encode(original)
        val totalBytes = encoded.remaining()
        // 1 (header) + 2 (var-int) + 200 (body) = 203
        assertEquals(203, totalBytes)

        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            // Drip in just the first byte: NeedsMoreData (need at least header + 1 var-int byte).
            val b1 = BufferFactory.Default.allocate(1)
            b1.writeByte(encoded.readByte())
            b1.resetForRead()
            stream.append(b1)
            assertEquals(PeekResult.NeedsMoreData, SubAckCodec.peekFrameSize(stream))

            // Second byte is the first var-int byte (continuation bit set since 200 > 127).
            val b2 = BufferFactory.Default.allocate(1)
            b2.writeByte(encoded.readByte())
            b2.resetForRead()
            stream.append(b2)
            assertEquals(
                PeekResult.NeedsMoreData,
                SubAckCodec.peekFrameSize(stream),
                "var-int continuation bit set, need second byte",
            )

            // Third byte completes the var-int. peek can now compute total.
            val b3 = BufferFactory.Default.allocate(1)
            b3.writeByte(encoded.readByte())
            b3.resetForRead()
            stream.append(b3)
            assertEquals(
                PeekResult.NeedsMoreData,
                SubAckCodec.peekFrameSize(stream),
                "var-int complete but still need 200 body bytes",
            )

            // Drip-feed the rest, asserting NeedsMoreData until the final byte.
            while (encoded.remaining() > 1) {
                val one = BufferFactory.Default.allocate(1)
                one.writeByte(encoded.readByte())
                one.resetForRead()
                stream.append(one)
                assertEquals(PeekResult.NeedsMoreData, SubAckCodec.peekFrameSize(stream))
            }
            val last = BufferFactory.Default.allocate(1)
            last.writeByte(encoded.readByte())
            last.resetForRead()
            stream.append(last)
            assertEquals(PeekResult.Complete(totalBytes), SubAckCodec.peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    /**
     * Pin the per-variant codec emit shape for
     * `data object` variants under `@DispatchOn(value class)`. Each
     * variant codec self-frames the discriminator: `wireSize =
     * Exact(1)`, `peekFrameSize` reports `Complete(1)` once one byte is
     * available, `decode` consumes the byte (and returns the singleton),
     * `encode` writes the `@PacketType.value` literal as a UByte.
     *
     * Independent of the parent dispatcher's peek + reset, so it
     * confirms the variant codec is byte-for-byte symmetric — which is
     * what unblocks the JS Node 4-byte VBI re-add (the synthetic 2 M-
     * element list decode reuses the same singleton instance per
     * decoded entry, eliminating the per-element allocation that
     * tripped Mocha's 2 s timeout under the prior data-class shape).
     */
    @Test
    fun dataObjectVariantsSelfFrameDiscriminatorByte() {
        data class VariantCase(
            val name: String,
            val singleton: MqttV3SubAckReturnCode,
            val codec: com.ditchoom.buffer.codec.Codec<MqttV3SubAckReturnCode>,
            val expectedWireByte: Byte,
        )

        @Suppress("UNCHECKED_CAST")
        val cases =
            listOf(
                VariantCase(
                    "SuccessMaximumQoS0",
                    MqttV3SubAckReturnCode.SuccessMaximumQoS0,
                    SuccessMaximumQoS0Codec as com.ditchoom.buffer.codec.Codec<MqttV3SubAckReturnCode>,
                    0x00.toByte(),
                ),
                VariantCase(
                    "SuccessMaximumQoS1",
                    MqttV3SubAckReturnCode.SuccessMaximumQoS1,
                    SuccessMaximumQoS1Codec as com.ditchoom.buffer.codec.Codec<MqttV3SubAckReturnCode>,
                    0x01.toByte(),
                ),
                VariantCase(
                    "SuccessMaximumQoS2",
                    MqttV3SubAckReturnCode.SuccessMaximumQoS2,
                    SuccessMaximumQoS2Codec as com.ditchoom.buffer.codec.Codec<MqttV3SubAckReturnCode>,
                    0x02.toByte(),
                ),
                VariantCase(
                    "Failure",
                    MqttV3SubAckReturnCode.Failure,
                    FailureCodec as com.ditchoom.buffer.codec.Codec<MqttV3SubAckReturnCode>,
                    0x80.toByte(),
                ),
            )

        for (case in cases) {
            assertEquals(
                WireSize.Exact(1),
                case.codec.wireSize(case.singleton, EncodeContext.Empty),
                "${case.name} wireSize",
            )

            val outBuf = BufferFactory.Default.allocate(1, ByteOrder.BIG_ENDIAN)
            case.codec.encode(outBuf, case.singleton, EncodeContext.Empty)
            outBuf.resetForRead()
            assertEquals(1, outBuf.remaining(), "${case.name} encoded byte count")
            assertEquals(case.expectedWireByte, outBuf.readByte(), "${case.name} discriminator byte")

            val inBuf = BufferFactory.Default.allocate(1, ByteOrder.BIG_ENDIAN)
            inBuf.writeByte(case.expectedWireByte)
            inBuf.resetForRead()
            assertEquals(
                case.singleton,
                case.codec.decode(inBuf, DecodeContext.Empty),
                "${case.name} decode returns singleton",
            )
            assertEquals(0, inBuf.remaining(), "${case.name} decode advanced past discriminator")

            val pool = BufferPool()
            val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
            try {
                assertEquals(
                    PeekResult.NeedsMoreData,
                    case.codec.peekFrameSize(stream),
                    "${case.name} peek with empty stream",
                )
                val byteBuf = BufferFactory.Default.allocate(1, ByteOrder.BIG_ENDIAN)
                byteBuf.writeByte(case.expectedWireByte)
                byteBuf.resetForRead()
                stream.append(byteBuf)
                assertEquals(
                    PeekResult.Complete(1),
                    case.codec.peekFrameSize(stream),
                    "${case.name} peek with one byte available",
                )
            } finally {
                stream.release()
                pool.clear()
            }
        }
    }

    private fun makeAckWithRemainingLength(rl: UInt): MqttPacket.SubAck =
        MqttPacket.SubAck(
            header = MqttFixedHeader(0x90u),
            packetIdentifier = 1u,
            // packetIdentifier is 2 bytes; pad return codes to fill the rest.
            returnCodes = List((rl.toInt() - 2).coerceAtLeast(0)) { MqttV3SubAckReturnCode.SuccessMaximumQoS0 },
        )

    private fun encodeAndAssertBytes(
        msg: MqttPacket.SubAck,
        expected: ByteArray,
    ) {
        val buf = encode(msg)
        assertEquals(expected.size, buf.remaining(), "encoded byte count matches MQTT-3.1.1 §3.9 layout")
        val actual = buf.readByteArray(expected.size)
        assertContentEquals(expected, actual, "encoded bytes match MQTT-3.1.1 §3.9")
    }

    private fun bigEndianBufferOf(wire: ByteArray) =
        BufferFactory.Default
            .allocate(wire.size, ByteOrder.BIG_ENDIAN)
            .also { it.writeBytes(wire) }
            .also { it.resetForRead() }

    private fun encode(value: MqttPacket.SubAck): ReadBuffer = SubAckCodec.encode(value, EncodeContext.Empty, BufferFactory.Default)
}
