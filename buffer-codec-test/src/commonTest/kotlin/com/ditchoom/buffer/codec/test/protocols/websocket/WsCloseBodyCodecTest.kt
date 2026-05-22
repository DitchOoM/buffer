package com.ditchoom.buffer.codec.test.protocols.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * RFC 6455 §5.5.1 / §7.4.1 close-body wire shape: 2-byte BE status code followed by an
 * optional UTF-8 reason that consumes the remainder of the frame payload.
 *
 * The body is decoded only when the parent Close frame's `@When("remaining >= 2")`
 * predicate fires; that gating is exercised in [WsFrameDispatchCodecTest]. These tests
 * focus on the body codec in isolation.
 */
class WsCloseBodyCodecTest {
    /** Status code 1000 ("normal closure") with no reason — minimal close body, 2 bytes. */
    @Test
    fun statusOnlyEncodesAsTwoBeBytes() {
        val body = WsCloseBody(statusCode = 1000u.toUShort(), reason = "")
        val buf = BufferFactory.Default.allocate(2)
        WsCloseBodyCodec.encode(buf, body, EncodeContext.Empty)
        assertEquals(2, buf.position())
        buf.resetForRead()
        assertEquals(0x03.toByte(), buf.readByte(), "status BE high byte")
        assertEquals(0xE8.toByte(), buf.readByte(), "status BE low byte = 1000")

        buf.resetForRead()
        buf.setLimit(2)
        val decoded = WsCloseBodyCodec.decode(buf, DecodeContext.Empty)
        assertEquals(1000u.toUShort(), decoded.statusCode)
        assertEquals("", decoded.reason)
    }

    /** Status code + ASCII reason — tests `@RemainingBytes String` consumption. */
    @Test
    fun statusAndAsciiReasonRoundTrips() {
        val body = WsCloseBody(statusCode = 1001u.toUShort(), reason = "going away")
        val totalSize = 2 + "going away".length
        val buf = BufferFactory.Default.allocate(totalSize)
        WsCloseBodyCodec.encode(buf, body, EncodeContext.Empty)
        assertEquals(totalSize, buf.position())

        buf.resetForRead()
        buf.setLimit(totalSize)
        val decoded = WsCloseBodyCodec.decode(buf, DecodeContext.Empty)
        assertEquals(1001u.toUShort(), decoded.statusCode)
        assertEquals("going away", decoded.reason)
    }

    /**
     * Multi-byte UTF-8 reason — checks that `@RemainingBytes String` reads UTF-8 by byte
     * count (not codepoint count). "héllo" = 6 bytes in UTF-8 (h, é=2 bytes, l, l, o).
     */
    @Test
    fun statusAndUtf8ReasonRoundTrips() {
        val reason = "héllo"
        val body = WsCloseBody(statusCode = 1011u.toUShort(), reason = reason)
        val reasonBytes = reason.encodeToByteArray()
        assertEquals(6, reasonBytes.size, "sanity: 'héllo' is 6 UTF-8 bytes")

        val totalSize = 2 + reasonBytes.size
        val buf = BufferFactory.Default.allocate(totalSize)
        WsCloseBodyCodec.encode(buf, body, EncodeContext.Empty)
        assertEquals(totalSize, buf.position())

        buf.resetForRead()
        buf.setLimit(totalSize)
        val decoded = WsCloseBodyCodec.decode(buf, DecodeContext.Empty)
        assertEquals(1011u.toUShort(), decoded.statusCode)
        assertEquals(reason, decoded.reason)
    }

    /**
     * Highest-bit status code — RFC 6455 §7.4.2 reserves 4000-4999 for application use.
     * Confirms `UShort` round-trips past 0x7FFF without sign-extension.
     */
    @Test
    fun highStatusCodeRoundTrips() {
        val body = WsCloseBody(statusCode = 4999u.toUShort(), reason = "app-defined")
        val totalSize = 2 + "app-defined".length
        val buf = BufferFactory.Default.allocate(totalSize)
        WsCloseBodyCodec.encode(buf, body, EncodeContext.Empty)

        buf.resetForRead()
        buf.setLimit(totalSize)
        val decoded = WsCloseBodyCodec.decode(buf, DecodeContext.Empty)
        assertEquals(4999u.toUShort(), decoded.statusCode)
        assertEquals("app-defined", decoded.reason)
    }
}
