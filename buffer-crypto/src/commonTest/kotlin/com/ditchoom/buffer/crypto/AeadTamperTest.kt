package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Negative / tamper discipline for AEAD: this is the contract that the KAT vectors never
 * exercise — it proves *our* verification branch rejects manipulated input rather than
 * silently releasing plaintext. Every failure must surface as the opaque [VerificationFailed]
 * (no reason, no cause — a granular reason on a verify path is a decryption oracle).
 *
 * Runs on the cross-platform async surface so it covers JVM, JS, and WASM identically. The
 * ChaCha20-Poly1305 mirror is capability-gated.
 */
class AeadTamperTest {
    private val keyHex = "feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308"
    private val plaintext = "the quick brown fox"
    private val aad = "associated-context"

    private suspend fun sealOnce(): ReadBuffer {
        val key = AesGcmKey.of(hexBuffer(keyHex))
        return aesGcmSealAsync(key, ascii(plaintext), ascii(aad), BufferFactory.Default)
    }

    @Test
    fun aesGcmEveryByteFlipRejected() =
        runTest {
            val key = AesGcmKey.of(hexBuffer(keyHex))
            val sealed = sealOnce()
            val n = sealed.remaining()
            // Flip each byte of the whole nonce ‖ ciphertext ‖ tag frame in turn; all must reject.
            for (i in 0 until n) {
                val mutated = flipByte(sealed, i)
                assertFailsWith<VerificationFailed>("byte $i flip must reject") {
                    aesGcmOpenAsync(mutated, key, ascii(aad), BufferFactory.Default)
                }
            }
        }

    @Test
    fun aesGcmAadSwapRejected() =
        runTest {
            val key = AesGcmKey.of(hexBuffer(keyHex))
            val sealed = sealOnce()
            // Right key+ciphertext, wrong AAD → must reject.
            assertFailsWith<VerificationFailed> {
                aesGcmOpenAsync(sealed, key, ascii("different-context"), BufferFactory.Default)
            }
            // Dropping AAD entirely also breaks authentication.
            assertFailsWith<VerificationFailed> {
                aesGcmOpenAsync(sealed, key, null, BufferFactory.Default)
            }
        }

    @Test
    fun aesGcmWrongKeyRejected() =
        runTest {
            val sealed = sealOnce()
            val wrongKey = AesGcmKey.of(hexBuffer("00000000000000000000000000000000" + "00000000000000000000000000000000"))
            assertFailsWith<VerificationFailed> {
                aesGcmOpenAsync(sealed, wrongKey, ascii(aad), BufferFactory.Default)
            }
        }

    @Test
    fun aesGcmEmptyPlaintextAndAadRoundTrip() =
        runTest {
            val key = AesGcmKey.of(hexBuffer(keyHex))
            // Empty plaintext, empty/no AAD — the length off-by-one edge.
            val sealed = aesGcmSealAsync(key, BufferFactory.Default.allocate(0).also { it.resetForRead() }, null, BufferFactory.Default)
            assertEquals(AEAD_NONCE_BYTES + AEAD_TAG_BYTES, sealed.remaining())
            val opened = aesGcmOpenAsync(sealed, key, null, BufferFactory.Default)
            assertEquals(0, opened.remaining(), "empty plaintext must round-trip to empty")
        }

    @Test
    fun aesGcmTruncatedFrameRejected() =
        runTest {
            val key = AesGcmKey.of(hexBuffer(keyHex))
            val sealed = sealOnce()
            // Drop the last tag byte: a too-short frame must not authenticate.
            val truncated = absolute(sealed, 0, sealed.remaining() - 1)
            assertFailsWith<Exception> {
                aesGcmOpenAsync(truncated, key, ascii(aad), BufferFactory.Default)
            }
        }

    @Test
    fun chaChaPolyEveryByteFlipRejected() {
        if (!supportsChaChaPoly) return
        val key = ChaChaPolyKey.of(hexBuffer("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f"))
        val sealed = chaChaPolySeal(key, ascii(plaintext), ascii(aad), BufferFactory.Default)
        val n = sealed.remaining()
        for (i in 0 until n) {
            val mutated = flipByte(sealed, i)
            assertFailsWith<VerificationFailed>("ChaCha byte $i flip must reject") {
                chaChaPolyOpen(mutated, key, ascii(aad), BufferFactory.Default)
            }
        }
    }

    @Test
    fun chaChaPolyAadSwapAndWrongKeyRejected() {
        if (!supportsChaChaPoly) return
        val key = ChaChaPolyKey.of(hexBuffer("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f"))
        val sealed = chaChaPolySeal(key, ascii(plaintext), ascii(aad), BufferFactory.Default)
        assertFailsWith<VerificationFailed> { chaChaPolyOpen(sealed, key, ascii("wrong"), BufferFactory.Default) }
        val wrongKey = ChaChaPolyKey.of(hexBuffer("00".repeat(CHACHA_KEY_BYTES)))
        assertFailsWith<VerificationFailed> { chaChaPolyOpen(sealed, wrongKey, ascii(aad), BufferFactory.Default) }
    }

    // --- helpers: copy-and-mutate without ByteArray ---

    /** Returns a fresh read-ready copy of [source] with the byte at absolute index [i] flipped. */
    private fun flipByte(
        source: ReadBuffer,
        i: Int,
    ): ReadBuffer {
        val start = source.position()
        val n = source.remaining()
        val out = BufferFactory.Default.allocate(n)
        for (k in 0 until n) {
            val b = source.get(start + k)
            out.writeByte(if (k == i) (b.toInt() xor 0x01).toByte() else b)
        }
        out.resetForRead()
        return out
    }

    /** A fresh read-ready copy of [length] bytes of [source] starting at relative offset [from]. */
    private fun absolute(
        source: ReadBuffer,
        from: Int,
        length: Int,
    ): ReadBuffer {
        val start = source.position() + from
        val out = BufferFactory.Default.allocate(length)
        for (k in 0 until length) out.writeByte(source.get(start + k))
        out.resetForRead()
        return out
    }
}
