package com.ditchoom.buffer.codec.test.protocols.enums

import com.ditchoom.buffer.codec.annotations.EnumDefault
import com.ditchoom.buffer.codec.annotations.ProtocolMessage

/**
 * Enum-field fixtures. An enum field's `ordinal` rides the wire as an unsigned LEB128 varint
 * ([com.ditchoom.buffer.codec.UnsignedVarIntCodec]); enums need no annotation of their own (the
 * field's generated codec encodes them inline).
 *
 * [Color] carries an `@EnumDefault` sink, so an unknown (newer) ordinal decodes to [Color.Unknown]
 * — the forward-compatibility property. [Priority] has none, so an out-of-range ordinal throws.
 */
enum class Color {
    @EnumDefault
    Unknown,
    Red,
    Green,
    Blue,
}

enum class Priority {
    Low,
    Medium,
    High,
}

@ProtocolMessage
data class Style(
    val color: Color,
    val priority: Priority,
    val weight: UByte,
)

/**
 * Single-enum message: one self-delimiting varint ordinal + a fixed suffix, so its codec frames
 * via `peekFrameSize` (unlike [Style], whose two enums collapse to `NoFraming` — the same
 * single-variable-field limitation the varint `@UseCodec` peek has).
 */
@ProtocolMessage
data class Tagged(
    val level: Priority,
    val id: UByte,
)
