package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.CryptoBackings.Backing
import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import com.ditchoom.buffer.pool.BufferPool
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The crypto primitives must produce identical output regardless of how the input and
 * destination buffers are backed. Backings are supplied by the shared [CryptoBackings]
 * harness so every family covers the same heap × direct × pooled × slice matrix.
 */
class BufferBackingTests {
    // SHA-256("abc") and RFC 4231 Case 2 HMAC-SHA256(key="Jefe", msg="what do ya want for nothing?").
    private val sha256Abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val hmacJefe = "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"

    @Test
    fun sha256AcrossInputAndDestBackings() {
        val pool = BufferPool()
        for (ik in CryptoBackings.inputs) {
            for (dk in CryptoBackings.dests) {
                val out = CryptoBackings.dest(dk, SHA256_DIGEST_BYTES, pool)
                sha256(CryptoBackings.place(ik, ascii("abc"), pool), out)
                out.resetForRead()
                assertEquals(sha256Abc, out.toHex(), "sha256 input=$ik dest=$dk")
            }
        }
        pool.clear()
    }

    @Test
    fun hmacAcrossKeyBackings() {
        // Varying the key backing toggles the SecretKeySpec(array, …) vs SecretKeySpec(copy)
        // branch on JVM and the managed/native pointer branch on Apple.
        val pool = BufferPool()
        for (keyKind in CryptoBackings.inputs) {
            val key = CryptoBackings.place(keyKind, ascii("Jefe"), pool)
            val message = CryptoBackings.place(Backing.DIRECT, ascii("what do ya want for nothing?"), pool)
            assertEquals(hmacJefe, hmacSha256(key, message).toHex(), "hmac key=$keyKind")
        }
        pool.clear()
    }
}
