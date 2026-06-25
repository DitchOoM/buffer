@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed actual file
@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_sha384_final
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_sha384_new
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_sha384_update
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret

/** Linux SHA-384 backed by BoringSSL (`SHA384_*`). Reads and writes via buffer pointers — no arrays. */
actual class Sha384Digest actual constructor() {
    private val ctx = bcl_sha384_new() ?: error("SHA384_Init failed")
    private var finalized = false

    actual fun update(input: ReadBuffer): Sha384Digest {
        input.withRemainingBytes { ptr, len -> bcl_sha384_update(ctx, ptr.reinterpret(), len.convert()) }
        return this
    }

    actual fun digestInto(dest: WriteBuffer) {
        check(!finalized) { "digest already finalized" }
        finalized = true
        dest.withWritablePointer(SHA384_DIGEST_BYTES) { ptr -> bcl_sha384_final(ctx, ptr.reinterpret()) }
    }
}
