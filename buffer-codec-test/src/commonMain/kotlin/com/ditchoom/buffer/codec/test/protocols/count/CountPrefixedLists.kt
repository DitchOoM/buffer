package com.ditchoom.buffer.codec.test.protocols.count

import com.ditchoom.buffer.codec.annotations.Count
import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage

/*
 * `@Count` fixtures — element-count-prefixed lists. A `@Count` list rides on
 * the wire as a self-delimiting `varint(N)` element count followed by exactly
 * `N` self-delimiting elements (each via its own codec). This is the
 * element-count complement to the byte-length list framings
 * (`@RemainingBytes` / `@LengthFrom` / `@LengthPrefixed`), which bound a list
 * by a byte span and drain to a buffer limit.
 *
 * The count varint is the shipped unsigned-LEB128 `UnsignedVarIntCodec` — the
 * same self-delimiting encoding an enum ordinal rides on — so counts 0..127
 * cost one byte.
 */

/** Fixed-width element: 3 bytes (UShort BE + UByte). */
@ProtocolMessage(wireOrder = Endianness.Big)
data class CountPoint(
    val x: UShort,
    val y: UByte,
)

/**
 * Fixed-width-element `@Count` list. Wire = id (1 byte) + varint(N) +
 * 3 × N bytes.
 *
 * ```text
 *   id   count   point_0           point_1 …
 *   01   02      00 10 00          00 20 01
 * ```
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class CountFixedList(
    val id: UByte,
    @Count val points: List<CountPoint>,
)

/** Variable-width element: a length-prefixed UTF-8 name. */
@ProtocolMessage(wireOrder = Endianness.Big)
data class CountNamed(
    @LengthPrefixed val name: String,
)

/**
 * Variable-width-element `@Count` list. Each element carries its own 2-byte
 * length prefix, so the element width varies — exercising the self-delimiting
 * count loop independent of any byte bound.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class CountVariableList(
    @Count val names: List<CountNamed>,
)

/**
 * Non-terminal `@Count` list followed by a trailing fixed-size field —
 * demonstrating that the field-count framing is self-delimiting and need not
 * be the last constructor parameter (unlike the drain-to-limit list shapes).
 *
 * ```text
 *   count   point_0     point_1     trailer (UInt BE)
 *   02      00 01 02    00 03 04    DE AD BE EF
 * ```
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class CountThenTrailer(
    @Count val points: List<CountPoint>,
    val trailer: UInt,
)
