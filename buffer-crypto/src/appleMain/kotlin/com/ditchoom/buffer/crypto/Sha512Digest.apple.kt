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
import platform.CoreCrypto.CC_SHA512_CTX
import platform.CoreCrypto.CC_SHA512_Final
import platform.CoreCrypto.CC_SHA512_Init
import platform.CoreCrypto.CC_SHA512_Update

/** Apple SHA-512 backed by CommonCrypto (`CC_SHA512`). Reads and writes via buffer pointers — no arrays. */
actual class Sha512Digest actual constructor() {
    private val ctx = nativeHeap.alloc<CC_SHA512_CTX>()
    private var finalized = false

    init {
        CC_SHA512_Init(ctx.ptr)
    }

    actual fun update(input: ReadBuffer): Sha512Digest {
        input.withRemainingBytes { ptr, len -> CC_SHA512_Update(ctx.ptr, ptr, len.convert()) }
        return this
    }

    actual fun digestInto(dest: WriteBuffer) {
        // CommonCrypto writes the 64-byte digest straight into the destination buffer's memory.
        dest.withWritablePointer(SHA512_DIGEST_BYTES) { ptr -> CC_SHA512_Final(ptr.reinterpret(), ctx.ptr) }
        if (!finalized) {
            finalized = true
            nativeHeap.free(ctx.rawPtr)
        }
    }
}
