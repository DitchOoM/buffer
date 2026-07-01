@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed actual file
@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_hmac_final
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_hmac_sha512_new
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_hmac_update
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret

/** Linux HMAC-SHA512 backed by BoringSSL (`HMAC_*`). Reads and writes via buffer pointers — no arrays. */
actual class HmacSha512Mac actual constructor(
    key: ReadBuffer,
) {
    private val ctx =
        run {
            var c = if (key.remaining() == 0) bcl_hmac_sha512_new(null, 0.convert()) else null
            key.withRemainingBytes { ptr, len -> c = bcl_hmac_sha512_new(ptr.reinterpret(), len.convert()) }
            c ?: error("HMAC_Init failed")
        }
    private var finalized = false

    actual fun update(input: ReadBuffer): HmacSha512Mac {
        check(!finalized) { "mac already finalized" }
        input.withRemainingBytes { ptr, len -> bcl_hmac_update(ctx, ptr.reinterpret(), len.convert()) }
        return this
    }

    actual fun doFinalInto(dest: WriteBuffer) {
        check(!finalized) { "mac already finalized" }
        finalized = true
        dest.withWritablePointer(HMAC_SHA512_BYTES) { ptr -> bcl_hmac_final(ctx, ptr.reinterpret()) }
    }
}
