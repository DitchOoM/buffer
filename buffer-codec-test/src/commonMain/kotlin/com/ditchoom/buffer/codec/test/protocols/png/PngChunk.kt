package com.ditchoom.buffer.codec.test.protocols.png

import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes

/**
 * Issue #151 part 2 (J.M.6.c) fixture — PNG chunk (W3C PNG 2nd ed §5):
 *
 * ```
 * +-----------+-----------+----------+----------+
 * | length:4  | type:4    | data:N   | crc:4    |
 * +-----------+-----------+----------+----------+
 * ```
 *
 * Big-endian throughout. `length` covers `data` only (not `type` or
 * `crc`); `crc` is computed over `type + data`. Exercises non-terminal
 * `@RemainingBytes val data: List<UByte>` followed by a fixed 4-byte
 * CRC trailer — the analyzer subtracts the trailer's wire bytes from
 * `buffer.limit()` before the body loop so the body stops where the
 * CRC begins. Without J.M.6.c the body loop would consume the CRC
 * bytes and the trailer's read would underflow.
 *
 * The user supplies `length` to match `data.size` and `crc` to match
 * the CRC32 of `type + data` — the codec doesn't recompute either
 * (row 16 trust contract). The caller bounds the buffer to the chunk's
 * extent (typically via an outer reader that reads `length` first).
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class PngChunk(
    val length: UInt,
    val type: UInt,
    @RemainingBytes val data: List<UByte>,
    val crc: UInt,
)
