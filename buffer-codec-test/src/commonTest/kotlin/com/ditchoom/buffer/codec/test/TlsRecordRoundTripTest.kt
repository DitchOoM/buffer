package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.test.protocols.TlsContentType
import com.ditchoom.buffer.codec.test.protocols.TlsProtocolVersion
import com.ditchoom.buffer.codec.test.protocols.TlsRecord
import com.ditchoom.buffer.codec.test.protocols.TlsRecordAlertCodec
import com.ditchoom.buffer.codec.test.protocols.TlsRecordChangeCipherSpecCodec
import com.ditchoom.buffer.codec.test.protocols.TlsRecordCodec
import com.ditchoom.buffer.codec.test.protocols.TlsRecordHandshakeCodec
import com.ditchoom.buffer.codec.testRoundTrip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * TLS record layer tests (RFC 5246 §6.2.1).
 *
 * Each test verifies exact wire bytes against the spec:
 *   type(1) + version(2) + length(2) + fragment(length)
 */
class TlsRecordRoundTripTest {
    // ========== Value class tests ==========

    @Test
    fun contentTypeConstants() {
        assertEquals(20, TlsContentType.CHANGE_CIPHER_SPEC.type)
        assertEquals(21, TlsContentType.ALERT.type)
        assertEquals(22, TlsContentType.HANDSHAKE.type)
        assertEquals(23, TlsContentType.APPLICATION_DATA.type)
    }

    @Test
    fun protocolVersionTls12() {
        val tls12 = TlsProtocolVersion.TLS_1_2
        assertEquals(3u.toUByte(), tls12.major) // 0x03
        assertEquals(3u.toUByte(), tls12.minor) // 0x03
    }

    // ========== Sub-codec round-trips ==========

    @Test
    fun changeCipherSpecRoundTrip() {
        // RFC 5246 §7.1: length is always 1, message is always 1
        val original = TlsRecord.ChangeCipherSpec(TlsProtocolVersion.TLS_1_2, 1u, 1u)
        val decoded = TlsRecordChangeCipherSpecCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun alertRoundTrip() {
        // RFC 5246 §7.2: length is always 2
        val original = TlsRecord.Alert(TlsProtocolVersion.TLS_1_2, 2u, 2u, 40u) // fatal, handshake_failure
        val decoded = TlsRecordAlertCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun handshakeRoundTrip() {
        val original = TlsRecord.Handshake(TlsProtocolVersion.TLS_1_2, 5u, "hello")
        val buffer = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
        TlsRecordHandshakeCodec.encode(buffer, original) { buf, s -> buf.writeString(s) }
        buffer.resetForRead()
        val decoded = TlsRecordHandshakeCodec.decode<String>(buffer) { pr -> pr.readString(pr.remaining()) }
        assertEquals(original.version, decoded.version)
        assertEquals(original.length, decoded.length)
        assertEquals("hello", decoded.fragment)
    }

    // ========== Verify exact wire bytes ==========

    @Test
    fun changeCipherSpecExactWireBytes() {
        // RFC 5246 §7.1: 14 03 03 00 01 01
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        TlsRecordCodec.encode(buffer, TlsRecord.ChangeCipherSpec(TlsProtocolVersion.TLS_1_2, 1u, 1u))
        assertEquals(0x14.toByte(), buffer[0]) // content type 20
        assertEquals(0x03.toByte(), buffer[1]) // version major
        assertEquals(0x03.toByte(), buffer[2]) // version minor
        assertEquals(0x00.toByte(), buffer[3]) // length high byte
        assertEquals(0x01.toByte(), buffer[4]) // length low byte
        assertEquals(0x01.toByte(), buffer[5]) // message = 1
        assertEquals(6, buffer.position()) // exactly 6 bytes
    }

    @Test
    fun alertExactWireBytes() {
        // Fatal close_notify: 15 03 03 00 02 02 00
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        TlsRecordCodec.encode(buffer, TlsRecord.Alert(TlsProtocolVersion.TLS_1_2, 2u, 2u, 0u))
        assertEquals(0x15.toByte(), buffer[0]) // content type 21
        assertEquals(0x03.toByte(), buffer[1]) // version major
        assertEquals(0x03.toByte(), buffer[2]) // version minor
        assertEquals(0x00.toByte(), buffer[3]) // length high
        assertEquals(0x02.toByte(), buffer[4]) // length low
        assertEquals(0x02.toByte(), buffer[5]) // level = fatal
        assertEquals(0x00.toByte(), buffer[6]) // description = close_notify
        assertEquals(7, buffer.position()) // exactly 7 bytes
    }

    // ========== Decode from spec-compliant wire bytes ==========

    @Test
    fun decodeChangeCipherSpecFromSpecBytes() {
        // 14 03 03 00 01 01
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        buffer.writeByte(0x14) // content type
        buffer.writeByte(0x03)
        buffer.writeByte(0x03) // TLS 1.2
        buffer.writeShort(1.toShort()) // length = 1
        buffer.writeByte(0x01) // message = 1
        buffer.resetForRead()

        val decoded = TlsRecordCodec.decode(buffer)
        assertTrue(decoded is TlsRecord.ChangeCipherSpec)
        assertEquals(TlsProtocolVersion.TLS_1_2, decoded.version)
        assertEquals(1u.toUShort(), decoded.length)
        assertEquals(1u.toUByte(), decoded.message)
    }

    @Test
    fun decodeAlertFromSpecBytes() {
        // 15 03 03 00 02 02 28
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        buffer.writeByte(0x15) // content type
        buffer.writeByte(0x03)
        buffer.writeByte(0x03) // TLS 1.2
        buffer.writeShort(2.toShort()) // length = 2
        buffer.writeByte(0x02) // fatal
        buffer.writeByte(0x28) // handshake_failure (40)
        buffer.resetForRead()

        val decoded = TlsRecordCodec.decode(buffer)
        assertTrue(decoded is TlsRecord.Alert)
        assertEquals(2u.toUByte(), decoded.level)
        assertEquals(40u.toUByte(), decoded.description)
    }

    @Test
    fun unknownContentTypeThrows() {
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        buffer.writeByte(99)
        buffer.resetForRead()
        assertFailsWith<IllegalArgumentException> {
            TlsRecordCodec.decode(buffer)
        }
    }

    // ========== Full dispatch round-trip ==========

    @Test
    fun changeCipherSpecDispatchRoundTrip() {
        val original: TlsRecord = TlsRecord.ChangeCipherSpec(TlsProtocolVersion.TLS_1_0, 1u, 1u)
        val decoded = TlsRecordCodec.testRoundTrip(original)
        assertTrue(decoded is TlsRecord.ChangeCipherSpec)
        assertEquals(original, decoded)
    }

    @Test
    fun alertDispatchRoundTrip() {
        val original: TlsRecord = TlsRecord.Alert(TlsProtocolVersion.TLS_1_2, 2u, 1u, 0u) // warning, close_notify
        val decoded = TlsRecordCodec.testRoundTrip(original)
        assertTrue(decoded is TlsRecord.Alert)
        assertEquals(original, decoded)
    }
}
