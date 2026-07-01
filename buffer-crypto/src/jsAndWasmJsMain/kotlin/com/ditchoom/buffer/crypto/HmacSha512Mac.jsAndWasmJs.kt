@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed actual file

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/** js/wasmJs HMAC-SHA512 (RFC 2104) over the pure-Kotlin [Sha512Core]. Holds no primitive arrays. */
actual class HmacSha512Mac actual constructor(
    key: ReadBuffer,
) : AutoCloseable {
    private val core = Sha512FamilyHmac(key, mode384 = false, outBytes = SHA512_DIGEST_BYTES)
    private var finalized = false

    actual fun update(input: ReadBuffer): HmacSha512Mac {
        check(!finalized) { "mac already finalized" }
        core.update(input)
        return this
    }

    actual fun doFinalInto(dest: WriteBuffer) {
        check(!finalized) { "mac already finalized" }
        // Validate BEFORE the core finalize: the core's padding absorb is not re-runnable, so a
        // short dest must fail while the state is still retryable (C1).
        require(dest.remaining() >= HMAC_SHA512_BYTES) { "dest needs $HMAC_SHA512_BYTES bytes remaining, has ${dest.remaining()}" }
        core.doFinalInto(dest)
        finalized = true
    }

    actual override fun close() {
        // GC-managed state; nothing to free. The flag still bars further use, matching the
        // other platforms' post-close behavior.
        if (finalized) return
        finalized = true
    }
}
