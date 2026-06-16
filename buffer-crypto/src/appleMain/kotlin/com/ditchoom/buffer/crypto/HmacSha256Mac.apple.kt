@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import platform.CoreCrypto.CCHmacContext
import platform.CoreCrypto.CCHmacFinal
import platform.CoreCrypto.CCHmacInit
import platform.CoreCrypto.CCHmacUpdate
import platform.CoreCrypto.kCCHmacAlgSHA256

/** Apple HMAC-SHA256 backed by CommonCrypto (`CCHmac`). Reads and writes via buffer pointers — no arrays. */
actual class HmacSha256Mac actual constructor(
    key: ReadBuffer,
) {
    private val ctx = nativeHeap.alloc<CCHmacContext>()
    private var finalized = false

    init {
        if (key.remaining() == 0) {
            CCHmacInit(ctx.ptr, kCCHmacAlgSHA256, null, 0.convert())
        } else {
            key.withRemainingBytes { ptr, len -> CCHmacInit(ctx.ptr, kCCHmacAlgSHA256, ptr, len.convert()) }
        }
    }

    actual fun update(input: ReadBuffer): HmacSha256Mac {
        input.withRemainingBytes { ptr, len -> CCHmacUpdate(ctx.ptr, ptr, len.convert()) }
        return this
    }

    actual fun doFinalInto(dest: WriteBuffer) {
        // CommonCrypto writes the 32-byte tag straight into the destination buffer's memory.
        dest.withWritablePointer(SHA256_DIGEST_BYTES) { ptr -> CCHmacFinal(ctx.ptr, ptr) }
        if (!finalized) {
            finalized = true
            nativeHeap.free(ctx.rawPtr)
        }
    }
}
