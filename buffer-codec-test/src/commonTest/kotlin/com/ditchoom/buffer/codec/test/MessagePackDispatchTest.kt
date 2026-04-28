package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.test.protocols.MessagePackByte
import com.ditchoom.buffer.codec.test.protocols.MessagePackFormatByte
import com.ditchoom.buffer.codec.test.protocols.MessagePackFormatByteCodec
import com.ditchoom.buffer.codec.test.protocols.MessagePackMalformedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Spec-compliance walk over the MessagePack format byte (RFC 7159 §5).
 *
 * The fixture deliberately models only a subset of the format byte space:
 *   - PositiveFixInt over 0x00..0x7F  (low 7 bits = inline value)
 *   - FixMap         over 0x80..0x8F  (low 4 bits = entry count)
 *   - NegativeFixInt over 0xE0..0xFF  (low 5 bits sign-extended)
 *   - Nil / False / True singletons at 0xC0 / 0xC2 / 0xC3
 *
 * The remaining bytes (notably 0xC1, plus 0x90..0xBF and 0xC4..0xDF) are intentionally
 * unclaimed so we can verify the dispatcher routes them through the configured
 * [MessagePackMalformedException] rather than the default [IllegalArgumentException].
 */
class MessagePackDispatchTest {
    private fun bufferOf(byte: Int) =
        BufferFactory.Default.allocate(1, ByteOrder.BIG_ENDIAN).apply {
            writeByte(byte.toByte())
            resetForRead()
        }

    // ========== Boundary checks: ranges must not silently mis-route ==========

    @Test
    fun positiveFixIntBoundary0x7FRoutesToPositiveFixInt() {
        val decoded = MessagePackFormatByteCodec.decode(bufferOf(0x7F), DecodeContext.Empty)
        assertTrue(decoded is MessagePackFormatByte.PositiveFixInt)
        assertEquals(0x7F, decoded.header.positiveFixIntValue)
    }

    @Test
    fun fixMapBoundary0x80RoutesToFixMap() {
        val decoded = MessagePackFormatByteCodec.decode(bufferOf(0x80), DecodeContext.Empty)
        assertTrue(decoded is MessagePackFormatByte.FixMap)
        assertEquals(0, decoded.header.fixMapEntryCount)
    }

    @Test
    fun fixMapBoundary0x8FRoutesToFixMap() {
        val decoded = MessagePackFormatByteCodec.decode(bufferOf(0x8F), DecodeContext.Empty)
        assertTrue(decoded is MessagePackFormatByte.FixMap)
        assertEquals(0x0F, decoded.header.fixMapEntryCount)
    }

    @Test
    fun pastFixMapBoundary0x90Throws() {
        // 0x90 is the first byte past the FixMap range (0x80..0x8F) and is intentionally
        // unclaimed in this fixture — must surface as MessagePackMalformedException.
        assertFailsWith<MessagePackMalformedException> {
            MessagePackFormatByteCodec.decode(bufferOf(0x90), DecodeContext.Empty)
        }
    }

    @Test
    fun negativeFixIntBoundary0xE0RoutesToNegativeFixInt() {
        val decoded = MessagePackFormatByteCodec.decode(bufferOf(0xE0), DecodeContext.Empty)
        assertTrue(decoded is MessagePackFormatByte.NegativeFixInt)
        assertEquals(-32, decoded.header.negativeFixIntValue)
    }

    @Test
    fun negativeFixIntBoundary0xFFRoutesToNegativeFixInt() {
        val decoded = MessagePackFormatByteCodec.decode(bufferOf(0xFF), DecodeContext.Empty)
        assertTrue(decoded is MessagePackFormatByte.NegativeFixInt)
        assertEquals(-1, decoded.header.negativeFixIntValue)
    }

    // ========== Singleton bytes ==========

    @Test
    fun nilDecodes() {
        val decoded = MessagePackFormatByteCodec.decode(bufferOf(0xC0), DecodeContext.Empty)
        assertTrue(decoded is MessagePackFormatByte.Nil)
    }

    @Test
    fun falseDecodes() {
        val decoded = MessagePackFormatByteCodec.decode(bufferOf(0xC2), DecodeContext.Empty)
        assertTrue(decoded is MessagePackFormatByte.False)
    }

    @Test
    fun trueDecodes() {
        val decoded = MessagePackFormatByteCodec.decode(bufferOf(0xC3), DecodeContext.Empty)
        assertTrue(decoded is MessagePackFormatByte.True)
    }

    @Test
    fun unclaimed0xC1Throws() {
        // 0xC1 is reserved-but-unused in the MessagePack spec — perfect for asserting
        // that "registered range neighbours don't accidentally swallow a hole".
        assertFailsWith<MessagePackMalformedException> {
            MessagePackFormatByteCodec.decode(bufferOf(0xC1), DecodeContext.Empty)
        }
    }

    // ========== Exhaustive walk of every byte 0x00..0xFF ==========

    @Test
    fun everyByteRoutesToCorrectVariantOrThrows() {
        for (byte in 0x00..0xFF) {
            val buf = bufferOf(byte)
            when {
                byte in 0x00..0x7F -> {
                    val decoded = MessagePackFormatByteCodec.decode(buf, DecodeContext.Empty)
                    assertTrue(
                        decoded is MessagePackFormatByte.PositiveFixInt,
                        "byte ${byte.toString(16)} should decode as PositiveFixInt, got $decoded",
                    )
                    assertEquals(byte, decoded.header.positiveFixIntValue)
                }
                byte in 0x80..0x8F -> {
                    val decoded = MessagePackFormatByteCodec.decode(buf, DecodeContext.Empty)
                    assertTrue(
                        decoded is MessagePackFormatByte.FixMap,
                        "byte ${byte.toString(16)} should decode as FixMap, got $decoded",
                    )
                    assertEquals(byte and 0x0F, decoded.header.fixMapEntryCount)
                }
                byte in 0xE0..0xFF -> {
                    val decoded = MessagePackFormatByteCodec.decode(buf, DecodeContext.Empty)
                    assertTrue(
                        decoded is MessagePackFormatByte.NegativeFixInt,
                        "byte ${byte.toString(16)} should decode as NegativeFixInt, got $decoded",
                    )
                    assertEquals((byte and 0x1F) - 32, decoded.header.negativeFixIntValue)
                }
                byte == 0xC0 -> {
                    val decoded = MessagePackFormatByteCodec.decode(buf, DecodeContext.Empty)
                    assertTrue(decoded is MessagePackFormatByte.Nil)
                }
                byte == 0xC2 -> {
                    val decoded = MessagePackFormatByteCodec.decode(buf, DecodeContext.Empty)
                    assertTrue(decoded is MessagePackFormatByte.False)
                }
                byte == 0xC3 -> {
                    val decoded = MessagePackFormatByteCodec.decode(buf, DecodeContext.Empty)
                    assertTrue(decoded is MessagePackFormatByte.True)
                }
                else -> {
                    // 0x90..0xBF, 0xC1, 0xC4..0xDF — all unclaimed in this fixture.
                    assertFailsWith<MessagePackMalformedException>(
                        "byte ${byte.toString(16)} is unclaimed and must throw the configured exception",
                    ) {
                        MessagePackFormatByteCodec.decode(buf, DecodeContext.Empty)
                    }
                }
            }
        }
    }

    // ========== Inline-data extraction sanity check ==========

    @Test
    fun positiveFixIntExtractsInlineValue() {
        val decoded = MessagePackFormatByteCodec.decode(bufferOf(0x42), DecodeContext.Empty)
        assertTrue(decoded is MessagePackFormatByte.PositiveFixInt)
        assertEquals(0x42, decoded.header.positiveFixIntValue)
    }

    @Test
    fun fixMapExtractsEntryCount() {
        val decoded = MessagePackFormatByteCodec.decode(bufferOf(0x83), DecodeContext.Empty)
        assertTrue(decoded is MessagePackFormatByte.FixMap)
        assertEquals(3, decoded.header.fixMapEntryCount)
    }

    @Test
    fun negativeFixIntExtractsSignedValue() {
        // 0xF8 = 0b1111_1000 → low 5 bits 0b11000 = 24, signed = 24 - 32 = -8
        val decoded = MessagePackFormatByteCodec.decode(bufferOf(0xF8), DecodeContext.Empty)
        assertTrue(decoded is MessagePackFormatByte.NegativeFixInt)
        assertEquals(-8, decoded.header.negativeFixIntValue)
    }

    // ========== Sanity: header field carries the raw byte ==========

    @Test
    fun rawByteIsPreservedInDiscriminatorField() {
        val decoded = MessagePackFormatByteCodec.decode(bufferOf(0x55), DecodeContext.Empty)
        assertTrue(decoded is MessagePackFormatByte.PositiveFixInt)
        assertEquals(MessagePackByte(0x55u), decoded.header)
    }
}
