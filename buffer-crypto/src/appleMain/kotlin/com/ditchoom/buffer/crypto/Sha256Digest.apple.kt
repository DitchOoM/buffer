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
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update

/** Apple SHA-256 backed by CommonCrypto (`CC_SHA256`). Reads and writes via buffer pointers — no arrays. */
actual class Sha256Digest actual constructor() {
    private val ctx = nativeHeap.alloc<CC_SHA256_CTX>()
    private var finalized = false

    init {
        CC_SHA256_Init(ctx.ptr)
    }

    actual fun update(input: ReadBuffer): Sha256Digest {
        input.withRemainingBytes { ptr, len -> CC_SHA256_Update(ctx.ptr, ptr, len.convert()) }
        return this
    }

    actual fun digestInto(dest: WriteBuffer) {
        // CommonCrypto writes the 32-byte digest straight into the destination buffer's memory.
        dest.withWritablePointer(SHA256_DIGEST_BYTES) { ptr -> CC_SHA256_Final(ptr.reinterpret(), ctx.ptr) }
        if (!finalized) {
            finalized = true
            nativeHeap.free(ctx.rawPtr)
        }
    }
}
