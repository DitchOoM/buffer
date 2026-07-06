package com.ditchoom.buffer.crypto

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Proves the JS-exported [CryptoDemo] facade actually executes the shipping AES-GCM path in a real
 * JS engine (Node WebCrypto). This is the "see it run" backstop for the docs widget: the same
 * facade the browser demo calls is exercised here end to end.
 */
class CryptoDemoTest {
    @Test
    fun roundTripsThroughTheFacade() =
        runTest {
            val keyHex = CryptoDemo.generateKeyHex()
            assertEquals(64, keyHex.length, "AES-256 key is 32 bytes = 64 hex chars")

            val sealed = CryptoDemo.seal(keyHex, "attack at dawn", "header-v1").await()
            val recovered = CryptoDemo.open(keyHex, sealed, "header-v1").await()
            assertEquals("attack at dawn", recovered)
        }

    @Test
    fun freshNoncePerSealMakesCiphertextNonDeterministic() =
        runTest {
            val keyHex = CryptoDemo.generateKeyHex()
            val a = CryptoDemo.seal(keyHex, "same plaintext", "").await()
            val b = CryptoDemo.seal(keyHex, "same plaintext", "").await()
            assertNotEquals(a, b, "a fresh 12-byte nonce per seal must change the output")
        }

    @Test
    fun tamperedCiphertextFailsVerification() =
        runTest {
            val keyHex = CryptoDemo.generateKeyHex()
            val sealed = CryptoDemo.seal(keyHex, "attack at dawn", "header-v1").await()

            // Flip the last hex nibble of the tag.
            val last = sealed.last()
            val flipped = if (last == '0') '1' else '0'
            val tampered = sealed.dropLast(1) + flipped

            assertFailsWith<VerificationFailed> {
                CryptoDemo.open(keyHex, tampered, "header-v1").await()
            }
        }

    @Test
    fun swappedAadFailsVerification() =
        runTest {
            val keyHex = CryptoDemo.generateKeyHex()
            val sealed = CryptoDemo.seal(keyHex, "attack at dawn", "header-v1").await()
            assertFailsWith<VerificationFailed> {
                CryptoDemo.open(keyHex, sealed, "header-v2").await()
            }
        }

    @Test
    fun pinnedNonceSealIsDeterministicAndOpens() =
        runTest {
            val keyHex = CryptoDemo.generateKeyHex()
            val nonceHex = CryptoDemo.generateNonceHex()
            assertEquals(24, nonceHex.length, "12-byte nonce = 24 hex chars")

            // Same key+nonce+plaintext+aad → identical output (that's why real seal never reuses a nonce).
            val a = CryptoDemo.sealWithNonce(keyHex, nonceHex, "attack at dawn", "header-v1").await()
            val b = CryptoDemo.sealWithNonce(keyHex, nonceHex, "attack at dawn", "header-v1").await()
            assertEquals(a, b, "pinned nonce makes the seal deterministic")

            // Output is nonce ‖ ct ‖ tag, so the normal open recovers it.
            val recovered = CryptoDemo.open(keyHex, a, "header-v1").await()
            assertEquals("attack at dawn", recovered)
        }

    @Test
    fun capabilitiesReportTheWebWitnesses() {
        val caps = CryptoDemo.capabilities()
        assertTrue(caps.contains("AES-GCM=AsyncOnly"), "AES-GCM is async-only on the web: $caps")
        assertTrue(
            caps.contains("ChaCha20-Poly1305=Unavailable"),
            "ChaCha20-Poly1305 is not in WebCrypto: $caps",
        )
    }
}
