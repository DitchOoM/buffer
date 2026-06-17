package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/** js/wasmJs SHA-384 backed by the synchronous pure-Kotlin [Sha512Core] (SHA-384 IV). */
actual class Sha384Digest actual constructor() {
    private val core = Sha512Core(sha384 = true)

    actual fun update(input: ReadBuffer): Sha384Digest {
        core.update(input)
        return this
    }

    actual fun digestInto(dest: WriteBuffer) {
        core.finish()
        for (i in 0 until SHA384_DIGEST_BYTES) dest.writeByte(core.digestByte(i))
    }
}
