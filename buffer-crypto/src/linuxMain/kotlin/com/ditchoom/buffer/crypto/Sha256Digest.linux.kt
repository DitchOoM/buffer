@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_sha256_final
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_sha256_new
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_sha256_update
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret

/** Linux SHA-256 backed by BoringSSL (`SHA256_*`). Reads and writes via buffer pointers — no arrays. */
actual class Sha256Digest actual constructor() {
    private val ctx = bcl_sha256_new() ?: error("SHA256_Init failed")
    private var finalized = false

    actual fun update(input: ReadBuffer): Sha256Digest {
        input.withRemainingBytes { ptr, len -> bcl_sha256_update(ctx, ptr.reinterpret(), len.convert()) }
        return this
    }

    actual fun digestInto(dest: WriteBuffer) {
        check(!finalized) { "digest already finalized" }
        finalized = true
        dest.withWritablePointer(SHA256_DIGEST_BYTES) { ptr -> bcl_sha256_final(ctx, ptr.reinterpret()) }
    }
}
