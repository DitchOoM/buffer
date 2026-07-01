@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed actual file

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/** js/wasmJs HMAC-SHA384 (RFC 2104) over the pure-Kotlin [Sha512Core] (SHA-384 IV). No primitive arrays. */
actual class HmacSha384Mac actual constructor(
    key: ReadBuffer,
) : AutoCloseable {
    private val core = Sha512FamilyHmac(key, mode384 = true, outBytes = SHA384_DIGEST_BYTES)
    private var finalized = false

    actual fun update(input: ReadBuffer): HmacSha384Mac {
        check(!finalized) { "mac already finalized" }
        core.update(input)
        return this
    }

    actual fun doFinalInto(dest: WriteBuffer) {
        check(!finalized) { "mac already finalized" }
        finalized = true
        core.doFinalInto(dest)
    }

    actual override fun close() {
        // GC-managed state; nothing to free. The flag still bars further use, matching the
        // other platforms' post-close behavior.
        if (finalized) return
        finalized = true
    }
}
