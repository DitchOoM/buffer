package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.CryptoBackings.Backing
import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import com.ditchoom.buffer.pool.BufferPool
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SHA-512 and HMAC-SHA512 must produce identical output across every input/destination
 * backing (heap × direct × pooled × slice), via the shared [CryptoBackings] harness — a
 * broken direct-dest fast path would otherwise pass the KAT tests by hitting one backing.
 */
class Sha512FamilyBackingTests {
    private val abc512 =
        "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
            "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f"
    private val jefe512 =
        "164b7a7bfcf819e2e395fbe73b56e0a387bd64222e831fd610270cd7ea250554" +
            "9758bf75c05a994a6d034f65f8f0e6fdcaeab1a34d4a6b4b636e070a38bce737"

    @Test
    fun sha512AcrossInputAndDestBackings() {
        val pool = BufferPool()
        for (ik in CryptoBackings.inputs) {
            for (dk in CryptoBackings.dests) {
                val out = CryptoBackings.dest(dk, SHA512_DIGEST_BYTES, pool)
                sha512(CryptoBackings.place(ik, ascii("abc"), pool), out)
                out.resetForRead()
                assertEquals(abc512, out.toHex(), "sha512 input=$ik dest=$dk")
            }
        }
        pool.clear()
    }

    @Test
    fun hmacSha512AcrossKeyBackings() {
        val pool = BufferPool()
        for (keyKind in CryptoBackings.inputs) {
            val key = CryptoBackings.place(keyKind, ascii("Jefe"), pool)
            val message = CryptoBackings.place(Backing.DIRECT, ascii("what do ya want for nothing?"), pool)
            assertEquals(jefe512, hmacSha512(key, message).toHex(), "hmac key=$keyKind")
        }
        pool.clear()
    }
}
