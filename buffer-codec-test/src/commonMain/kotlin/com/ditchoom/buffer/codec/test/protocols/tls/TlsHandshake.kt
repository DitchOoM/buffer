package com.ditchoom.buffer.codec.test.protocols.tls

import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.LengthFrom
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.WireBytes

/**
 * Issue #151 part 1 (J.M.6.b) fixture — TLS 1.3 handshake message header
 * (RFC 8446 §4):
 *
 * ```
 * struct {
 *     HandshakeType msg_type;     // 1 byte: 1=ClientHello, 2=ServerHello, ...
 *     uint24 length;               // 3 bytes big-endian
 *     <body of `length` bytes>
 * } Handshake;
 * ```
 *
 * Exercises `@LengthFrom("length") val body: T : @ProtocolMessage` — the
 * length is read as a `uint24` (`@WireBytes(3) val length: UInt`,
 * big-endian) and bounds the nested body's decode region. The user
 * supplies `length` as the body's wire byte count on encode.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class TlsHandshake(
    val msgType: UByte,
    @WireBytes(3) val length: UInt,
    @LengthFrom("length") val body: TlsHandshakeBody,
)

/**
 * Body shape: 2-byte legacy version + variable random bytes (the real
 * TLS ClientHello has a 32-byte fixed random + variable trailing fields;
 * the fixture keeps the shape "fixed prefix + variable tail" but stays
 * minimal to focus on the framing.)
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class TlsHandshakeBody(
    val legacyVersion: UShort,
    @RemainingBytes val random: List<UByte>,
)

/**
 * Sealed-parent variant of the body: same outer framing
 * (`@LengthFrom("length") val body: TlsHandshakeSealedBody`), but the
 * body type is a sealed interface dispatching on `@PacketType`. Confirms
 * that `@LengthFrom` on a nested @ProtocolMessage works for sealed
 * parents (their codec is the dispatcher object) the same way it does
 * for data-class bodies.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class TlsHandshakeWithSealedBody(
    val msgType: UByte,
    @WireBytes(3) val length: UInt,
    @LengthFrom("length") val body: TlsHandshakeSealedBody,
)

@ProtocolMessage(wireOrder = Endianness.Big)
sealed interface TlsHandshakeSealedBody {
    @PacketType(0x01)
    @ProtocolMessage
    data class ClientHello(
        val legacyVersion: UShort,
    ) : TlsHandshakeSealedBody

    @PacketType(0x02)
    @ProtocolMessage
    data object HelloRequest : TlsHandshakeSealedBody
}
