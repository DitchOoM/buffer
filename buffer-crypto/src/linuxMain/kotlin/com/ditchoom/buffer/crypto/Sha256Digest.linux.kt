@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed actual file
@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_sha256_final
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_sha256_new
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_sha256_update
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret

/** Linux SHA-256 backed by BoringSSL (`SHA256_*`). Reads and writes via buffer pointers — no arrays. */
actual class Sha256Digest actual constructor() : AutoCloseable {
    private val ctx = bcl_sha256_new() ?: error("SHA256_Init failed")
    private var finalized = false

    actual fun update(input: ReadBuffer): Sha256Digest {
        check(!finalized) { "digest already finalized" }
        input.withRemainingBytes { ptr, len -> bcl_sha256_update(ctx, ptr.reinterpret(), len.convert()) }
        return this
    }

    actual fun digestInto(dest: WriteBuffer) {
        check(!finalized) { "digest already finalized" }
        finalized = true
        dest.withWritablePointer(SHA256_DIGEST_BYTES) { ptr -> bcl_sha256_final(ctx, ptr.reinterpret()) }
    }

    actual override fun close() {
        if (finalized) return
        finalized = true
        // The shim has no free-without-final: finalize into discarded stack scratch, which
        // cleanses and frees the ctx.
        memScoped {
            val scratch = allocArray<UByteVar>(SHA256_DIGEST_BYTES)
            bcl_sha256_final(ctx, scratch)
        }
    }
}
