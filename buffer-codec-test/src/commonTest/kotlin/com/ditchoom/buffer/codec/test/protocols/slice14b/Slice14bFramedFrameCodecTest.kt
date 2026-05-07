package com.ditchoom.buffer.codec.test.protocols.slice14b

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Phase J.M.5 slice 14b — `@FramedBy` capability probe.
 *
 * Five checks:
 *   1. **Fixed wire bytes** — encode of `(payload=0x42, tail=0xABCD)`
 *      writes `03 42 AB CD` (1-byte VBI prefix `03` + 3-byte body).
 *      Confirms the slicing scheme right-flushes the prefix into the
 *      slack region without leaking the unused slack onto the wire.
 *   2. **Fixed round-trip** — decode of those same bytes recovers the
 *      data class.
 *   3. **Variable round-trip (short)** — `message = "hi"` round-trips
 *      cleanly, exercising the BackPatch-suffix body case.
 *   4. **Variable round-trip (medium)** — a 100-character payload
 *      forces the prefix to remain 1-byte VBI (body = 102 bytes,
 *      under the 128-byte boundary) while a 200-character payload
 *      forces a 2-byte VBI prefix. Both must round-trip.
 *   5. **Strict bound check** — a hand-crafted wire form whose prefix
 *      claims more bytes than the body actually consumes must throw
 *      `DecodeException` (the framework owns the bound now and refuses
 *      to leave the cursor short of `__framingBound`).
 */
class Slice14bFramedFrameCodecTest {
    @Test
    fun fixedFrameWireFormatMatchesExpected() {
        val original = Slice14bFramedFrameFixed(payload = 0x42u, tail = 0xABCDu)
        val read =
            Slice14bFramedFrameFixedCodec.encode(
                value = original,
                context = EncodeContext.Empty,
                factory = BufferFactory.Default,
            )
        val bytes = ByteArray(read.remaining())
        for (i in bytes.indices) bytes[i] = read.readByte()
        assertContentEquals(byteArrayOf(0x03, 0x42, 0xAB.toByte(), 0xCD.toByte()), bytes)
    }

    @Test
    fun fixedFrameRoundTrips() {
        val original = Slice14bFramedFrameFixed(payload = 0x55u, tail = 0x1234u)
        val read =
            Slice14bFramedFrameFixedCodec.encode(
                value = original,
                context = EncodeContext.Empty,
                factory = BufferFactory.Default,
            )
        val decoded = Slice14bFramedFrameFixedCodec.decode(read, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun variableFrameShortRoundTrips() {
        val original = Slice14bFramedFrameVariable(message = "hi")
        val read =
            Slice14bFramedFrameVariableCodec.encode(
                value = original,
                context = EncodeContext.Empty,
                factory = BufferFactory.Default,
            )
        // Body = 2 (length prefix) + 2 ("hi") = 4 bytes; VBI prefix = `04`. Total wire = 5 bytes.
        assertEquals(5, read.remaining())
        val decoded = Slice14bFramedFrameVariableCodec.decode(read, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun variableFrameMediumRoundTrips1ByteVbi() {
        // 100-char ASCII string → body = 2 + 100 = 102 bytes → VBI = 1 byte.
        val original = Slice14bFramedFrameVariable(message = "x".repeat(100))
        val read =
            Slice14bFramedFrameVariableCodec.encode(
                value = original,
                context = EncodeContext.Empty,
                factory = BufferFactory.Default,
            )
        assertEquals(1 + 102, read.remaining())
        val decoded = Slice14bFramedFrameVariableCodec.decode(read, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun variableFrameMediumRoundTrips2ByteVbi() {
        // 200-char ASCII → body = 2 + 200 = 202 → 202 ≥ 128 → VBI = 2 bytes.
        val original = Slice14bFramedFrameVariable(message = "y".repeat(200))
        val read =
            Slice14bFramedFrameVariableCodec.encode(
                value = original,
                context = EncodeContext.Empty,
                factory = BufferFactory.Default,
            )
        assertEquals(2 + 202, read.remaining())
        val decoded = Slice14bFramedFrameVariableCodec.decode(read, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun strictBoundCheckRejectsOverlongPrefix() {
        // Hand-craft a wire form: prefix says 5, but body only consumes 3 (UByte + UShort).
        val wire = byteArrayOf(0x05, 0x42, 0xAB.toByte(), 0xCD.toByte(), 0x00, 0x00)
        val buffer = BufferFactory.Default.allocate(wire.size, ByteOrder.BIG_ENDIAN)
        buffer.writeBytes(wire)
        buffer.resetForRead()
        val ex =
            assertFailsWith<DecodeException> {
                Slice14bFramedFrameFixedCodec.decode(buffer, DecodeContext.Empty)
            }
        assertTrue(
            ex.message!!.contains("@FramedBy"),
            "expected @FramedBy diagnostic, got: ${ex.message}",
        )
    }
}
