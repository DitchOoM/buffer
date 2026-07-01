@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed actual file
@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import platform.CoreCrypto.CCHmacContext
import platform.CoreCrypto.CCHmacFinal
import platform.CoreCrypto.CCHmacInit
import platform.CoreCrypto.CCHmacUpdate
import platform.CoreCrypto.kCCHmacAlgSHA512
import platform.posix.memset

/** Apple HMAC-SHA512 backed by CommonCrypto (`CCHmac`). Reads and writes via buffer pointers — no arrays. */
actual class HmacSha512Mac actual constructor(
    key: ReadBuffer,
) : AutoCloseable {
    private val ctx = nativeHeap.alloc<CCHmacContext>()
    private var finalized = false

    init {
        if (key.remaining() == 0) {
            CCHmacInit(ctx.ptr, kCCHmacAlgSHA512, null, 0.convert())
        } else {
            key.withRemainingBytes { ptr, len -> CCHmacInit(ctx.ptr, kCCHmacAlgSHA512, ptr, len.convert()) }
        }
    }

    actual fun update(input: ReadBuffer): HmacSha512Mac {
        check(!finalized) { "mac already finalized" }
        input.withRemainingBytes { ptr, len -> CCHmacUpdate(ctx.ptr, ptr, len.convert()) }
        return this
    }

    actual fun doFinalInto(dest: WriteBuffer) {
        check(!finalized) { "mac already finalized" }
        finalized = true
        // CommonCrypto writes the 64-byte tag straight into the destination buffer's memory.
        dest.withWritablePointer(SHA512_DIGEST_BYTES) { ptr -> CCHmacFinal(ctx.ptr, ptr) }
        releaseCtx()
    }

    actual override fun close() {
        if (finalized) return
        finalized = true
        releaseCtx()
    }

    // The context holds key-derived ipad/opad state; zero it before returning the allocation.
    private fun releaseCtx() {
        memset(ctx.ptr, 0, sizeOf<CCHmacContext>().convert())
        nativeHeap.free(ctx.rawPtr)
    }
}
