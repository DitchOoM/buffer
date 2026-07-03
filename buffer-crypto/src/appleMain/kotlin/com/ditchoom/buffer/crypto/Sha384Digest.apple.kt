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
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import platform.CoreCrypto.CC_SHA384_Final
import platform.CoreCrypto.CC_SHA384_Init
import platform.CoreCrypto.CC_SHA384_Update
import platform.CoreCrypto.CC_SHA512_CTX
import platform.posix.memset

/**
 * Apple SHA-384 backed by CommonCrypto (`CC_SHA384`). SHA-384 shares SHA-512's context type
 * ([CC_SHA512_CTX]) with a distinct init. Reads and writes via buffer pointers — no arrays.
 */
actual class Sha384Digest actual constructor() : AutoCloseable {
    private val ctx = nativeHeap.alloc<CC_SHA512_CTX>()
    private var finalized = false

    init {
        CC_SHA384_Init(ctx.ptr)
    }

    actual fun update(input: ReadBuffer): Sha384Digest {
        check(!finalized) { "digest already finalized" }
        input.withRemainingBytes { ptr, len -> CC_SHA384_Update(ctx.ptr, ptr, len.convert()) }
        return this
    }

    actual fun digestInto(dest: WriteBuffer) {
        check(!finalized) { "digest already finalized" }
        // CommonCrypto writes the 48-byte digest straight into the destination buffer's memory.
        // withWritablePointer validates capacity BEFORE invoking the block, so a too-small
        // dest throws here with the ctx untouched — the finalize stays retryable (C1).
        dest.withWritablePointer(SHA384_DIGEST_BYTES) { ptr -> CC_SHA384_Final(ptr.reinterpret(), ctx.ptr) }
        finalized = true
        releaseCtx()
    }

    actual override fun close() {
        if (finalized) return
        finalized = true
        releaseCtx()
    }

    // The context still holds absorbed state; zero it before returning the allocation.
    private fun releaseCtx() {
        memset(ctx.ptr, 0, sizeOf<CC_SHA512_CTX>().convert())
        nativeHeap.free(ctx.rawPtr)
    }
}
