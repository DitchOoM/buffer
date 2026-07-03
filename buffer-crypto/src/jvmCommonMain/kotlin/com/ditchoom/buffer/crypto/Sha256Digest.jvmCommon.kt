@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed actual file

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import java.security.MessageDigest

/** JVM/Android SHA-256 backed by the JCA [MessageDigest]. */
actual class Sha256Digest actual constructor() : AutoCloseable {
    private val md = MessageDigest.getInstance("SHA-256")
    private var finalized = false

    actual fun update(input: ReadBuffer): Sha256Digest {
        check(!finalized) { "digest already finalized" }
        md.updateRemaining(input)
        return this
    }

    actual fun digestInto(dest: WriteBuffer) {
        // JCA resets the digest for reuse; the cross-platform contract is one-shot, so reject reuse.
        check(!finalized) { "digest already finalized" }
        // digestInto validates capacity BEFORE consuming the JCA state, so a too-small
        // dest throws with the digest still intact — the finalize stays retryable (C1).
        md.digestInto(dest)
        finalized = true
    }

    actual override fun close() {
        if (finalized) return
        finalized = true
        md.reset()
    }
}
