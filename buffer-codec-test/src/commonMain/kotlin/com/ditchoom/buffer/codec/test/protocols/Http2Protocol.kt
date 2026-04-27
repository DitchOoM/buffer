package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage

/**
 * Dispatcher exception thrown when an HTTP/2 frame-type byte does not map to any of the
 * registered variants below. KSP only needs the FQN to resolve at compile time.
 */
class Http2ProtocolException(
    message: String,
) : RuntimeException(message)

/**
 * HTTP/2 frame type byte (RFC 7540 §6). Pure raw-byte dispatch — no `@DispatchOn`, so each
 * variant claims its discriminator by literal byte value via `@PacketType(wire = N)`.
 *
 * Each variant carries trivial payload so the codec round-trip exercises both the dispatcher
 * (which writes the discriminator byte itself) and the inner field codec.
 */
@ProtocolMessage(
    onUnknownDiscriminator = "com.ditchoom.buffer.codec.test.protocols.Http2ProtocolException",
)
sealed interface Http2Frame {
    @PacketType(wire = 0x00)
    @ProtocolMessage
    data class Data(
        val streamId: UInt,
        val flags: UByte,
    ) : Http2Frame

    @PacketType(wire = 0x01)
    @ProtocolMessage
    data class Headers(
        val streamId: UInt,
        val flags: UByte,
    ) : Http2Frame

    @PacketType(wire = 0x02)
    @ProtocolMessage
    data class Priority(
        val streamId: UInt,
        val weight: UByte,
    ) : Http2Frame

    @PacketType(wire = 0x03)
    @ProtocolMessage
    data class RstStream(
        val streamId: UInt,
        val errorCode: UInt,
    ) : Http2Frame

    @PacketType(wire = 0x04)
    @ProtocolMessage
    data class Settings(
        val flags: UByte,
    ) : Http2Frame

    @PacketType(wire = 0x05)
    @ProtocolMessage
    data class PushPromise(
        val streamId: UInt,
        val promisedStreamId: UInt,
    ) : Http2Frame

    @PacketType(wire = 0x06)
    @ProtocolMessage
    data class Ping(
        val opaque: ULong,
    ) : Http2Frame

    @PacketType(wire = 0x07)
    @ProtocolMessage
    data class Goaway(
        val lastStreamId: UInt,
        val errorCode: UInt,
    ) : Http2Frame

    @PacketType(wire = 0x08)
    @ProtocolMessage
    data class WindowUpdate(
        val streamId: UInt,
        val windowSizeIncrement: UInt,
    ) : Http2Frame

    @PacketType(wire = 0x09)
    @ProtocolMessage
    data class Continuation(
        val streamId: UInt,
        val flags: UByte,
    ) : Http2Frame
}
