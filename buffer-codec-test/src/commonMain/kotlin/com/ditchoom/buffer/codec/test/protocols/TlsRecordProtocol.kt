package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.LengthFrom
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.Payload
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import kotlin.jvm.JvmInline

/**
 * TLS record layer protocol (RFC 5246 §6.2.1).
 *
 * Wire format (every record, no exceptions):
 * ```
 * ContentType type;           // 1 byte
 * ProtocolVersion version;    // 2 bytes (major.minor)
 * uint16 length;              // 2 bytes (max 2^14)
 * opaque fragment[length];    // variable
 * ```
 *
 * Identity @DispatchOn — content type byte IS the dispatch value (no extraction).
 */

/** TLS content type byte. Identity dispatch — value equals raw byte. */
@JvmInline
@ProtocolMessage
value class TlsContentType(val raw: UByte) {
    @DispatchValue
    val type: Int get() = raw.toInt()

    companion object {
        val CHANGE_CIPHER_SPEC = TlsContentType(20u)
        val ALERT = TlsContentType(21u)
        val HANDSHAKE = TlsContentType(22u)
        val APPLICATION_DATA = TlsContentType(23u)
    }
}

/** TLS protocol version (major.minor). */
@ProtocolMessage
data class TlsProtocolVersion(
    val major: UByte,
    val minor: UByte,
) {
    companion object {
        val TLS_1_0 = TlsProtocolVersion(3u, 1u)
        val TLS_1_1 = TlsProtocolVersion(3u, 2u)
        val TLS_1_2 = TlsProtocolVersion(3u, 3u)
        val TLS_1_3 = TlsProtocolVersion(3u, 3u) // TLS 1.3 uses 0x0303 on the wire
    }
}

/**
 * TLS record dispatched by content type.
 * Every variant includes version + length + fragment — matching the spec exactly.
 */
@DispatchOn(TlsContentType::class)
@ProtocolMessage
sealed interface TlsRecord {
    /**
     * ContentType 20: Change Cipher Spec (RFC 5246 §7.1).
     * Fragment is always exactly 1 byte with value 1.
     * Wire: 14 03 03 00 01 01
     */
    @PacketType(20)
    @ProtocolMessage
    data class ChangeCipherSpec(
        val version: TlsProtocolVersion,
        val length: UShort, // always 1
        val message: UByte, // always 1
    ) : TlsRecord

    /**
     * ContentType 21: Alert (RFC 5246 §7.2).
     * Fragment is always exactly 2 bytes: level + description.
     * Wire: 15 03 03 00 02 LL DD
     */
    @PacketType(21)
    @ProtocolMessage
    data class Alert(
        val version: TlsProtocolVersion,
        val length: UShort, // always 2
        val level: UByte, // 1=warning, 2=fatal
        val description: UByte,
    ) : TlsRecord

    /**
     * ContentType 22: Handshake (RFC 5246 §7.4).
     * Fragment is a variable-length handshake message.
     * Wire: 16 03 03 LL LL [fragment...]
     */
    @PacketType(22)
    @ProtocolMessage
    data class Handshake<@Payload P>(
        val version: TlsProtocolVersion,
        val length: UShort,
        @LengthFrom("length") val fragment: P,
    ) : TlsRecord

    /**
     * ContentType 23: Application Data (RFC 5246 §6.2.1).
     * Fragment is encrypted application data.
     * Wire: 17 03 03 LL LL [fragment...]
     */
    @PacketType(23)
    @ProtocolMessage
    data class ApplicationData<@Payload P>(
        val version: TlsProtocolVersion,
        val length: UShort,
        @LengthFrom("length") val fragment: P,
    ) : TlsRecord
}
