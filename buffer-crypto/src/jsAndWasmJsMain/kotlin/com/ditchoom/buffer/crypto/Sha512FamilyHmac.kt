package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.managed

/**
 * Shared pure-Kotlin HMAC (RFC 2104) over [Sha512Core], used by the js/wasmJs HMAC-SHA384 and
 * HMAC-SHA512 actuals. [mode384] selects the SHA-384 IV and [outBytes] the tag length; the
 * 1024-bit block ([SHA512_BLOCK_BYTES]) is shared by both. Holds no primitive arrays.
 */
internal class Sha512FamilyHmac(
    key: ReadBuffer,
    private val mode384: Boolean,
    private val outBytes: Int,
) {
    // inner accumulates H(ipad ‖ message); opad (a managed buffer) is held for the outer hash.
    private val inner = Sha512Core(mode384)
    private val opad: PlatformBuffer = BufferFactory.managed().allocate(SHA512_BLOCK_BYTES)

    init {
        // Normalize the key to a single block (managed buffer, zero-initialized): hash it if
        // longer than a block, else zero-pad.
        val kBlock: PlatformBuffer = BufferFactory.managed().allocate(SHA512_BLOCK_BYTES)
        val n = key.remaining()
        val start = key.position()
        if (n > SHA512_BLOCK_BYTES) {
            val kh = Sha512Core(mode384)
            kh.update(key)
            kh.finish()
            for (i in 0 until outBytes) kBlock.set(i, kh.digestByte(i))
        } else {
            for (i in 0 until n) kBlock.set(i, key.get(start + i))
        }
        for (i in 0 until SHA512_BLOCK_BYTES) {
            val kb = kBlock.get(i).toInt()
            inner.absorbByte((kb xor 0x36).toByte()) // ipad
            opad.set(i, (kb xor 0x5c).toByte())
        }
        kBlock.fill(0) // wipe the key-derived block
    }

    fun update(input: ReadBuffer) {
        inner.update(input)
    }

    fun doFinalInto(dest: WriteBuffer) {
        inner.finish() // inner = H(ipad ‖ message)
        val outer = Sha512Core(mode384)
        for (i in 0 until SHA512_BLOCK_BYTES) outer.absorbByte(opad.get(i))
        for (i in 0 until outBytes) outer.absorbByte(inner.digestByte(i))
        outer.finish()
        for (i in 0 until outBytes) dest.writeByte(outer.digestByte(i))
        opad.fill(0)
    }
}
