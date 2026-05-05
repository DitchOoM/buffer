package com.ditchoom.buffer.codec.test.protocols.mqtt

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Stage G slice 7b doctrine vector. Validates `@RemainingBytes` on
 * `List<UByte>`: the SUBACK body's return-codes list reads until
 * the buffer's limit is reached. Caller-bounded — the test sets
 * `buffer.setLimit` before calling decode to simulate the outer
 * MQTT framing layer (fixed-header remaining-length variable-length
 * integer, parsed at a higher layer).
 *
 * Vectors are byte-exact against MQTT-3.1.1 §3.9.
 */
class MqttSubAckBodyCodecTest {
    @Test
    fun encodesEmptyReturnCodesByteExact() {
        val msg = MqttSubAckBody(packetIdentifier = 1u, returnCodes = emptyList())
        val expected =
            byteArrayOf(
                0x00,
                0x01, // packet identifier
            )
        encodeAndAssertBytes(msg, expected)
    }

    @Test
    fun encodesThreeReturnCodesByteExact() {
        val msg =
            MqttSubAckBody(
                packetIdentifier = 0x1234u,
                returnCodes =
                    listOf(
                        0x00u, // QoS 0 success
                        0x01u, // QoS 1 success
                        0x80u, // failure
                    ),
            )
        val expected =
            byteArrayOf(
                0x12,
                0x34, // packet identifier
                0x00,
                0x01,
                0x80.toByte(), // return codes
            )
        encodeAndAssertBytes(msg, expected)
    }

    @Test
    fun decodesReturnCodesUntilBufferLimit() {
        // Wire: packet identifier (2 bytes) + 3 return codes.
        // Allocate a 5-byte buffer; the entire buffer is the body region.
        val buf = BufferFactory.Default.allocate(5, ByteOrder.BIG_ENDIAN)
        buf.writeShort(0x1234.toShort())
        buf.writeByte(0x00.toByte())
        buf.writeByte(0x01.toByte())
        buf.writeByte(0x80.toByte())
        buf.resetForRead()
        val decoded = MqttSubAckBodyCodec.decode(buf, DecodeContext.Empty)
        assertEquals(0x1234u.toUShort(), decoded.packetIdentifier)
        assertEquals(listOf(0x00u.toUByte(), 0x01u.toUByte(), 0x80u.toUByte()), decoded.returnCodes)
    }

    @Test
    fun decodeRespectsExternallySetLimit() {
        // Wire allocated as 8 bytes but only first 5 are the body
        // (caller has set limit accordingly). Trailing bytes simulate
        // a following MQTT packet — must not be consumed.
        val buf = BufferFactory.Default.allocate(8, ByteOrder.BIG_ENDIAN)
        buf.writeShort(0x1234.toShort())
        buf.writeByte(0x00.toByte())
        buf.writeByte(0x01.toByte())
        buf.writeByte(0x80.toByte())
        // Trailing bytes — start of next packet, must remain unconsumed.
        buf.writeByte(0xDE.toByte())
        buf.writeByte(0xAD.toByte())
        buf.writeByte(0xBE.toByte())
        buf.resetForRead()
        // Outer framing: limit the body to 5 bytes.
        buf.setLimit(5)
        val decoded = MqttSubAckBodyCodec.decode(buf, DecodeContext.Empty)
        assertEquals(3, decoded.returnCodes.size, "list bounded by external limit, not buffer end")
        assertEquals(5, buf.position(), "decode advanced position to the bounded limit")
    }

    @Test
    fun roundTripsTwoReturnCodes() {
        val original =
            MqttSubAckBody(
                packetIdentifier = 0xABCDu,
                returnCodes = listOf(0x00u, 0x02u),
            )
        val buf = encode(original)
        buf.resetForRead()
        val decoded = MqttSubAckBodyCodec.decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun wireSizeIsExactBasedOnListSize() {
        // 2 bytes (packet identifier) + N bytes (return codes).
        val msg =
            MqttSubAckBody(
                packetIdentifier = 1u,
                returnCodes = listOf(0u, 1u, 2u),
            )
        assertEquals(WireSize.Exact(5), MqttSubAckBodyCodec.wireSize(msg, EncodeContext.Empty))

        val empty = MqttSubAckBody(packetIdentifier = 1u, returnCodes = emptyList())
        assertEquals(WireSize.Exact(2), MqttSubAckBodyCodec.wireSize(empty, EncodeContext.Empty))
    }

    @Test
    fun peekFrameSizeReportsNoFraming() {
        // @RemainingBytes signals "outer framing required" — peek
        // can't determine the size without the caller-set buffer
        // limit, which the stream-side peek doesn't see.
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            assertEquals(PeekResult.NoFraming, MqttSubAckBodyCodec.peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    private fun encodeAndAssertBytes(
        msg: MqttSubAckBody,
        expected: ByteArray,
    ) {
        val buf = encode(msg)
        assertEquals(expected.size, buf.position(), "encoded byte count matches MQTT-3.1.1 §3.9 layout")
        buf.resetForRead()
        val actual = buf.readByteArray(expected.size)
        assertContentEquals(expected, actual, "encoded bytes match MQTT-3.1.1 §3.9")
    }

    private fun encode(value: MqttSubAckBody) =
        BufferFactory.Default
            .allocate(64, ByteOrder.BIG_ENDIAN)
            .also { MqttSubAckBodyCodec.encode(it, value, EncodeContext.Empty) }
}
