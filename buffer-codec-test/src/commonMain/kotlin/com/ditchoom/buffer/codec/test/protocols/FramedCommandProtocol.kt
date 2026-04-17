package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.LengthFrom
import com.ditchoom.buffer.codec.annotations.LengthPrefix
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes

/**
 * Wire: [payload variant][checksum:2]
 * The wrapper holds a variable-size payload (sealed @ProtocolMessage) plus a trailing checksum.
 * When used as a nested field bounded by an outer length, the whole block decodes within that
 * length region and the checksum is recovered from the tail.
 */
@ProtocolMessage(wireOrder = Endianness.Little)
data class PayloadWithChecksum(
    val payload: CommandPayload,
    val checksum: UShort,
)

/**
 * Wire: [counter:2][length:2][body:length bytes]
 * `length` covers the whole `body` (payload + checksum). Users compute `length = body.sizeOf()`
 * before building a `FramedCommand`.
 */
@ProtocolMessage(wireOrder = Endianness.Little)
data class FramedCommand(
    val counter: UShort,
    val length: UShort,
    @LengthFrom("length") val body: PayloadWithChecksum,
)

/** Length-prefixed variant: a 2-byte prefix written before the nested body. */
@ProtocolMessage(wireOrder = Endianness.Little)
data class LengthPrefixedCommand(
    val counter: UShort,
    @LengthPrefixed(LengthPrefix.Short) val body: PayloadWithChecksum,
)

/** Remaining-bytes variant: nested body consumes everything left in the buffer. */
@ProtocolMessage(wireOrder = Endianness.Little)
data class RemainingBytesCommand(
    val counter: UShort,
    @RemainingBytes val body: PayloadWithChecksum,
)
