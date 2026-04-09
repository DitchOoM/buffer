package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.Payload
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes

/*
 * Minimal protocol that combines @Payload with a discriminator field
 * in the variant constructor — the exact pattern from PAYLOAD_DISCRIMINATOR_BUG.md.
 *
 * Wire format:
 * ```
 * byte1: type (dispatch value) + flags
 * byte2: flags / reserved
 * payload: remaining bytes, decoded by caller
 * ```
 */

/** Two-byte header used as the dispatch discriminator. */
@ProtocolMessage
data class FrameHeader(
    val byte1: UByte,
    val byte2: UByte,
) {
    @DispatchValue val type: Int get() = byte1.toInt()
}

/**
 * Sealed dispatch where payload variants include the discriminator
 * as a constructor field. This is the pattern that triggers the bug:
 * the generated decode references `context` without it being a parameter.
 */
@DispatchOn(FrameHeader::class)
@ProtocolMessage
sealed interface DispatchedFrame {
    @PacketType(0x01)
    @ProtocolMessage
    data class Data<@Payload T>(
        val header: FrameHeader,
        @RemainingBytes val payload: T,
    ) : DispatchedFrame

    @PacketType(0x02)
    @ProtocolMessage
    data class Control(
        val header: FrameHeader,
        @RemainingBytes val message: String,
    ) : DispatchedFrame
}
