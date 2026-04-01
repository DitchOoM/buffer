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
 * Validates spec-compliant encode/decode with identity @DispatchOn.
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
    fun protocolVersionEncoding() {
        val tls12 = TlsProtocolVersion.TLS_1_2
        assertEquals(3u.toUByte(), tls12.major)
        assertEquals(3u.toUByte(), tls12.minor)
    }

    // ========== Sub-codec round-trips ==========

    @Test
    fun changeCipherSpecRoundTrip() {
        val original = TlsRecord.ChangeCipherSpec(TlsProtocolVersion.TLS_1_2, 1u)
        val decoded = TlsRecordChangeCipherSpecCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun alertRoundTrip() {
        val original = TlsRecord.Alert(TlsProtocolVersion.TLS_1_2, 2u, 40u) // fatal, handshake_failure
        val decoded = TlsRecordAlertCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun handshakeRoundTrip() {
        val original = TlsRecord.Handshake(
            TlsProtocolVersion.TLS_1_2,
            5u,
            "hello",
        )
        val buffer = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
        TlsRecordHandshakeCodec.encode(buffer, original) { buf, s -> buf.writeString(s) }
        buffer.resetForRead()
        val decoded = TlsRecordHandshakeCodec.decode<String>(buffer) { pr -> pr.readString(pr.remaining()) }
        assertEquals(original.version, decoded.version)
        assertEquals(original.length, decoded.length)
        assertEquals("hello", decoded.fragment)
    }

    // ========== Dispatch decode from spec bytes ==========

    @Test
    fun dispatchDecodesChangeCipherSpec() {
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        buffer.writeByte(20) // ContentType.change_cipher_spec
        buffer.writeByte(3); buffer.writeByte(3) // TLS 1.2
        buffer.writeByte(1) // message = 1
        buffer.resetForRead()

        val decoded = TlsRecordCodec.decode(buffer)
        assertTrue(decoded is TlsRecord.ChangeCipherSpec)
        assertEquals(TlsProtocolVersion.TLS_1_2, decoded.version)
        assertEquals(1u.toUByte(), decoded.message)
    }

    @Test
    fun dispatchDecodesAlert() {
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        buffer.writeByte(21) // ContentType.alert
        buffer.writeByte(3); buffer.writeByte(3) // TLS 1.2
        buffer.writeByte(2) // fatal
        buffer.writeByte(40) // handshake_failure
        buffer.resetForRead()

        val decoded = TlsRecordCodec.decode(buffer)
        assertTrue(decoded is TlsRecord.Alert)
        assertEquals(2u.toUByte(), decoded.level)
        assertEquals(40u.toUByte(), decoded.description)
    }

    @Test
    fun dispatchUnknownContentTypeThrows() {
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        buffer.writeByte(99) // unknown
        buffer.resetForRead()

        assertFailsWith<IllegalArgumentException> {
            TlsRecordCodec.decode(buffer)
        }
    }

    // ========== Dispatch round-trip ==========

    @Test
    fun changeCipherSpecDispatchRoundTrip() {
        val original: TlsRecord = TlsRecord.ChangeCipherSpec(TlsProtocolVersion.TLS_1_0, 1u)
        val decoded = TlsRecordCodec.testRoundTrip(original)
        assertTrue(decoded is TlsRecord.ChangeCipherSpec)
        assertEquals(original, decoded)
    }

    @Test
    fun alertDispatchRoundTrip() {
        val original: TlsRecord = TlsRecord.Alert(TlsProtocolVersion.TLS_1_2, 1u, 0u) // warning, close_notify
        val decoded = TlsRecordCodec.testRoundTrip(original)
        assertTrue(decoded is TlsRecord.Alert)
        assertEquals(original, decoded)
    }

    @Test
    fun encodeWritesCorrectContentTypeByte() {
        val buffer = BufferFactory.Default.allocate(32, ByteOrder.BIG_ENDIAN)
        TlsRecordCodec.encode(buffer, TlsRecord.Alert(TlsProtocolVersion.TLS_1_2, 2u, 50u))
        assertEquals(21.toByte(), buffer[0]) // ContentType.alert = 21
    }
}
