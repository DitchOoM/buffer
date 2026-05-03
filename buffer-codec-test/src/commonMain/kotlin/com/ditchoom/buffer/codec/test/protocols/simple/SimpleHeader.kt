package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage

/**
 * Stage C doctrine vector for `@LengthPrefixed val: String`. A signed
 * `Int` id followed by a UTF-8 length-prefixed name; the prefix is the
 * default `LengthPrefix.Short` (2-byte big-endian) carrying the body's
 * UTF-8 byte count.
 *
 * Validates simultaneously: signed-scalar widening (Stage A's emitter
 * accepted only unsigned scalars), `WireSize.BackPatch` emission for
 * the terminal String (the first stage to actually produce
 * `BackPatch`), and the prefix-peek `peekFrameSize` walk through a
 * variable-length terminal.
 */
@ProtocolMessage
data class SimpleHeader(
    val id: Int,
    @LengthPrefixed val name: String,
)
