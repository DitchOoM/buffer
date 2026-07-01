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
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update
import platform.posix.memset

/** Apple SHA-256 backed by CommonCrypto (`CC_SHA256`). Reads and writes via buffer pointers — no arrays. */
actual class Sha256Digest actual constructor() : AutoCloseable {
    private val ctx = nativeHeap.alloc<CC_SHA256_CTX>()
    private var finalized = false

    init {
        CC_SHA256_Init(ctx.ptr)
    }

    actual fun update(input: ReadBuffer): Sha256Digest {
        check(!finalized) { "digest already finalized" }
        input.withRemainingBytes { ptr, len -> CC_SHA256_Update(ctx.ptr, ptr, len.convert()) }
        return this
    }

    actual fun digestInto(dest: WriteBuffer) {
        check(!finalized) { "digest already finalized" }
        finalized = true
        // CommonCrypto writes the 32-byte digest straight into the destination buffer's memory.
        dest.withWritablePointer(SHA256_DIGEST_BYTES) { ptr -> CC_SHA256_Final(ptr.reinterpret(), ctx.ptr) }
        releaseCtx()
    }

    actual override fun close() {
        if (finalized) return
        finalized = true
        releaseCtx()
    }

    // The context still holds absorbed state; zero it before returning the allocation.
    private fun releaseCtx() {
        memset(ctx.ptr, 0, sizeOf<CC_SHA256_CTX>().convert())
        nativeHeap.free(ctx.rawPtr)
    }
}
