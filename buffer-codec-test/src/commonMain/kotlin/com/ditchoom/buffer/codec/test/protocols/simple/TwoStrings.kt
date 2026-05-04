package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage

/**
 * Stage E slice 5a doctrine vector — two consecutive
 * `@LengthPrefixed val: String` fields. The smallest shape that
 * forces the sequential peek walk: a single LPS String would
 * collapse to the existing terminal-LPS peek; two require a true
 * prefix chase (peek prefix-1, advance, peek prefix-2, advance,
 * total).
 *
 * Wire layout (default `LengthPrefix.Short`, 2-byte big-endian
 * prefix per field):
 *   - `TwoStrings("hi", "yo")` → `00 02 68 69   00 02 79 6F`
 *
 * Both encoded bodies use the BackPatch pattern (Locked Decision
 * row 15); the encoder's position-restore-past-body is what makes
 * non-terminal LPS String safe — the second field's encode picks
 * up where the first field's encode left off.
 */
@ProtocolMessage
data class TwoStrings(
    @LengthPrefixed val first: String,
    @LengthPrefixed val second: String,
)
