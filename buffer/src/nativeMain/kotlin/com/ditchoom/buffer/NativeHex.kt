@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package com.ditchoom.buffer

import com.ditchoom.buffer.cinterop.buf_hex_decode
import com.ditchoom.buffer.cinterop.buf_hex_encode
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.convert
import kotlinx.cinterop.toCPointer

// Hex encode/decode computed entirely in C (raw pointer arithmetic, table lookup that Clang lowers to a
// NEON/SSE shuffle) — see buf_hex_encode / buf_hex_decode. Shared by every native NativeMemoryAccess
// buffer (linux NativeBuffer + slice, apple MutableDataBuffer + slice) so the cinterop wiring lives in
// ONE place and can't drift onto one backend while silently missing another (as apple was for hashRange).
//
// Both directions take the source via raw addresses; the destination is taken when it is also native
// memory (dest.nativeMemoryAccess), so the whole transform stays pointer-to-pointer with no per-element
// Kotlin dispatch. When the destination is NOT native (e.g. a managed ByteArrayBuffer), or would not
// fit, we fall back to the portable common-code path (encodeHexCommon / decodeHexFallback) — bit
// identical output, just without the C fast path.

private fun nativeHexEncodeAddr(
    srcAddress: Long,
    dstAddress: Long,
    length: Int,
    upperCase: Boolean,
) = buf_hex_encode(
    srcAddress.toCPointer<UByteVar>()!!,
    dstAddress.toCPointer<UByteVar>()!!,
    length.convert(),
    if (upperCase) 1 else 0,
)

private fun nativeHexDecodeAddr(
    srcAddress: Long,
    dstAddress: Long,
    length: Int,
): Long =
    buf_hex_decode(
        srcAddress.toCPointer<UByteVar>()!!,
        dstAddress.toCPointer<UByteVar>()!!,
        length.convert(),
    )

/**
 * Shared native [ReadBuffer.encodeHexInto] body. [srcAddress] is the address of this buffer's first
 * byte (`nativeAddress`); the caller has already range-checked `[offset, offset + length)`.
 */
internal fun ReadBuffer.nativeEncodeHexInto(
    srcAddress: Long,
    dest: WriteBuffer,
    offset: Int,
    length: Int,
    upperCase: Boolean,
) {
    val destNative = dest.nativeMemoryAccess
    val outBytes = length * 2
    if (destNative != null && outBytes <= dest.remaining()) {
        val destPos = dest.position()
        nativeHexEncodeAddr(srcAddress + offset, destNative.nativeAddress + destPos, length, upperCase)
        dest.position(destPos + outBytes)
    } else {
        encodeHexCommon(offset, length, upperCase, dest)
    }
}

/**
 * Shared native [ReadBuffer.decodeHexInto] body. [srcAddress] is the address of this buffer's first
 * byte (`nativeAddress`); the caller has already range-checked `[offset, offset + length)`.
 */
internal fun ReadBuffer.nativeDecodeHexInto(
    srcAddress: Long,
    dest: WriteBuffer,
    offset: Int,
    length: Int,
) {
    require(length and 1 == 0) { "hex input length ($length) must be even" }
    val destNative = dest.nativeMemoryAccess
    val outBytes = length / 2
    if (destNative != null && outBytes <= dest.remaining()) {
        val destPos = dest.position()
        val bad = nativeHexDecodeAddr(srcAddress + offset, destNative.nativeAddress + destPos, length)
        if (bad >= 0) {
            throw IllegalArgumentException("invalid hex character at index ${offset + bad}")
        }
        dest.position(destPos + outBytes)
    } else {
        decodeHexFallback(offset, length, { getUnchecked(it) }, { dest.writeByte(it) })
    }
}
