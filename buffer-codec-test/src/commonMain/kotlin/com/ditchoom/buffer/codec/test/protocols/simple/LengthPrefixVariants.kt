package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.codec.annotations.LengthPrefix
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage

/**
 * Prefix-width coverage. Each variant exercises one
 * `LengthPrefix` value on a `String` body so the prefix encode/decode
 * paths are tested for all three widths the enum exposes (locked
 * decision row 14 — `Byte`/`Short`/`Int` only, no further widening).
 */

@ProtocolMessage
data class BytePrefixedString(
    @LengthPrefixed(LengthPrefix.Byte) val name: String,
)

@ProtocolMessage
data class IntPrefixedString(
    @LengthPrefixed(LengthPrefix.Int) val name: String,
)
