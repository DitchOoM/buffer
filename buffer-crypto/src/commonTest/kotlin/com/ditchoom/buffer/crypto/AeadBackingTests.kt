package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import com.ditchoom.buffer.pool.BufferPool
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AEAD output must be identical regardless of how the plaintext, AAD, and (for decrypt) the
 * ciphertext buffers are backed — heap × direct × pooled × slice — because each backing hits a
 * different bridge branch (managed-array fast path vs native-pointer / `ByteBuffer` path vs
 * wrapper delegation). A regression in one branch would otherwise slip past a KAT that only
 * happens to exercise one backing. Driven through the shared [CryptoBackings] harness.
 */
class AeadBackingTests {
    // NIST AES-128-GCM vector with AAD (see AeadTest).
    private val key = "feffe9928665731c6d6a8f9467308308"
    private val iv = "cafebabefacedbaddecaf888"
    private val aad = "feedfacedeadbeeffeedfacedeadbeefabaddad2"
    private val pt =
        "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721" +
            "c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39"
    private val ctTag =
        "42831ec2217774244b7221b784d0d49ce3aa212f2c02a4e035c17e2329aca12e" +
            "21d514b25466931c7d8f6a5aac84aa051ba30b396a0aac973d58e091" +
            "5bc94fbc3221a5db94fae95ae7121a47"

    @Test
    fun aesGcmSealAcrossInputAadDestBackings() =
        runTest {
            // Sync seal/open exercise the per-backing bridge branches; on the web they throw
            // (WebCrypto is async-only), so the matrix runs on JVM/Apple. Web backing coverage
            // comes from the async round-trip + Wycheproof suites.
            if (!supportsSyncAesGcm) return@runTest
            val pool = BufferPool()
            val gcmKey = AesGcmKey.of(hexBuffer(key))
            val ptLen = pt.length / 2
            for (ptKind in CryptoBackings.inputs) {
                for (aadKind in CryptoBackings.inputs) {
                    for (destKind in CryptoBackings.dests) {
                        val ptBuf = CryptoBackings.place(ptKind, hexBuffer(pt), pool)
                        val aadBuf = CryptoBackings.place(aadKind, hexBuffer(aad), pool)
                        val dest = CryptoBackings.dest(destKind, ptLen + AEAD_TAG_BYTES, pool)
                        aesGcmSeal(gcmKey, hexBuffer(iv), aadBuf, ptBuf, dest)
                        dest.resetForRead()
                        assertEquals(ctTag, dest.toHex(), "seal pt=$ptKind aad=$aadKind dest=$destKind")
                    }
                }
            }
            pool.clear()
        }

    @Test
    fun aesGcmOpenAcrossInputAadDestBackings() =
        runTest {
            if (!supportsSyncAesGcm) return@runTest
            val pool = BufferPool()
            val gcmKey = AesGcmKey.of(hexBuffer(key))
            val ptLen = pt.length / 2
            for (ctKind in CryptoBackings.inputs) {
                for (aadKind in CryptoBackings.inputs) {
                    for (destKind in CryptoBackings.dests) {
                        val ctBuf = CryptoBackings.place(ctKind, hexBuffer(ctTag), pool)
                        val aadBuf = CryptoBackings.place(aadKind, hexBuffer(aad), pool)
                        val dest = CryptoBackings.dest(destKind, ptLen, pool)
                        aesGcmOpen(gcmKey, hexBuffer(iv), aadBuf, ctBuf, dest)
                        dest.resetForRead()
                        assertEquals(pt, dest.toHex(), "open ct=$ctKind aad=$aadKind dest=$destKind")
                    }
                }
            }
            pool.clear()
        }

    @Test
    fun aesGcmAsyncRoundTripAcrossInputAadDestBackings() =
        runTest {
            // The cross-platform async surface (the only AES-GCM path on the web) across every
            // input/AAD backing — proves the WebCrypto bridge handles each backing too.
            val pool = BufferPool()
            val gcmKey = AesGcmKey.of(hexBuffer(key))
            for (ptKind in CryptoBackings.inputs) {
                for (aadKind in CryptoBackings.inputs) {
                    val ptBuf = CryptoBackings.place(ptKind, hexBuffer(pt), pool)
                    val aadBuf = CryptoBackings.place(aadKind, hexBuffer(aad), pool)
                    val sealed = aesGcmSealWithNonceAsync(gcmKey, hexBuffer(iv), aadBuf, ptBuf, BufferFactory.Default)
                    assertEquals(ctTag, sealed.toHex(), "async seal pt=$ptKind aad=$aadKind")

                    val ctBuf = CryptoBackings.place(ptKind, hexBuffer(ctTag), pool)
                    val aadBuf2 = CryptoBackings.place(aadKind, hexBuffer(aad), pool)
                    val opened = aesGcmOpenWithNonceAsync(gcmKey, hexBuffer(iv), aadBuf2, ctBuf, BufferFactory.Default)
                    assertEquals(pt, opened.toHex(), "async open ct=$ptKind aad=$aadKind")
                }
            }
            pool.clear()
        }

    @Test
    fun chaChaPolySealOpenAcrossBackings() {
        if (!supportsChaChaPoly) return
        val pool = BufferPool()
        // RFC 8439 ChaCha20-Poly1305 vector.
        val ccKey = ChaChaPolyKey.of(hexBuffer("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f"))
        val ccIv = "070000004041424344454647"
        val ccAad = "50515253c0c1c2c3c4c5c6c7"
        val ccPt = "4c616469657320616e642047656e746c656d656e206f662074686520636c617373206f66202739393a"
        for (ptKind in CryptoBackings.inputs) {
            for (destKind in CryptoBackings.dests) {
                val ptBuf = CryptoBackings.place(ptKind, hexBuffer(ccPt), pool)
                val aadBuf = CryptoBackings.place(ptKind, hexBuffer(ccAad), pool)
                val dest = CryptoBackings.dest(destKind, ccPt.length / 2 + AEAD_TAG_BYTES, pool)
                chaChaPolySeal(ccKey, hexBuffer(ccIv), aadBuf, ptBuf, dest)
                dest.resetForRead()
                // Decrypt back and confirm the plaintext survives every backing.
                val openDest = CryptoBackings.dest(destKind, ccPt.length / 2, pool)
                val aadBuf2 = CryptoBackings.place(ptKind, hexBuffer(ccAad), pool)
                chaChaPolyOpen(ccKey, hexBuffer(ccIv), aadBuf2, dest, openDest)
                openDest.resetForRead()
                assertEquals(ccPt, openDest.toHex(), "chacha pt=$ptKind dest=$destKind")
            }
        }
        pool.clear()
    }
}
