package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.test.protocols.Http2Frame
import com.ditchoom.buffer.codec.test.protocols.Http2FrameCodec
import com.ditchoom.buffer.codec.test.protocols.Http2ProtocolException
import com.ditchoom.buffer.codec.testRoundTrip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pure raw-byte `@PacketType(wire = N)` dispatch — no `@DispatchOn`. The dispatcher reads a
 * single byte, matches it against each variant's claimed wire value, and throws the
 * configured [Http2ProtocolException] for any byte not claimed by a variant.
 *
 * Each of the 10 RFC 7540 §6 frame types (DATA..CONTINUATION, 0x00..0x09) is covered by a
 * round-trip; the first unclaimed byte (0x0A) asserts the `onUnknownDiscriminator` wiring.
 */
class Http2DispatchTest {
    @Test
    fun dataRoundTrip() {
        val original: Http2Frame = Http2Frame.Data(streamId = 1u, flags = 0u)
        val decoded = Http2FrameCodec.testRoundTrip(original)
        assertTrue(decoded is Http2Frame.Data)
        assertEquals(original, decoded)
    }

    @Test
    fun headersRoundTrip() {
        val original: Http2Frame = Http2Frame.Headers(streamId = 3u, flags = 0x04u)
        val decoded = Http2FrameCodec.testRoundTrip(original)
        assertTrue(decoded is Http2Frame.Headers)
        assertEquals(original, decoded)
    }

    @Test
    fun priorityRoundTrip() {
        val original: Http2Frame = Http2Frame.Priority(streamId = 5u, weight = 16u)
        val decoded = Http2FrameCodec.testRoundTrip(original)
        assertTrue(decoded is Http2Frame.Priority)
        assertEquals(original, decoded)
    }

    @Test
    fun rstStreamRoundTrip() {
        val original: Http2Frame = Http2Frame.RstStream(streamId = 7u, errorCode = 0x08u)
        val decoded = Http2FrameCodec.testRoundTrip(original)
        assertTrue(decoded is Http2Frame.RstStream)
        assertEquals(original, decoded)
    }

    @Test
    fun settingsRoundTrip() {
        val original: Http2Frame = Http2Frame.Settings(flags = 0x01u)
        val decoded = Http2FrameCodec.testRoundTrip(original)
        assertTrue(decoded is Http2Frame.Settings)
        assertEquals(original, decoded)
    }

    @Test
    fun pushPromiseRoundTrip() {
        val original: Http2Frame = Http2Frame.PushPromise(streamId = 9u, promisedStreamId = 11u)
        val decoded = Http2FrameCodec.testRoundTrip(original)
        assertTrue(decoded is Http2Frame.PushPromise)
        assertEquals(original, decoded)
    }

    @Test
    fun pingRoundTrip() {
        val original: Http2Frame = Http2Frame.Ping(opaque = 0xDEADBEEF_CAFEBABEu)
        val decoded = Http2FrameCodec.testRoundTrip(original)
        assertTrue(decoded is Http2Frame.Ping)
        assertEquals(original, decoded)
    }

    @Test
    fun goawayRoundTrip() {
        val original: Http2Frame = Http2Frame.Goaway(lastStreamId = 13u, errorCode = 0x02u)
        val decoded = Http2FrameCodec.testRoundTrip(original)
        assertTrue(decoded is Http2Frame.Goaway)
        assertEquals(original, decoded)
    }

    @Test
    fun windowUpdateRoundTrip() {
        val original: Http2Frame = Http2Frame.WindowUpdate(streamId = 15u, windowSizeIncrement = 65535u)
        val decoded = Http2FrameCodec.testRoundTrip(original)
        assertTrue(decoded is Http2Frame.WindowUpdate)
        assertEquals(original, decoded)
    }

    @Test
    fun continuationRoundTrip() {
        val original: Http2Frame = Http2Frame.Continuation(streamId = 17u, flags = 0x04u)
        val decoded = Http2FrameCodec.testRoundTrip(original)
        assertTrue(decoded is Http2Frame.Continuation)
        assertEquals(original, decoded)
    }

    // ========== Encoder writes the raw discriminator byte ==========

    @Test
    fun dataEncodeWritesDiscriminator0x00() {
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        Http2FrameCodec.encode(buffer, Http2Frame.Data(streamId = 1u, flags = 0u), EncodeContext.Empty)
        assertEquals(0x00.toByte(), buffer[0])
    }

    @Test
    fun continuationEncodeWritesDiscriminator0x09() {
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        Http2FrameCodec.encode(buffer, Http2Frame.Continuation(streamId = 17u, flags = 0u), EncodeContext.Empty)
        assertEquals(0x09.toByte(), buffer[0])
    }

    // ========== onUnknownDiscriminator wiring ==========

    @Test
    fun unknownDiscriminator0x0AThrowsConfiguredException() {
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        buffer.writeByte(0x0A.toByte())
        buffer.resetForRead()
        assertFailsWith<Http2ProtocolException> {
            Http2FrameCodec.decode(buffer, DecodeContext.Empty)
        }
    }
}
