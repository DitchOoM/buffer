@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed actual file
@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_sha512_final
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_sha512_new
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_sha512_update
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret

/** Linux SHA-512 backed by BoringSSL (`SHA512_*`). Reads and writes via buffer pointers — no arrays. */
actual class Sha512Digest actual constructor() {
    private val ctx = bcl_sha512_new() ?: error("SHA512_Init failed")
    private var finalized = false

    actual fun update(input: ReadBuffer): Sha512Digest {
        check(!finalized) { "digest already finalized" }
        input.withRemainingBytes { ptr, len -> bcl_sha512_update(ctx, ptr.reinterpret(), len.convert()) }
        return this
    }

    actual fun digestInto(dest: WriteBuffer) {
        check(!finalized) { "digest already finalized" }
        finalized = true
        dest.withWritablePointer(SHA512_DIGEST_BYTES) { ptr -> bcl_sha512_final(ctx, ptr.reinterpret()) }
    }
}
