@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed actual file

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.managed

/** js/wasmJs HMAC-SHA1 (RFC 2104) over the pure-Kotlin [Sha1Core]. Holds no primitive arrays. */
actual class HmacSha1Mac actual constructor(
    key: ReadBuffer,
) : AutoCloseable {
    // inner accumulates H(ipad ‖ message); opad (a managed buffer) is held for the outer hash.
    private val inner = Sha1Core()
    private val opad: PlatformBuffer = BufferFactory.managed().allocate(SHA1_BLOCK_BYTES)
    private var finalized = false

    init {
        // Normalize the key to a single block (managed buffer, zero-initialized): hash it if
        // longer than a block, else zero-pad.
        val kBlock: PlatformBuffer = BufferFactory.managed().allocate(SHA1_BLOCK_BYTES)
        val n = key.remaining()
        val start = key.position()
        if (n > SHA1_BLOCK_BYTES) {
            val kh = Sha1Core()
            kh.update(key)
            kh.finish()
            for (i in 0 until SHA1_DIGEST_BYTES) kBlock.set(i, kh.digestByte(i))
        } else {
            for (i in 0 until n) kBlock.set(i, key.get(start + i))
        }
        for (i in 0 until SHA1_BLOCK_BYTES) {
            val kb = kBlock.get(i).toInt()
            inner.absorbByte((kb xor HMAC_IPAD).toByte()) // ipad
            opad.set(i, (kb xor HMAC_OPAD).toByte())
        }
        kBlock.fill(0) // wipe the key-derived block
    }

    actual fun update(input: ReadBuffer): HmacSha1Mac {
        check(!finalized) { "mac already finalized" }
        inner.update(input)
        return this
    }

    actual fun doFinalInto(dest: WriteBuffer) {
        check(!finalized) { "mac already finalized" }
        // Validate BEFORE finish(): the core's padding absorb is not re-runnable, so a
        // short dest must fail while the state is still retryable (C1).
        require(dest.remaining() >= HMAC_SHA1_BYTES) {
            "dest needs $HMAC_SHA1_BYTES bytes remaining, has ${dest.remaining()}"
        }
        inner.finish() // inner = H(ipad ‖ message)
        val outer = Sha1Core()
        for (i in 0 until SHA1_BLOCK_BYTES) outer.absorbByte(opad.get(i))
        for (i in 0 until SHA1_DIGEST_BYTES) outer.absorbByte(inner.digestByte(i))
        outer.finish()
        for (i in 0 until SHA1_DIGEST_BYTES) dest.writeByte(outer.digestByte(i))
        opad.fill(0)
        finalized = true
    }

    actual override fun close() {
        // GC-managed state; nothing to free beyond wiping opad. The flag still bars further
        // use, matching the other platforms' post-close behavior.
        if (finalized) return
        opad.fill(0)
        finalized = true
    }
}
