package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Single-block AES (ECB) known-answer + round-trip + capability tests.
 *
 * The KAT vectors are the canonical FIPS-197 (Appendix C.1 / C.3) and NIST SP 800-38A (Appendix F.1)
 * single-block pairs; they pin the exact ciphertext so a permutation regression on any native
 * platform fails here. All ops are capability-gated on [aesEcbOpsOrNull] so the suite compiles and
 * runs on the web too, where the primitive is [AesEcb.Unavailable].
 */
class AesEcbTest {
    // (key, plaintext block, ciphertext block) — one 16-byte AES block.
    private data class Vec(
        val key: String,
        val pt: String,
        val ct: String,
    )

    // FIPS-197 Appendix C.1 (AES-128) and C.3 (AES-256): the reference block-cipher vectors.
    private val fips197Aes128 =
        Vec(
            key = "000102030405060708090a0b0c0d0e0f",
            pt = "00112233445566778899aabbccddeeff",
            ct = "69c4e0d86a7b0430d8cdb78070b4c55a",
        )
    private val fips197Aes256 =
        Vec(
            key = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            pt = "00112233445566778899aabbccddeeff",
            ct = "8ea2b7ca516745bfeafc49904b496089",
        )

    // NIST SP 800-38A F.1.1 / F.1.5 first block (ECB-AES128 / ECB-AES256).
    private val sp80038aAes128 =
        Vec(
            key = "2b7e151628aed2a6abf7158809cf4f3c",
            pt = "6bc1bee22e409f96e93d7e117393172a",
            ct = "3ad77bb40d7a3660a89ecaf32466ef97",
        )
    private val sp80038aAes256 =
        Vec(
            key = "603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4",
            pt = "6bc1bee22e409f96e93d7e117393172a",
            ct = "f3eed1bdb5d2a03c064b5a7e3db181f8",
        )

    private val allVectors = listOf(fips197Aes128, fips197Aes256, sp80038aAes128, sp80038aAes256)

    @Test
    fun encryptBlockKnownAnswer() {
        val ops = aesEcbOpsOrNull() ?: return
        for (v in allVectors) {
            AesEcbKey.of(hexBuffer(v.key)).use { key ->
                val ct = ops.encryptBlock(key, hexBuffer(v.pt), BufferFactory.Default)
                assertEquals(v.ct, ct.toHex(), "AES-ECB encrypt (key=${v.key.length * 4}bit)")
            }
        }
    }

    @Test
    fun decryptBlockKnownAnswer() {
        val ops = aesEcbOpsOrNull() ?: return
        for (v in allVectors) {
            AesEcbKey.of(hexBuffer(v.key)).use { key ->
                val pt = ops.decryptBlock(key, hexBuffer(v.ct), BufferFactory.Default)
                assertEquals(v.pt, pt.toHex(), "AES-ECB decrypt (key=${v.key.length * 4}bit)")
            }
        }
    }

    @Test
    fun encryptThenDecryptRoundTrips() {
        val ops = aesEcbOpsOrNull() ?: return
        for (v in allVectors) {
            AesEcbKey.of(hexBuffer(v.key)).use { key ->
                val ct = ops.encryptBlock(key, hexBuffer(v.pt), BufferFactory.Default)
                val pt = ops.decryptBlock(key, ct, BufferFactory.Default)
                assertEquals(v.pt, pt.toHex(), "AES-ECB round-trip")
            }
        }
    }

    @Test
    fun encryptIsDeterministic() {
        // ECB is a pure keyed permutation: the same block under the same key always maps to the same
        // ciphertext. This determinism is exactly what DTLS 1.3 sequence-number masking relies on and
        // exactly why ECB must never be used for bulk confidentiality.
        val ops = aesEcbOpsOrNull() ?: return
        AesEcbKey.of(hexBuffer(fips197Aes128.key)).use { key ->
            val a = ops.encryptBlock(key, hexBuffer(fips197Aes128.pt), BufferFactory.Default)
            val b = ops.encryptBlock(key, hexBuffer(fips197Aes128.pt), BufferFactory.Default)
            assertEquals(a.toHex(), b.toHex(), "ECB encrypt must be deterministic")
        }
    }

    @Test
    fun dtlsSequenceNumberMaskRoundTrips() {
        // Models RFC 9147 §4.2.3 sequence-number encryption: mask = AES-ECB-encrypt(sn_key, sample);
        // both sides XOR the same mask, so only the forward direction is used. Here we mask an 8-byte
        // sequence number with the leading mask bytes and recover it.
        val ops = aesEcbOpsOrNull() ?: return
        AesEcbKey.of(hexBuffer(fips197Aes128.key)).use { key ->
            val sample = hexBuffer("112233445566778899aabbccddeeff00")
            val mask = ops.encryptBlock(key, sample, BufferFactory.Default)
            // Fold the leading 8 mask bytes into a Long and XOR the 8-byte sequence number with it.
            var maskWord = 0L
            for (i in 0 until 8) maskWord = (maskWord shl 8) or (mask.get(i).toLong() and 0xFF)
            val seq = 0x0001020304050607L
            val masked = seq xor maskWord
            val recovered = masked xor maskWord
            assertTrue(masked != seq, "masking must actually change the sequence number")
            assertEquals(seq, recovered, "re-XOR with the same forward mask must recover the sequence number")
        }
    }

    @Test
    fun keyLengthValidation() {
        // Non-16/32-byte key lengths are rejected (192-bit is not supported by this wrapper).
        assertFailsWith<IllegalArgumentException> { AesEcbKey.of(hexBuffer("00112233")) }
        assertFailsWith<IllegalArgumentException> {
            AesEcbKey.of(hexBuffer("000102030405060708090a0b0c0d0e0f1011121314151617"))
        }
    }

    @Test
    fun blockLengthValidation() {
        val ops = aesEcbOpsOrNull() ?: return
        AesEcbKey.of(hexBuffer(fips197Aes128.key)).use { key ->
            // Too short and too long (multi-block) are both rejected — this is a single-block primitive.
            assertFailsWith<IllegalArgumentException> {
                ops.encryptBlock(key, hexBuffer("00112233"), BufferFactory.Default)
            }
            assertFailsWith<IllegalArgumentException> {
                ops.encryptBlock(key, hexBuffer(fips197Aes128.pt + fips197Aes128.pt), BufferFactory.Default)
            }
            assertFailsWith<IllegalArgumentException> {
                val dst = BufferFactory.Default.allocate(8)
                ops.encryptBlock(key, hexBuffer(fips197Aes128.pt), dst)
            }
        }
    }

    @Test
    fun capabilityContract() {
        when (val w = CryptoCapabilities.aesEcb) {
            is AesEcb.Blocking -> {
                // Native: the primitive is reachable and enciphers a block.
                assertTrue(aesEcbBlockingAvailable)
                AesEcbKey.of(hexBuffer(fips197Aes128.key)).use { key ->
                    val ct = w.ops.encryptBlock(key, hexBuffer(fips197Aes128.pt), BufferFactory.Default)
                    assertEquals(fips197Aes128.ct, ct.toHex())
                }
            }
            // Web: WebCrypto has no ECB, so the witness is Unavailable — there is no op to call
            // (impossible by construction, not a throw).
            AesEcb.Unavailable -> assertEquals(false, aesEcbBlockingAvailable)
        }
    }
}
