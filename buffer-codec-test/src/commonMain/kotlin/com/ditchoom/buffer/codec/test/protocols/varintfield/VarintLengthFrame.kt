package com.ditchoom.buffer.codec.test.protocols.varintfield

import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.UseCodec
import com.ditchoom.buffer.codec.test.protocols.quic.QuicVarintCodec

/**
 * Field-level variable-width vector (stage 2): a `@UseCodec` scalar whose codec
 * implements `VariableLengthCodec` (the test-support [QuicVarintCodec]),
 * followed by a fixed-size suffix.
 *
 * Proves the generic, encoding-agnostic plumbing: decode/encode delegate to the
 * codec (already worked), and `peekFrameSize` now frames the message as
 * `width(value) + 1` — the self-delimiting width read from the leading bytes
 * plus the trailing `tag` byte — instead of collapsing to `NoFraming`. No QUIC
 * knowledge is in the processor; the encoding is entirely in the consumer codec.
 */
@ProtocolMessage
data class VarintLengthFrame(
    @UseCodec(QuicVarintCodec::class) val value: ULong,
    val tag: UByte,
)
