package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.repeatedByte
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [HkdfKey] against raw-byte HKDF. Every case runs over both the in-memory key ([HkdfKey.of]) and the
 * platform import ([CryptoCapabilities.importHkdfKey]), which on js/wasmJs is a non-extractable
 * WebCrypto key: the two must agree with [Hkdf.derive] and the AEAD witness byte for byte.
 */
class HkdfKeyTest {
    private val rfcIkm = "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"
    private val rootHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"

    private suspend fun bothKinds(ikmHex: String): List<HkdfKey> =
        listOf(HkdfKey.of(hexBuffer(ikmHex)), CryptoCapabilities.importHkdfKey(hexBuffer(ikmHex)))

    private fun lengthPrefixed(vararg parts: String): ReadBuffer {
        val out = BufferFactory.Default.allocate(parts.sumOf { 4 + it.length })
        for (part in parts) {
            out.writeInt(part.length)
            out.write(ascii(part))
        }
        out.resetForRead()
        return out
    }

    @Test
    fun deriveBytesMatchesRfc5869WithSaltAndInfo() =
        runTest {
            for (key in bothKinds(rfcIkm)) {
                val okm =
                    key.deriveBytes(
                        Salt.Of(hexBuffer("000102030405060708090a0b0c")),
                        Info.Of(hexBuffer("f0f1f2f3f4f5f6f7f8f9")),
                        42,
                    )
                assertEquals(
                    "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
                    okm.toHex(),
                    "custody ${key.custody}",
                )
                key.close()
            }
        }

    @Test
    fun anAbsentSaltAndInfoAreTheZeroBlockAndEmpty() =
        runTest {
            for (key in bothKinds(rfcIkm)) {
                assertEquals(
                    "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
                    key.deriveBytes(Salt.None, Info.None, 42).toHex(),
                    "custody ${key.custody}",
                )
                key.close()
            }
        }

    @Test
    fun aChildKeyDerivesWhatHkdfDerivesFromTheChildsBytes() =
        runTest {
            val first = lengthPrefixed("level", "one")
            val second = lengthPrefixed("level", "two")
            val childBytes =
                Hkdf.derive(Salt.None, hexBuffer(rootHex), Info.Of(first.slice()), 32, BufferFactory.Default)
            val expected =
                Hkdf.derive(Salt.None, childBytes, Info.Of(second.slice()), 32, BufferFactory.Default).toHex()
            for (root in bothKinds(rootHex)) {
                val child = root.deriveHkdfKey(Salt.None, Info.Of(first))
                assertEquals(root.custody, child.custody, "a child keeps its parent's custody")
                assertEquals(32, child.sizeBytes)
                val derived = child.deriveBytes(Salt.None, Info.Of(second), 32).toHex()
                assertEquals(expected, derived, "custody ${root.custody}")
                child.close()
                root.close()
            }
        }

    @Test
    fun aDerivedAesKeySealsExactlyAsTheRawKeyUnderTheSameNonce() =
        runTest {
            val info = lengthPrefixed("aead")
            val raw =
                AesGcmKey.of(
                    Hkdf.derive(Salt.None, hexBuffer(rootHex), Info.Of(info.slice()), 32, BufferFactory.Default),
                )
            val nonce = hexBuffer("0102030405060708090a0b0c")
            val aad = hexBuffer("aabbccdd")
            val plaintext = ascii("a derived key seals like the raw key")
            val expected =
                aesGcmAsyncOps().sealWithNonce(raw, nonce.slice(), plaintext.slice(), Aad.Of(aad.slice())).toHex()
            for (root in bothKinds(rootHex)) {
                val aes = root.deriveAesGcmKey(Salt.None, Info.Of(info))
                assertEquals(256, aes.sizeBits)
                assertEquals(root.custody, aes.custody)
                val sealed = aes.sealWithNonce(nonce.slice(), plaintext.slice(), Aad.Of(aad.slice()))
                assertEquals(expected, sealed.toHex(), "custody ${root.custody}")
                val opened = aes.openWithNonce(nonce.slice(), hexBuffer(expected), Aad.Of(aad.slice()))
                assertEquals(plaintext.toHex(), opened.toHex())
                aes.close()
                root.close()
            }
            raw.close()
        }

    @Test
    fun aTamperedCiphertextOrWrongAadIsRefusedOpaquely() =
        runTest {
            val nonce = hexBuffer("0102030405060708090a0b0c")
            for (root in bothKinds(rootHex)) {
                val aes = root.deriveAesGcmKey(Salt.None, Info.None)
                val sealed = aes.sealWithNonce(nonce.slice(), ascii("payload"), Aad.Of(ascii("bound")))
                val tampered = BufferFactory.Default.allocate(sealed.remaining())
                tampered.write(sealed.slice())
                tampered.set(0, (tampered.get(0).toInt() xor 1).toByte())
                tampered.resetForRead()
                assertFailsWith<VerificationFailed> {
                    aes.openWithNonce(nonce.slice(), tampered, Aad.Of(ascii("bound")))
                }
                assertFailsWith<VerificationFailed> {
                    aes.openWithNonce(nonce.slice(), sealed.slice(), Aad.Of(ascii("other")))
                }
                aes.close()
                root.close()
            }
        }

    @Test
    fun aKeyDerivedUnderAnotherInfoDoesNotOpen() =
        runTest {
            val nonce = hexBuffer("0102030405060708090a0b0c")
            for (root in bothKinds(rootHex)) {
                val aes = root.deriveAesGcmKey(Salt.None, Info.Of(ascii("a")))
                val sealed = aes.sealWithNonce(nonce.slice(), ascii("x"))
                val other = root.deriveAesGcmKey(Salt.None, Info.Of(ascii("b")))
                assertFailsWith<VerificationFailed> { other.openWithNonce(nonce.slice(), sealed) }
                root.close()
            }
        }

    @Test
    fun theCallersSaltAndInfoBuffersAreNotConsumed() =
        runTest {
            for (root in bothKinds(rootHex)) {
                val salt = hexBuffer("0001020304")
                val info = hexBuffer("0506070809")
                root.deriveBytes(Salt.Of(salt), Info.Of(info), 32)
                root.deriveHkdfKey(Salt.Of(salt), Info.Of(info)).close()
                root.deriveAesGcmKey(Salt.Of(salt), Info.Of(info)).close()
                assertEquals(5, salt.remaining())
                assertEquals(5, info.remaining())
                root.close()
            }
        }

    @Test
    fun sizesOutsideTheContractAreRejected() =
        runTest {
            assertFailsWith<IllegalArgumentException> { HkdfKey.of(repeatedByte(1, HKDF_KEY_MIN_BYTES - 1)) }
            assertFailsWith<IllegalArgumentException> {
                CryptoCapabilities.importHkdfKey(repeatedByte(1, HKDF_KEY_MIN_BYTES - 1))
            }
            for (root in bothKinds(rootHex)) {
                assertFailsWith<IllegalArgumentException> { root.deriveBytes(Salt.None, Info.None, 0) }
                assertFailsWith<IllegalArgumentException> {
                    root.deriveBytes(Salt.None, Info.None, HKDF_SHA256_MAX_OUTPUT_BYTES + 1)
                }
                assertFailsWith<IllegalArgumentException> {
                    root.deriveHkdfKey(Salt.None, Info.None, HKDF_KEY_MIN_BYTES - 1)
                }
                assertFailsWith<IllegalArgumentException> { root.deriveAesGcmKey(Salt.None, Info.None, 192) }
                val aes = root.deriveAesGcmKey(Salt.None, Info.None, 128)
                assertEquals(128, aes.sizeBits)
                assertFailsWith<IllegalArgumentException> {
                    aes.sealWithNonce(hexBuffer("0102030405060708090a0b"), ascii("x"))
                }
                aes.close()
                root.close()
            }
        }

    @Test
    fun aSoftwareStoreImportsReloadsRefusesAnOccupiedAliasAndDeletes() =
        runTest {
            val storage = InMemoryKeyStorage()
            val store = CryptoCapabilities.hkdfKeyStore(KeyStoreConfig(name = "hkdf-software", storage = storage))
            assertEquals(KeyCustody.ExportableSoftware, store.custody)
            val info = Info.Of(ascii("child"))
            val imported = store.importKey("root", hexBuffer(rootHex))
            val expected = imported.deriveBytes(Salt.None, info, 32).toHex()
            imported.close()

            val reloaded = assertNotNullKey(store.load("root"))
            assertEquals(32, reloaded.sizeBytes)
            assertEquals(expected, reloaded.deriveBytes(Salt.None, info, 32).toHex())
            reloaded.close()

            assertFailsWith<KeyStoreException.AliasOccupied> { store.importKey("root", hexBuffer(rootHex)) }
            assertTrue(store.contains("root"))
            assertEquals(setOf("root"), store.aliases())
            assertTrue(store.delete("root"))
            assertFalse(store.delete("root"))
            assertNull(store.load("root"))
        }

    @Test
    fun aStoreLoadOverAnotherKindOfEntryIsAbsent() =
        runTest {
            val storage = InMemoryKeyStorage()
            CryptoCapabilities.keyStore(KeyStoreConfig(name = "shared", storage = storage)).getOrGenerateAesGcm("aes")
            assertNull(CryptoCapabilities.hkdfKeyStore(KeyStoreConfig(name = "shared", storage = storage)).load("aes"))
        }

    private fun assertNotNullKey(key: HkdfKey?): HkdfKey = kotlin.test.assertNotNull(key)
}
