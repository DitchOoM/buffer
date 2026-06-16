@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package com.ditchoom.buffer

import com.ditchoom.buffer.cinterop.buf_base64_decode
import com.ditchoom.buffer.cinterop.buf_base64_encode
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.convert
import kotlinx.cinterop.toCPointer

// Base64 encode/decode computed entirely in C (raw pointer arithmetic) — see buf_base64_encode /
// buf_base64_decode. Shared by every native NativeMemoryAccess buffer (linux NativeBuffer + slice, apple
// MutableDataBuffer + slice), mirroring NativeHex.kt. The destination is taken when it is also native
// memory; otherwise (or when it would not fit) we fall back to the portable common-code path
// (encodeBase64Fallback / decodeBase64Fallback) — bit-identical output, just without the C fast path.

private fun nativeBase64EncodeAddr(
    srcAddress: Long,
    dstAddress: Long,
    length: Int,
    urlSafe: Boolean,
    padded: Boolean,
) = buf_base64_encode(
    srcAddress.toCPointer<UByteVar>()!!,
    dstAddress.toCPointer<UByteVar>()!!,
    length.convert(),
    if (urlSafe) 1 else 0,
    if (padded) 1 else 0,
)

private fun nativeBase64DecodeAddr(
    srcAddress: Long,
    dstAddress: Long,
    length: Int,
): Long =
    buf_base64_decode(
        srcAddress.toCPointer<UByteVar>()!!,
        dstAddress.toCPointer<UByteVar>()!!,
        length.convert(),
    )

/**
 * Shared native [ReadBuffer.encodeBase64Into] body. [srcAddress] is this buffer's first-byte address
 * (`nativeAddress`); the caller has already range-checked `[offset, offset + length)`.
 */
internal fun ReadBuffer.nativeEncodeBase64Into(
    srcAddress: Long,
    dest: WriteBuffer,
    offset: Int,
    length: Int,
    urlSafe: Boolean,
    padded: Boolean,
) {
    val destNative = dest.nativeMemoryAccess
    val outBytes = base64EncodedLength(length, padded)
    if (destNative != null && outBytes <= dest.remaining()) {
        val destPos = dest.position()
        nativeBase64EncodeAddr(srcAddress + offset, destNative.nativeAddress + destPos, length, urlSafe, padded)
        dest.position(destPos + outBytes)
    } else {
        encodeBase64Fallback(offset, length, urlSafe, padded, { getUnchecked(it) }, { dest.writeByte(it) })
    }
}

/**
 * Shared native [ReadBuffer.decodeBase64Into] body. [srcAddress] is this buffer's first-byte address
 * (`nativeAddress`); the caller has already range-checked `[offset, offset + length)`.
 */
internal fun ReadBuffer.nativeDecodeBase64Into(
    srcAddress: Long,
    dest: WriteBuffer,
    offset: Int,
    length: Int,
) {
    val destNative = dest.nativeMemoryAccess
    // The exact decoded size is data-dependent (padding); the no-padding case is the upper bound.
    if (destNative != null && base64DecodedMaxLength(length) <= dest.remaining()) {
        val destPos = dest.position()
        val produced = nativeBase64DecodeAddr(srcAddress + offset, destNative.nativeAddress + destPos, length)
        if (produced < 0) {
            throw IllegalArgumentException("invalid base64 character at index ${offset + (-produced - 1)}")
        }
        dest.position(destPos + produced.toInt())
    } else {
        decodeBase64Fallback(offset, length, { getUnchecked(it) }, { dest.writeByte(it) })
    }
}
