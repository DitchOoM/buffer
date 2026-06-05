package com.ditchoom.buffer.codec.test.protocols.grpc

import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.LengthPrefix
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.UseCodec
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryData
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryDataCodec

/**
 * gRPC `Length-Prefixed-Message` framing (gRPC over HTTP/2, PROTOCOL-HTTP2.md):
 *
 * ```
 * Length-Prefixed-Message → Compressed-Flag (1 byte) Message-Length (4 bytes BE) Message
 * Compressed-Flag         → 0 / 1
 * Message-Length          → uint32 (big-endian / network order)
 * Message                 → *{binary octet}
 * ```
 *
 * Each gRPC message is carried inside HTTP/2 DATA frames as this self-delimiting
 * envelope: a 1-byte compression flag, then a 4-byte big-endian length, then
 * exactly that many opaque (protobuf) message bytes. The length measures the
 * message only — not the 5-byte prefix.
 *
 * Modeled with the framework-owned `@LengthPrefixed(LengthPrefix.Int)` 4-byte
 * prefix carrying the message's wire size, and the opaque message as a consumer-
 * decoded [BinaryData] payload (the protobuf body is interpreted by a
 * message-specific codec a layer up, not by the envelope). The generated
 * `peekFrameSize` therefore frames the whole envelope —
 * `Complete(1 + 4 + Message-Length)` — so a streaming reader can size each gRPC
 * message without consuming bytes. The length is owned by the framework, so the
 * `compressedFlag` + prefix shape round-trips byte-exactly against the spec.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class GrpcLengthPrefixedMessage(
    val compressedFlag: UByte,
    @LengthPrefixed(LengthPrefix.Int) @UseCodec(BinaryDataCodec::class) val message: BinaryData,
)
