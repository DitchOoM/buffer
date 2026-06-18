package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import java.security.MessageDigest

/** JVM/Android SHA-512 backed by the JCA [MessageDigest]. */
actual class Sha512Digest actual constructor() {
    private val md = MessageDigest.getInstance("SHA-512")

    actual fun update(input: ReadBuffer): Sha512Digest {
        md.updateRemaining(input)
        return this
    }

    actual fun digestInto(dest: WriteBuffer) {
        md.digestInto(dest)
    }
}
