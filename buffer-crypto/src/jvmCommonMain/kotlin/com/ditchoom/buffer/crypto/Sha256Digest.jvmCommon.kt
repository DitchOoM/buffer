@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed actual file

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import java.security.MessageDigest

/** JVM/Android SHA-256 backed by the JCA [MessageDigest]. */
actual class Sha256Digest actual constructor() {
    private val md = MessageDigest.getInstance("SHA-256")

    actual fun update(input: ReadBuffer): Sha256Digest {
        md.updateRemaining(input)
        return this
    }

    actual fun digestInto(dest: WriteBuffer) {
        md.digestInto(dest)
    }
}
