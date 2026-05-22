package com.ditchoom.buffer.codec.test.protocols.slice14c

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
 * -prep — sealed-parent + `@FramedBy(after = "header")`
 * probe tests.
 *
 * Five checks pin down the emit before the v3/v5 substitution lands:
 *   1. **A round-trip** — `(header = 0x10, a = 0x42, b = 0xABCD)` round-trips
 *      through the dispatcher and writes the expected `10 03 42 AB CD`.
 *   2. **B short round-trip** — `message = "hi"` round-trips with a 1-byte VBI prefix.
 *   3. **B long round-trip** — `message = "x".repeat(200)` round-trips and forces a
 *      2-byte VBI prefix, confirming the slicing scheme right-flushes a wider
 *      prefix into the slack region without shifting body bytes.
 *   4. **Dispatch correctness** — encoding an `A` then decoding the same bytes
 *      returns an `A`, not a `B`. Confirms the inherited-`@FramedBy` detection
 *      didn't break dispatch routing.
 *   5. **Strict bound rejection** — a hand-crafted wire form whose prefix
 *      claims more bytes than the body actually consumes throws
 *      `DecodeException` (the framework owns the bound; under-consumption
 *      is a wire-format error).
 */
class Slice14cFramedDispatchCodecTest {
    @Test
    fun aWireFormatMatchesExpected() {
        val original =
            Slice14cFramedDispatch.A(
                header = Slice14cTinyHeader(0x10u),
                a = 0x42u,
                b = 0xABCDu,
            )
        val read =
            Slice14cFramedDispatchCodec.encode(
                value = original,
                context = EncodeContext.Empty,
                factory = BufferFactory.Default,
            )
        val bytes = ByteArray(read.remaining())
        for (i in bytes.indices) bytes[i] = read.readByte()
        assertContentEquals(byteArrayOf(0x10, 0x03, 0x42, 0xAB.toByte(), 0xCD.toByte()), bytes)
    }

    @Test
    fun aRoundTrips() {
        val original =
            Slice14cFramedDispatch.A(
                header = Slice14cTinyHeader(0x10u),
                a = 0x55u,
                b = 0x1234u,
            )
        val read =
            Slice14cFramedDispatchCodec.encode(
                value = original,
                context = EncodeContext.Empty,
                factory = BufferFactory.Default,
            )
        val decoded = Slice14cFramedDispatchCodec.decode(read, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun bShortRoundTrips1ByteVbi() {
        val original =
            Slice14cFramedDispatch.B(
                header = Slice14cTinyHeader(0x20u),
                message = "hi",
            )
        val read =
            Slice14cFramedDispatchCodec.encode(
                value = original,
                context = EncodeContext.Empty,
                factory = BufferFactory.Default,
            )
        // header(1) + vbi(1) + length-prefix(2) + body(2) = 6 bytes.
        assertEquals(6, read.remaining())
        val decoded = Slice14cFramedDispatchCodec.decode(read, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun bLongRoundTrips2ByteVbi() {
        // body = 2 (length prefix) + 200 ('x's) = 202 ≥ 128 → VBI = 2 bytes.
        val original =
            Slice14cFramedDispatch.B(
                header = Slice14cTinyHeader(0x20u),
                message = "x".repeat(200),
            )
        val read =
            Slice14cFramedDispatchCodec.encode(
                value = original,
                context = EncodeContext.Empty,
                factory = BufferFactory.Default,
            )
        // header(1) + vbi(2) + body(202) = 205 bytes.
        assertEquals(205, read.remaining())
        val decoded = Slice14cFramedDispatchCodec.decode(read, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun strictBoundCheckRejectsOverlongPrefix() {
        // A is 3 body bytes (UByte + UShort). Hand-craft a wire form whose
        // prefix claims 5 bytes — under-consumption must throw.
        val wire = byteArrayOf(0x10, 0x05, 0x42, 0xAB.toByte(), 0xCD.toByte(), 0x00, 0x00)
        val buffer = BufferFactory.Default.allocate(wire.size, ByteOrder.BIG_ENDIAN)
        buffer.writeBytes(wire)
        buffer.resetForRead()
        val ex =
            assertFailsWith<DecodeException> {
                Slice14cFramedDispatchCodec.decode(buffer, DecodeContext.Empty)
            }
        assertTrue(
            ex.message!!.contains("@FramedBy"),
            "expected @FramedBy diagnostic, got: ${ex.message}",
        )
    }
}
