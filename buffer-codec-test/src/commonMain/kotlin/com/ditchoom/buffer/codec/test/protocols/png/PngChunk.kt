package com.ditchoom.buffer.codec.test.protocols.png

import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.UseCodec
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryData
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryDataCodec

/**
 * Issue #151 part 2 fixture — PNG chunk (W3C PNG 2nd ed §5):
 *
 * ```
 * +-----------+-----------+----------+----------+
 * | length:4  | type:4    | data:N   | crc:4    |
 * +-----------+-----------+----------+----------+
 * ```
 *
 * Big-endian throughout. `length` covers `data` only (not `type` or
 * `crc`); `crc` is computed over `type + data`. Exercises non-terminal
 * `@RemainingBytes` followed by a fixed 4-byte CRC trailer — the
 * analyzer subtracts the trailer's wire bytes from `buffer.limit()`
 * before the body loop so the body stops where the CRC begins. Without
 * The body loop would consume the CRC bytes and the trailer's
 * read would underflow.
 *
 * The user supplies `length` to match `data.bytes.size` and `crc` to
 * match the CRC32 of `type + data` — the codec doesn't recompute
 * either. The caller bounds the buffer to the
 * chunk's extent (typically via an outer reader that reads `length`
 * first).
 *
 * Retyped `data` from `List<UByte>` to
 * `BinaryData` ('s `@RemainingBytes @UseCodec(C::class)
 * val: P` shape). PNG chunk data is genuinely opaque bytes from the
 * outer codec's perspective (the data structure is determined by the
 * `type` byte and is interpreted by chunk-specific decoders, not by
 * the wrapper). Replacing the boxed `List<UByte>` with a
 * `BinaryData`-wrapped `ByteArray` makes the copy explicit (the user-
 * supplied [BinaryDataCodec] owns the memory semantics) and avoids the
 * per-byte JS-heap-object cost the prior shape paid.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class PngChunk(
    val length: UInt,
    val type: UInt,
    @RemainingBytes @UseCodec(BinaryDataCodec::class) val data: BinaryData,
    val crc: UInt,
)
