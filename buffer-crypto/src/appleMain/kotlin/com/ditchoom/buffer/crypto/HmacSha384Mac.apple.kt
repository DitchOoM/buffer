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
import platform.CoreCrypto.kCCHmacAlgSHA384
import platform.posix.memset

/** Apple HMAC-SHA384 backed by CommonCrypto (`CCHmac`). Reads and writes via buffer pointers — no arrays. */
actual class HmacSha384Mac actual constructor(
    key: ReadBuffer,
) : AutoCloseable {
    private val ctx = nativeHeap.alloc<CCHmacContext>()
    private var finalized = false

    init {
        if (key.remaining() == 0) {
            CCHmacInit(ctx.ptr, kCCHmacAlgSHA384, null, 0.convert())
        } else {
            key.withRemainingBytes { ptr, len -> CCHmacInit(ctx.ptr, kCCHmacAlgSHA384, ptr, len.convert()) }
        }
    }

    actual fun update(input: ReadBuffer): HmacSha384Mac {
        check(!finalized) { "mac already finalized" }
        input.withRemainingBytes { ptr, len -> CCHmacUpdate(ctx.ptr, ptr, len.convert()) }
        return this
    }

    actual fun doFinalInto(dest: WriteBuffer) {
        check(!finalized) { "mac already finalized" }
        // CommonCrypto writes the 48-byte tag straight into the destination buffer's memory.
        // withWritablePointer validates capacity BEFORE invoking the block, so a too-small
        // dest throws here with the ctx untouched — the finalize stays retryable (C1).
        dest.withWritablePointer(SHA384_DIGEST_BYTES) { ptr -> CCHmacFinal(ctx.ptr, ptr) }
        finalized = true
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
