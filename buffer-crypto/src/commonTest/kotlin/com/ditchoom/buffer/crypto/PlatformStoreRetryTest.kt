package com.ditchoom.buffer.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A platform store whose backend fails to open answers with a typed [KeyStoreException] and opens
 * again on the next call — it never switches to a weaker store for the rest of the process — and a
 * resolution caches only a settled answer.
 */
class PlatformStoreRetryTest {
    @Test
    fun aRefusedOpeningIsTypedAndRetriedOnTheNextCall() =
        runTest {
            val backend = SoftwareKeyStore(InMemoryKeyStorage())
            var attempts = 0
            val store =
                RetryingKeyStore {
                    attempts++
                    if (attempts == 1) {
                        PlatformStoreOpening.Refused(KeyStoreException.StorageFailure(retryable = true))
                    } else {
                        PlatformStoreOpening.Opened(backend)
                    }
                }

            val refused = assertFailsWith<KeyStoreException.StorageFailure> { store.contains("identity") }
            assertTrue(refused.retryable, "a store that failed to open is worth retrying")

            store.getOrGenerateSigning("identity", SignatureScheme.EcdsaP256)
            assertTrue(backend.contains("identity"), "the retried opening reaches the real backend, not a stand-in")
            assertTrue(store.contains("identity"))
            assertEquals(2, attempts, "an opened store is kept; only the refused attempt is repeated")
        }

    @Test
    fun aStoreThatNeverOpensNeverHandsOutAKey() =
        runTest {
            val store = RetryingKeyStore { PlatformStoreOpening.Refused(KeyStoreException.AccessDenied()) }
            repeat(3) {
                assertFailsWith<KeyStoreException.AccessDenied> {
                    store.getOrGenerateSigning("identity", SignatureScheme.EcdsaP256)
                }
            }
            assertFailsWith<KeyStoreException.AccessDenied> {
                store.requireTier(ProtectedKeyAlgorithm.EcdsaP256, CustodyTier.ExportableSoftware)
            }
        }

    @Test
    fun onlyASettledResolutionIsCached() {
        var computed = 0
        val unsettledThenSettled =
            SettlingLazy {
                computed++
                if (computed < 3) Settlement.Unsettled("refused-$computed") else Settlement.Settled("available")
            }
        assertEquals("refused-1", unsettledThenSettled.get())
        assertEquals("refused-2", unsettledThenSettled.get(), "an unsettled refusal is computed again")
        assertEquals("available", unsettledThenSettled.get())
        assertEquals("available", unsettledThenSettled.get())
        assertEquals(3, computed, "a settled answer is never recomputed")
    }

    @Test
    fun theFirstSettledAnswerWins() {
        val first = Any()
        val lazy = SettlingLazy { Settlement.Settled(first) }
        assertSame(first, lazy.get())
        assertSame(first, lazy.get())
    }
}
