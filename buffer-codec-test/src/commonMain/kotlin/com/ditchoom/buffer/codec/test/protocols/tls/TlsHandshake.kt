package com.ditchoom.buffer.codec.test.protocols.tls

import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.LengthFrom
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.UseCodec
import com.ditchoom.buffer.codec.annotations.WireBytes
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryData
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryDataCodec

/**
 * Issue #151 part 1 fixture — TLS 1.3 handshake message header
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
 * Body shape: 2-byte legacy version + variable trailing bytes (the real
 * TLS ClientHello has a 32-byte fixed random + variable trailing
 * fields; the fixture keeps the shape "fixed prefix + variable tail"
 * but stays minimal to focus on the framing.)
 *
 * Retyped the trailing tail from `List<UByte>`
 * to `BinaryData` ('s `@RemainingBytes @UseCodec(C::class)
 * val: P` shape). The fixture's purpose is `@LengthFrom`-bounded
 * variable-tail framing, not per-byte structure inspection — opaque
 * `BinaryData` matches that intent and avoids the per-byte JS-heap-
 * object cost.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class TlsHandshakeBody(
    val legacyVersion: UShort,
    @RemainingBytes @UseCodec(BinaryDataCodec::class) val random: BinaryData,
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
    /** RFC 8446 §4 HandshakeType `client_hello(1)` — a data-class body. */
    @PacketType(0x01)
    @ProtocolMessage
    data class ClientHello(
        val legacyVersion: UShort,
    ) : TlsHandshakeSealedBody

    /**
     * RFC 8446 §4.5 HandshakeType `end_of_early_data(5)` — `struct {} EndOfEarlyData`,
     * an empty body. Exercises the empty `data object` singleton-dispatch path under a
     * `@LengthFrom`-bounded sealed parent with a spec-faithful HandshakeType value.
     */
    @PacketType(0x05)
    @ProtocolMessage
    data object EndOfEarlyData : TlsHandshakeSealedBody
}
