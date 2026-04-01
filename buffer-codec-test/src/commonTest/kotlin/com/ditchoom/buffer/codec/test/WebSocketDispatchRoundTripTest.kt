package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.test.protocols.WsControlFrame
import com.ditchoom.buffer.codec.test.protocols.WsControlFrameCodec
import com.ditchoom.buffer.codec.test.protocols.WsControlFrameCloseCodec
import com.ditchoom.buffer.codec.test.protocols.WsControlFramePingCodec
import com.ditchoom.buffer.codec.test.protocols.WsControlFramePongCodec
import com.ditchoom.buffer.codec.test.protocols.WsOpcodeByte
import com.ditchoom.buffer.codec.testRoundTrip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * WebSocket control frame dispatch tests (RFC 6455 §5.5).
 * Validates bottom-nibble @DispatchOn (opcode = raw & 0x0F).
 */
class WebSocketDispatchRoundTripTest {
    // ========== Opcode byte extraction ==========

    @Test
    fun opcodeExtractionClose() {
        val byte = WsOpcodeByte(0x88u) // FIN=1, opcode=8
        assertEquals(8, byte.opcode)
        assertTrue(byte.fin)
    }

    @Test
    fun opcodeExtractionPing() {
        val byte = WsOpcodeByte(0x89u) // FIN=1, opcode=9
        assertEquals(9, byte.opcode)
        assertTrue(byte.fin)
    }

    @Test
    fun opcodeExtractionIgnoresUpperNibble() {
        // FIN=0, RSV1=1, opcode=9 → still dispatches as Ping
        val byte = WsOpcodeByte(0x49u)
        assertEquals(9, byte.opcode)
        assertEquals(false, byte.fin)
        assertEquals(true, byte.rsv1)
    }

    // ========== Sub-codec round-trips ==========

    @Test
    fun closeSubCodecRoundTrip() {
        val original = WsControlFrame.Close(4u, 1000u, "OK")
        val decoded = WsControlFrameCloseCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun pingSubCodecRoundTrip() {
        val original = WsControlFrame.Ping(5u, "hello")
        val decoded = WsControlFramePingCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun pongSubCodecRoundTrip() {
        val original = WsControlFrame.Pong(5u, "hello")
        val decoded = WsControlFramePongCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    // ========== Dispatch decode from spec bytes ==========

    @Test
    fun dispatchDecodesCloseFromSpecBytes() {
        val buffer = BufferFactory.Default.allocate(32, ByteOrder.BIG_ENDIAN)
        buffer.writeByte(0x88.toByte()) // FIN=1, opcode=0x08 (Close)
        buffer.writeByte(4) // payload length = 4 (2 bytes status + 2 bytes reason)
        buffer.writeShort(1000.toShort()) // close code: normal
        buffer.writeBytes("OK".encodeToByteArray()) // reason
        buffer.resetForRead()

        val decoded = WsControlFrameCodec.decode(buffer)
        assertTrue(decoded is WsControlFrame.Close)
        assertEquals(1000u.toUShort(), decoded.statusCode)
        assertEquals("OK", decoded.reason)
    }

    @Test
    fun dispatchDecodesPingFromSpecBytes() {
        val buffer = BufferFactory.Default.allocate(32, ByteOrder.BIG_ENDIAN)
        buffer.writeByte(0x89.toByte()) // FIN=1, opcode=0x09 (Ping)
        buffer.writeByte(4) // payload length
        buffer.writeBytes("ping".encodeToByteArray())
        buffer.resetForRead()

        val decoded = WsControlFrameCodec.decode(buffer)
        assertTrue(decoded is WsControlFrame.Ping)
        assertEquals("ping", decoded.data)
    }

    @Test
    fun dispatchDecodesPongFromSpecBytes() {
        val buffer = BufferFactory.Default.allocate(32, ByteOrder.BIG_ENDIAN)
        buffer.writeByte(0x8A.toByte()) // FIN=1, opcode=0x0A (Pong)
        buffer.writeByte(4) // payload length
        buffer.writeBytes("pong".encodeToByteArray())
        buffer.resetForRead()

        val decoded = WsControlFrameCodec.decode(buffer)
        assertTrue(decoded is WsControlFrame.Pong)
        assertEquals("pong", decoded.data)
    }

    @Test
    fun dispatchUnknownOpcodeThrows() {
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        buffer.writeByte(0x83.toByte()) // FIN=1, opcode=0x03 (reserved)
        buffer.resetForRead()

        assertFailsWith<IllegalArgumentException> {
            WsControlFrameCodec.decode(buffer)
        }
    }

    // ========== Dispatch round-trip ==========

    @Test
    fun closeDispatchRoundTrip() {
        val original: WsControlFrame = WsControlFrame.Close(6u, 1001u, "away")
        val decoded = WsControlFrameCodec.testRoundTrip(original)
        assertTrue(decoded is WsControlFrame.Close)
        assertEquals(original, decoded)
    }

    @Test
    fun pingDispatchRoundTrip() {
        val original: WsControlFrame = WsControlFrame.Ping(0u, "")
        val decoded = WsControlFrameCodec.testRoundTrip(original)
        assertTrue(decoded is WsControlFrame.Ping)
        assertEquals(original, decoded)
    }

    @Test
    fun pongDispatchRoundTrip() {
        val original: WsControlFrame = WsControlFrame.Pong(3u, "abc")
        val decoded = WsControlFrameCodec.testRoundTrip(original)
        assertTrue(decoded is WsControlFrame.Pong)
        assertEquals(original, decoded)
    }

    @Test
    fun encodeWritesCorrectOpcodeWithFin() {
        val buffer = BufferFactory.Default.allocate(32, ByteOrder.BIG_ENDIAN)
        WsControlFrameCodec.encode(buffer, WsControlFrame.Ping(0u, ""))
        // First byte should be 0x89 (FIN=1, opcode=9)
        assertEquals(0x89.toByte(), buffer[0])
    }
}
