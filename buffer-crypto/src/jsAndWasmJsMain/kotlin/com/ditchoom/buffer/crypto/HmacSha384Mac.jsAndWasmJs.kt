package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/** js/wasmJs HMAC-SHA384 (RFC 2104) over the pure-Kotlin [Sha512Core] (SHA-384 IV). No primitive arrays. */
actual class HmacSha384Mac actual constructor(
    key: ReadBuffer,
) {
    private val core = Sha512FamilyHmac(key, mode384 = true, outBytes = SHA384_DIGEST_BYTES)

    actual fun update(input: ReadBuffer): HmacSha384Mac {
        core.update(input)
        return this
    }

    actual fun doFinalInto(dest: WriteBuffer) {
        core.doFinalInto(dest)
    }
}
