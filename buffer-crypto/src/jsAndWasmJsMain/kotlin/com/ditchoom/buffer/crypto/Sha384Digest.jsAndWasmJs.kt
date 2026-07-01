@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed actual file

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/** js/wasmJs SHA-384 backed by the synchronous pure-Kotlin [Sha512Core] (SHA-384 IV). */
actual class Sha384Digest actual constructor() : AutoCloseable {
    private val core = Sha512Core(sha384 = true)
    private var finalized = false

    actual fun update(input: ReadBuffer): Sha384Digest {
        check(!finalized) { "digest already finalized" }
        core.update(input)
        return this
    }

    actual fun digestInto(dest: WriteBuffer) {
        check(!finalized) { "digest already finalized" }
        // Validate BEFORE finish(): the core's padding absorb is not re-runnable, so a
        // short dest must fail while the state is still retryable (C1).
        require(dest.remaining() >= SHA384_DIGEST_BYTES) { "dest needs $SHA384_DIGEST_BYTES bytes remaining, has ${dest.remaining()}" }
        core.finish()
        for (i in 0 until SHA384_DIGEST_BYTES) dest.writeByte(core.digestByte(i))
        finalized = true
    }

    actual override fun close() {
        // GC-managed state; nothing to free. The flag still bars further use, matching the
        // other platforms' post-close behavior.
        if (finalized) return
        finalized = true
    }
}
