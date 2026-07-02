@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed actual file
@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_hmac_final
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_hmac_sha256_new
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_hmac_update
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret

/** Linux HMAC-SHA256 backed by BoringSSL (`HMAC_*`). Reads and writes via buffer pointers — no arrays. */
actual class HmacSha256Mac actual constructor(
    key: ReadBuffer,
) : AutoCloseable {
    private val ctx =
        run {
            var c = if (key.remaining() == 0) bcl_hmac_sha256_new(null, 0.convert()) else null
            key.withRemainingBytes { ptr, len -> c = bcl_hmac_sha256_new(ptr.reinterpret(), len.convert()) }
            c ?: error("HMAC_Init failed")
        }
    private var finalized = false

    actual fun update(input: ReadBuffer): HmacSha256Mac {
        check(!finalized) { "mac already finalized" }
        input.withRemainingBytes { ptr, len -> bcl_hmac_update(ctx, ptr.reinterpret(), len.convert()) }
        return this
    }

    actual fun doFinalInto(dest: WriteBuffer) {
        check(!finalized) { "mac already finalized" }
        // withWritablePointer validates capacity BEFORE invoking the block, so a too-small
        // dest throws with the ctx untouched (not yet freed by the shim) — the finalize
        // stays retryable (C1). The flag is set only after the shim call succeeds.
        dest.withWritablePointer(HMAC_SHA256_BYTES) { ptr -> bcl_hmac_final(ctx, ptr.reinterpret()) }
        finalized = true
    }

    actual override fun close() {
        if (finalized) return
        finalized = true
        // The shim has no free-without-final: finalize into discarded stack scratch, which
        // cleanses and frees the ctx.
        memScoped {
            val scratch = allocArray<UByteVar>(HMAC_SHA256_BYTES)
            bcl_hmac_final(ctx, scratch)
        }
    }
}
