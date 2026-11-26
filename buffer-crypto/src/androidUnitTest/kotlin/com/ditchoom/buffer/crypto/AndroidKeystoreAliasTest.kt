package com.ditchoom.buffer.crypto

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.cert.Certificate
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.security.KeyStoreException as JcaKeyStoreException

/**
 * The provider's alias lookups over a `java.security.KeyStore` whose SPI behaves like the Android keystore's,
 * off-device: `containsAlias` answers `false` for a keystore error, and `getKey` throws instead.
 */
class AndroidKeystoreAliasTest {
    @Test
    fun twoRacingGetOrGeneratesEndWithOneKey() =
        runTest {
            val spi = FakeKeystoreSpi()
            val provider = providerOver(spi)
            val generations = AtomicInteger(0)

            val racers =
                (1..2).map { racer ->
                    async {
                        provider.getOrGenerateEntry(
                            ALIAS,
                            ProtectedKeyAlgorithm.AesGcm,
                            reattach = { spi.entries[ALIAS] },
                            generate = {
                                generations.incrementAndGet()
                                SecretKeySpec(ByteArray(KEY_BYTES) { racer.toByte() }, "AES").also {
                                    spi.entries[ALIAS] = it
                                }
                            },
                        )
                    }
                }
            val keys = racers.awaitAll().map { entry -> keyOf(entry) }

            assertEquals(1, generations.get(), "the second caller must see the first caller's key, not make another")
            assertEquals(keys[0], keys[1], "both callers hold the one stored key")
            assertEquals(keys[0], spi.entries[ALIAS])
        }

    @Test
    fun containsOnAKeystoreThatCannotAnswerIsAStorageFailureNotFalse() =
        runTest {
            val spi = FakeKeystoreSpi()
            spi.entries[ALIAS] = SecretKeySpec(ByteArray(KEY_BYTES), "AES")
            spi.failing = true
            val provider = providerOver(spi)

            val failure = assertFailsWith<KeyStoreException.StorageFailure> { provider.containsAlias(ALIAS) }
            assertTrue(failure.retryable)
        }

    @Test
    fun deleteOnAKeystoreThatCannotAnswerIsAStorageFailureNotFalse() =
        runTest {
            val spi = FakeKeystoreSpi()
            spi.entries[ALIAS] = SecretKeySpec(ByteArray(KEY_BYTES), "AES")
            spi.failing = true
            val provider = providerOver(spi)

            assertFailsWith<KeyStoreException.StorageFailure> { provider.deleteAlias(ALIAS) }
            assertTrue(spi.entries.containsKey(ALIAS), "a delete that cannot tell whether the key is there leaves it")
        }

    @Test
    fun deleteRemovesAKeyThatContainsAliasCannotSee() =
        runTest {
            val spi = FakeKeystoreSpi()
            spi.entries[ALIAS] = SecretKeySpec(ByteArray(KEY_BYTES), "AES")
            spi.containsAliasBlind = true
            val provider = providerOver(spi)

            assertTrue(provider.containsAlias(ALIAS))
            assertTrue(provider.deleteAlias(ALIAS), "the key was there and was removed")
            assertTrue(!spi.entries.containsKey(ALIAS))
            assertEquals(false, provider.deleteAlias(ALIAS), "nothing left to remove")
        }

    private fun TestScope.providerOver(spi: FakeKeystoreSpi): AndroidKeystoreHardwareKeyProvider {
        val keyStore = object : KeyStore(spi, null, "fake") {}
        keyStore.load(null)
        // A dispatcher of its own, so each keystore section suspends the caller and the two racers interleave.
        return AndroidKeystoreHardwareKeyProvider(keyStore, StandardTestDispatcher(testScheduler))
    }

    private fun keyOf(entry: AliasEntry<SecretKeySpec>): SecretKeySpec =
        when (entry) {
            is AliasEntry.Attached -> entry.key
            is AliasEntry.Generated -> entry.key
            is AliasEntry.HoldsOther -> error("unexpected ${entry.stored}")
        }

    /** An in-memory keystore SPI with the Android keystore's two lookup behaviours. */
    private class FakeKeystoreSpi : KeyStoreSpi() {
        val entries = ConcurrentHashMap<String, SecretKeySpec>()

        /** The keystore cannot answer: `getKey` throws and `containsAlias` answers `false`. */
        @Volatile var failing = false

        /** `containsAlias` answers `false` whatever is stored. */
        @Volatile var containsAliasBlind = false

        override fun engineGetKey(
            alias: String,
            password: CharArray?,
        ): Key? {
            if (failing) throw JcaKeyStoreException("keystore unavailable")
            return entries[alias]
        }

        override fun engineContainsAlias(alias: String): Boolean =
            when {
                failing || containsAliasBlind -> false
                else -> entries.containsKey(alias)
            }

        override fun engineDeleteEntry(alias: String) {
            if (failing) throw JcaKeyStoreException("keystore unavailable")
            entries.remove(alias)
        }

        override fun engineAliases(): Enumeration<String> = Collections.enumeration(entries.keys.toList())

        override fun engineSize(): Int = entries.size

        override fun engineIsKeyEntry(alias: String): Boolean = entries.containsKey(alias)

        override fun engineIsCertificateEntry(alias: String): Boolean = false

        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null

        override fun engineGetCertificate(alias: String): Certificate? = null

        override fun engineGetCreationDate(alias: String): Date? = null

        override fun engineSetKeyEntry(
            alias: String,
            key: Key,
            password: CharArray?,
            chain: Array<out Certificate>?,
        ) = throw UnsupportedOperationException()

        override fun engineSetKeyEntry(
            alias: String,
            key: ByteArray,
            chain: Array<out Certificate>?,
        ) = throw UnsupportedOperationException()

        override fun engineSetCertificateEntry(
            alias: String,
            cert: Certificate,
        ) = throw UnsupportedOperationException()

        override fun engineGetCertificateAlias(cert: Certificate): String? = null

        override fun engineStore(
            stream: OutputStream?,
            password: CharArray?,
        ) = Unit

        override fun engineLoad(
            stream: InputStream?,
            password: CharArray?,
        ) = Unit
    }

    private companion object {
        const val ALIAS = "store:identity"
        const val KEY_BYTES = 16
    }
}
