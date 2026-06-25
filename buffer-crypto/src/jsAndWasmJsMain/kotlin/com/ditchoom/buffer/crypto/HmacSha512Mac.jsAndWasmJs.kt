@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed actual file

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/** js/wasmJs HMAC-SHA512 (RFC 2104) over the pure-Kotlin [Sha512Core]. Holds no primitive arrays. */
actual class HmacSha512Mac actual constructor(
    key: ReadBuffer,
) {
    private val core = Sha512FamilyHmac(key, mode384 = false, outBytes = SHA512_DIGEST_BYTES)

    actual fun update(input: ReadBuffer): HmacSha512Mac {
        core.update(input)
        return this
    }

    actual fun doFinalInto(dest: WriteBuffer) {
        core.doFinalInto(dest)
    }
}
