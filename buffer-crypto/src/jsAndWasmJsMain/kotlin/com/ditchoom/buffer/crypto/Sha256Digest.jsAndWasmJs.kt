package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/** js/wasmJs SHA-256 backed by the synchronous pure-Kotlin [Sha256Core]. */
actual class Sha256Digest actual constructor() {
    private val core = Sha256Core()

    actual fun update(input: ReadBuffer): Sha256Digest {
        core.update(input)
        return this
    }

    actual fun digestInto(dest: WriteBuffer) {
        core.finish()
        for (i in 0 until SHA256_DIGEST_BYTES) dest.writeByte(core.digestByte(i))
    }
}
