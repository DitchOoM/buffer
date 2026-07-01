@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed actual file
@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_sha512_final
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_sha512_new
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_sha512_update
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret

/** Linux SHA-512 backed by BoringSSL (`SHA512_*`). Reads and writes via buffer pointers — no arrays. */
actual class Sha512Digest actual constructor() : AutoCloseable {
    private val ctx = bcl_sha512_new() ?: error("SHA512_Init failed")
    private var finalized = false

    actual fun update(input: ReadBuffer): Sha512Digest {
        check(!finalized) { "digest already finalized" }
        input.withRemainingBytes { ptr, len -> bcl_sha512_update(ctx, ptr.reinterpret(), len.convert()) }
        return this
    }

    actual fun digestInto(dest: WriteBuffer) {
        check(!finalized) { "digest already finalized" }
        // withWritablePointer validates capacity BEFORE invoking the block, so a too-small
        // dest throws with the ctx untouched (not yet freed by the shim) — the finalize
        // stays retryable (C1). The flag is set only after the shim call succeeds.
        dest.withWritablePointer(SHA512_DIGEST_BYTES) { ptr -> bcl_sha512_final(ctx, ptr.reinterpret()) }
        finalized = true
    }

    actual override fun close() {
        if (finalized) return
        finalized = true
        // The shim has no free-without-final: finalize into discarded stack scratch, which
        // cleanses and frees the ctx.
        memScoped {
            val scratch = allocArray<UByteVar>(SHA512_DIGEST_BYTES)
            bcl_sha512_final(ctx, scratch)
        }
    }
}
