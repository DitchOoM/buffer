package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.WhenTrue

/**
 * Reuses FixedHeaderByte (top nibble = packet type, bottom nibble = flags) to exercise
 * mixed sealed dispatch: some variants carry no discriminator field (the dispatcher
 * writes a literal wire byte), and [MixedPublish] carries a FixedHeaderByte field that
 * self-encodes via the variant's own codec — proving per-variant skip of the dispatcher
 * wire-write works.
 */
@DispatchOn(FixedHeaderByte::class)
@ProtocolMessage
sealed interface MixedDispatchPacket {
    /** Packet type 1 (CONNECT): wire byte 0x10, no discriminator field. */
    @PacketType(value = 1, wire = 0x10)
    @ProtocolMessage
    data class MixedConnect(
        val protocolLevel: UByte,
        val keepAlive: UShort,
    ) : MixedDispatchPacket

    /**
     * Packet type 3 (PUBLISH): wire byte varies — the variant carries the full header
     * byte so dup/qos/retain in the low nibble round-trip.
     */
    @PacketType(value = 3)
    @ProtocolMessage
    data class MixedPublish(
        val header: FixedHeaderByte,
        @LengthPrefixed val topicName: String,
        @WhenTrue("header.hasPacketIdentifier") val packetId: UShort? = null,
        @RemainingBytes val payload: String,
    ) : MixedDispatchPacket
}

/** Convenience extractor — FixedHeaderByte.flags already exposes the low nibble. */
val FixedHeaderByte.qos: Int get() = (flags shr 1) and 0x3

val FixedHeaderByte.hasPacketIdentifier: Boolean get() = qos > 0
