package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Curated real Wycheproof vectors (C2SP `testvectors_v1`) for AES-GCM and ChaCha20-Poly1305.
 *
 * The curated subset is restricted to the parameters these wrappers support — 96-bit IV,
 * 128-bit tag, 128/256-bit AES keys, 256-bit ChaCha keys — so `valid` vectors must decrypt to
 * the expected plaintext and `invalid` vectors (tampered tag / ciphertext) must be rejected by
 * the verify branch. This is the "single-byte flip must not authenticate" contract at scale,
 * against Google's adversarial corpus rather than just our own hand-written flips.
 */
class AeadWycheproofTest {
    @Test
    fun aesGcmVectors() =
        runTest {
            val summary =
                Wycheproof.runSuspending(WycheproofVectorsAesGcm.JSON) { case ->
                    val key = AesGcmKey.of(case.testHex("key"))
                    val iv = case.testHex("iv")
                    val aadHex = case.testHexOrNull("aad")
                    val ctAndTag = concat(case.testHex("ct"), case.testHex("tag"))
                    val expectedPt = case.testHex("msg").toHex()
                    // The op "accepts" iff decryption authenticates AND yields the expected plaintext.
                    val opened = aesGcmOpenWithNonceAsync(key, iv, aadHex, ctAndTag, BufferFactory.Default)
                    opened.toHex() == expectedPt
                }
            assertTrue(summary.total > 0, "expected curated AES-GCM vectors to run")
        }

    @Test
    fun chaCha20Poly1305Vectors() =
        runTest {
            if (!supportsChaChaPoly) return@runTest
            val summary =
                Wycheproof.runSuspending(WycheproofVectorsChaChaPoly.JSON) { case ->
                    val key = ChaChaPolyKey.of(case.testHex("key"))
                    val iv = case.testHex("iv")
                    val aadHex = case.testHexOrNull("aad")
                    val ctAndTag = concat(case.testHex("ct"), case.testHex("tag"))
                    val expectedPt = case.testHex("msg").toHex()
                    val out = BufferFactory.Default.allocate(maxOf(0, ctAndTag.remaining() - AEAD_TAG_BYTES))
                    chaChaPolyOpen(key, iv, aadHex, ctAndTag, out)
                    out.resetForRead()
                    out.toHex() == expectedPt
                }
            assertTrue(summary.total > 0, "expected curated ChaCha20-Poly1305 vectors to run")
        }

    /** Concatenates two read-ready buffers into a fresh read-ready buffer (no ByteArray). */
    private fun concat(
        a: com.ditchoom.buffer.ReadBuffer,
        b: com.ditchoom.buffer.ReadBuffer,
    ): com.ditchoom.buffer.ReadBuffer {
        val out = BufferFactory.Default.allocate(a.remaining() + b.remaining())
        val aStart = a.position()
        val bStart = b.position()
        for (i in 0 until a.remaining()) out.writeByte(a.get(aStart + i))
        for (i in 0 until b.remaining()) out.writeByte(b.get(bStart + i))
        out.resetForRead()
        return out
    }
}
