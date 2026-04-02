package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage

/** A @ProtocolMessage data class with a sealed interface field containing @Payload variants. */
@ProtocolMessage
data class Frame(
    val version: UByte,
    val chunk: AnimChunk,
)

/** Sealed inside sealed: outer dispatch routes to inner sealed interface. */
@ProtocolMessage
sealed interface Packet {
    @PacketType(0x01)
    data class Control(
        val flags: UByte,
    ) : Packet

    @PacketType(0x02)
    data class Media(
        val chunk: AnimChunk,
    ) : Packet
}
