package com.ditchoom.buffer.codec.test.protocols.slice14cgeneric

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.test.protocols.payload.TextPayload
import com.ditchoom.buffer.codec.test.protocols.payload.TextPayloadCodec
import com.ditchoom.buffer.codec.test.protocols.slice14c.Slice14cTinyHeader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Phase J.M.5 slice 14c — generic-payload `@FramedBy` probe tests.
 *
 * Six checks pin down the generic-emit shape before the v3/v5 substitution
 * touches a generic fixture:
 *   1. **Headered wire format** — non-generic variant under the generic
 *      sealed parent writes `10 03 42 AB CD`.
 *   2. **Headered round-trip** — confirms the generic dispatcher routes
 *      to a non-generic variant correctly.
 *   3. **WithPayload short round-trip** — generic variant with a 1-byte
 *      VBI; exercises generic-variant × inherited `@FramedBy` decode and
 *      generic-dispatcher framed encode.
 *   4. **WithPayload long round-trip** — generic variant with a 2-byte
 *      VBI (200-byte payload); confirms slice 14b right-flush behaviour
 *      survives the generic emit.
 *   5. **Dispatch correctness** — encoding a Headered then decoding the
 *      same bytes returns Headered, not WithPayload, through the generic
 *      dispatcher.
 *   6. **Strict bound rejection** — a hand-crafted wire form whose prefix
 *      claims more bytes than the body actually consumes throws
 *      `DecodeException` (the framework owns the bound).
 */
class Slice14cGenericFramedDispatchCodecTest {
    @Test
    fun headeredWireFormatMatchesExpected() {
        val original =
            Slice14cGenericFramedDispatch.Headered(
                header = Slice14cTinyHeader(0x10u),
                a = 0x42u,
                b = 0xABCDu,
            )
        val codec = Slice14cGenericFramedDispatchCodec(TextPayloadCodec)
        val read =
            codec.encode(
                value = original,
                context = EncodeContext.Empty,
                factory = BufferFactory.Default,
            )
        val bytes = ByteArray(read.remaining())
        for (i in bytes.indices) bytes[i] = read.readByte()
        assertContentEquals(byteArrayOf(0x10, 0x03, 0x42, 0xAB.toByte(), 0xCD.toByte()), bytes)
    }

    @Test
    fun headeredRoundTrips() {
        val original =
            Slice14cGenericFramedDispatch.Headered(
                header = Slice14cTinyHeader(0x10u),
                a = 0x55u,
                b = 0x1234u,
            )
        val codec = Slice14cGenericFramedDispatchCodec(TextPayloadCodec)
        val read =
            codec.encode(
                value = original,
                context = EncodeContext.Empty,
                factory = BufferFactory.Default,
            )
        val decoded = codec.decode(read, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun withPayloadShortRoundTrips1ByteVbi() {
        val original =
            Slice14cGenericFramedDispatch.WithPayload(
                header = Slice14cTinyHeader(0x20u),
                topic = "t",
                payload = TextPayload("hi"),
            )
        val codec = Slice14cGenericFramedDispatchCodec(TextPayloadCodec)
        val read =
            codec.encode(
                value = original,
                context = EncodeContext.Empty,
                factory = BufferFactory.Default,
            )
        // header(1) + vbi(1) + topic-LP(2) + topic(1) + payload(2) = 7 bytes.
        assertEquals(7, read.remaining())
        val decoded = codec.decode(read, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun withPayloadLongRoundTrips2ByteVbi() {
        // body = 2 (topic-LP) + 1 (topic) + 200 (payload) = 203 ≥ 128 → VBI = 2 bytes.
        val original =
            Slice14cGenericFramedDispatch.WithPayload(
                header = Slice14cTinyHeader(0x20u),
                topic = "t",
                payload = TextPayload("x".repeat(200)),
            )
        val codec = Slice14cGenericFramedDispatchCodec(TextPayloadCodec)
        val read =
            codec.encode(
                value = original,
                context = EncodeContext.Empty,
                factory = BufferFactory.Default,
            )
        // header(1) + vbi(2) + body(203) = 206 bytes.
        assertEquals(206, read.remaining())
        val decoded = codec.decode(read, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun dispatchRoutesToHeaderedNotWithPayload() {
        val original =
            Slice14cGenericFramedDispatch.Headered(
                header = Slice14cTinyHeader(0x10u),
                a = 0x01u,
                b = 0x0203u,
            )
        val codec = Slice14cGenericFramedDispatchCodec(TextPayloadCodec)
        val read =
            codec.encode(
                value = original,
                context = EncodeContext.Empty,
                factory = BufferFactory.Default,
            )
        val decoded = codec.decode(read, DecodeContext.Empty)
        assertIs<Slice14cGenericFramedDispatch.Headered>(decoded)
    }

    @Test
    fun strictBoundCheckRejectsOverlongPrefix() {
        // Headered is 3 body bytes (UByte + UShort). Wire form whose prefix
        // claims 5 bytes — under-consumption must throw.
        val wire = byteArrayOf(0x10, 0x05, 0x42, 0xAB.toByte(), 0xCD.toByte(), 0x00, 0x00)
        val buffer = BufferFactory.Default.allocate(wire.size, ByteOrder.BIG_ENDIAN)
        buffer.writeBytes(wire)
        buffer.resetForRead()
        val codec = Slice14cGenericFramedDispatchCodec(TextPayloadCodec)
        val ex =
            assertFailsWith<DecodeException> {
                codec.decode(buffer, DecodeContext.Empty)
            }
        assertTrue(
            ex.message!!.contains("@FramedBy"),
            "expected @FramedBy diagnostic, got: ${ex.message}",
        )
    }
}
