package com.ditchoom.buffer.codec.test.protocols.mysql

import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import kotlin.jvm.JvmInline

/**
 * MySQL Client/Server Protocol packet header — the four bytes that
 * precede every MySQL protocol packet. See the MySQL Internals manual,
 * "Protocol::Packet" (https://dev.mysql.com/doc/dev/mysql-server/latest/page_protocol_basic_packets.html).
 *
 * Wire layout (4 bytes, all little-endian):
 *
 * ```text
 * +---------------+---------------+---------------+---------------+
 * |             payloadLength (3 LE)              | sequenceId (1)|
 * +---------------+---------------+---------------+---------------+
 * ```
 *
 * Vector for the **value-class-of-raw** ergonomic, modeled on
 * the existing `MqttFixedHeader` pattern. The whole 4-byte header is
 * one `UInt` on the wire; `payloadLength` and `sequenceId` are derived
 * via bit-shift getters in user code, so the codec only ever sees a
 * single LE 4-byte read/write — no `@WireBytes(3)` annotation needed
 * because the structure packs cleanly into a 32-bit raw scalar.
 *
 * Compare with [com.ditchoom.buffer.codec.test.protocols.flv.FlvTagHeader],
 * whose 11-byte structure is too large to pack into a 64-bit scalar
 * and therefore needs `@WireBytes(3)` for its three 24-bit fields.
 *
 * The two shapes — value-class-of-raw with bit-packed getters, and
 * data-class-with-`@WireBytes`-fields — are first-class alternatives
 * for distinct wire shapes; this fixture shows the former.
 */
@JvmInline
@ProtocolMessage(wireOrder = Endianness.Little)
value class MySqlPacketHeader(
    val raw: UInt,
) {
    /** Bytes 0–2 LE: payload length, max 16 MiB - 1. */
    val payloadLength: UInt get() = raw and 0xFFFFFFu

    /** Byte 3: sequence id, wraps 0..255 within a multi-packet response. */
    val sequenceId: UByte get() = ((raw shr 24) and 0xFFu).toUByte()

    companion object {
        /** Compose a header from logical parts. Throws if `payloadLength` exceeds 24 bits. */
        fun of(
            payloadLength: UInt,
            sequenceId: UByte,
        ): MySqlPacketHeader {
            require(payloadLength <= 0xFFFFFFu) {
                "MySQL payload length is 24-bit; got $payloadLength"
            }
            return MySqlPacketHeader((sequenceId.toUInt() shl 24) or payloadLength)
        }
    }
}
