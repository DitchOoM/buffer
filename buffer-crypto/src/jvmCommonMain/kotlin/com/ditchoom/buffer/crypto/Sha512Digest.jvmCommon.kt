@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed actual file

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import java.security.MessageDigest

/** JVM/Android SHA-512 backed by the JCA [MessageDigest]. */
actual class Sha512Digest actual constructor() {
    private val md = MessageDigest.getInstance("SHA-512")
    private var finalized = false

    actual fun update(input: ReadBuffer): Sha512Digest {
        check(!finalized) { "digest already finalized" }
        md.updateRemaining(input)
        return this
    }

    actual fun digestInto(dest: WriteBuffer) {
        // JCA resets the digest for reuse; the cross-platform contract is one-shot, so reject reuse.
        check(!finalized) { "digest already finalized" }
        finalized = true
        md.digestInto(dest)
    }
}
