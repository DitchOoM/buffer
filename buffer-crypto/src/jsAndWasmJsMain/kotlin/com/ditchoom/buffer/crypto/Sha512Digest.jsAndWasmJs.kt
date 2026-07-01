@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed actual file

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/** js/wasmJs SHA-512 backed by the synchronous pure-Kotlin [Sha512Core]. */
actual class Sha512Digest actual constructor() {
    private val core = Sha512Core(sha384 = false)
    private var finalized = false

    actual fun update(input: ReadBuffer): Sha512Digest {
        check(!finalized) { "digest already finalized" }
        core.update(input)
        return this
    }

    actual fun digestInto(dest: WriteBuffer) {
        check(!finalized) { "digest already finalized" }
        finalized = true
        core.finish()
        for (i in 0 until SHA512_DIGEST_BYTES) dest.writeByte(core.digestByte(i))
    }
}
