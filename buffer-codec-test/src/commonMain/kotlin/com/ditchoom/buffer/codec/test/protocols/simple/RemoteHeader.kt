package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.codec.annotations.LengthFrom
import com.ditchoom.buffer.codec.annotations.ProtocolMessage

/**
 * Doctrine vector — `@LengthFrom("siblingField") val:
 * String` in its non-adjacent shape.
 *
 * The `payloadLength` carrier sits at the head of the message;
 * intermediate non-length fields (`flags`, `correlationId`)
 * separate it from the body. The user is responsible for keeping
 * `payloadLength` consistent with the UTF-8 byte length of
 * `payload`; the codec trusts that contract per row 16 (a
 * cross-check would allocate a `ByteArray` per encode).
 *
 * Wire layout (UByte, UByte, UInt big-endian, then `payloadLength`
 * UTF-8 bytes):
 *   - `RemoteHeader(2u, 0x00u, 1u, "hi")` →
 *     `00 02   00   00 00 00 01   68 69`
 *
 * Adjacency note: `payloadLength` and `payload` are non-adjacent
 * (separated by `flags` and `correlationId`), so this fixture
 * does not trip R1's adjacent-`@LengthFrom` migration suggestion.
 */
@ProtocolMessage
data class RemoteHeader(
    val payloadLength: UShort,
    val flags: UByte,
    val correlationId: UInt,
    @LengthFrom("payloadLength") val payload: String,
)
