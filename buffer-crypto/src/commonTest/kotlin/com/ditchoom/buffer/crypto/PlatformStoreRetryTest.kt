package com.ditchoom.buffer.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

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

    /**
     * Two first calls open at once: the inner one stores its store first, so the outer one's store is never used and
     * is closed rather than left open.
     */
    @Test
    fun aStoreOpenedByALosingFirstCallIsClosed() {
        val stores = mutableListOf<ClosingStore>()
        lateinit var store: RetryingKeyStore
        store =
            RetryingKeyStore {
                val mine = ClosingStore().also { stores += it }
                // The first opening is overtaken by a second call that opens and stores its store first.
                if (stores.size == 1) store.custodyFor(ProtectedKeyAlgorithm.EcdsaP256)
                PlatformStoreOpening.Opened(mine)
            }
        store.custodyFor(ProtectedKeyAlgorithm.EcdsaP256)
        assertEquals(2, stores.size)
        assertTrue(stores[0].closed, "the store that lost the race is closed")
        assertTrue(!stores[1].closed, "the store every call uses stays open")
    }

    private class ClosingStore : KeyStore by SoftwareKeyStore(InMemoryKeyStorage()) {
        var closed = false

        override fun close() {
            closed = true
        }
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
        val clock = TestTimeSource()
        val unsettledThenSettled =
            SettlingLazy(1.seconds, clock) {
                computed++
                if (computed < 3) Settlement.Unsettled("refused-$computed") else Settlement.Settled("available")
            }
        assertEquals("refused-1", unsettledThenSettled.get())
        assertEquals("refused-1", unsettledThenSettled.get(), "a refusal stands for the retry window")
        assertEquals(1, computed)
        clock += 1.seconds
        assertEquals("refused-2", unsettledThenSettled.get(), "after the window the refusal is computed again")
        clock += 1.seconds
        assertEquals("available", unsettledThenSettled.get())
        clock += 10.seconds
        assertEquals("available", unsettledThenSettled.get())
        assertEquals(3, computed, "a settled answer is never recomputed")
    }

    /**
     * Two reads compute at once: the outer one starts first and settles last. The answer every read sees is the one
     * that was stored first, not the one that started first.
     */
    @Test
    fun theFirstSettledAnswerWins() {
        val startedFirst = Any()
        val settledFirst = Any()
        var computed = 0
        lateinit var lazy: SettlingLazy<Any>
        lazy =
            SettlingLazy(1.seconds, TestTimeSource()) {
                computed++
                if (computed == 1) {
                    assertSame(settledFirst, lazy.get(), "the read that started second settles first")
                    Settlement.Settled(startedFirst)
                } else {
                    Settlement.Settled(settledFirst)
                }
            }
        assertSame(settledFirst, lazy.get(), "the read that started first sees the answer stored first")
        assertSame(settledFirst, lazy.get())
        assertEquals(2, computed)
    }

    /**
     * Three reads probe at once during one passing failure: each starts its probe before any stores its answer. That
     * is one undecided window, not three, so the limit is not reached by one moment of contention.
     */
    @Test
    fun concurrentUndecidedAnswersInOneWindowCountOnce() {
        val clock = TestTimeSource()
        var computed = 0
        lateinit var lazy: SettlingLazy<String>
        lazy =
            SettlingLazy(1.seconds, clock, undecidedLimit = 3) {
                computed++
                if (computed < 3) lazy.get()
                Settlement.Undecided("undecided")
            }
        assertEquals("undecided", lazy.get())
        assertEquals(3, computed, "three reads probed at once")
        clock += 1.seconds
        lazy.get()
        assertEquals(4, computed, "one window of contention did not settle the answer")
        clock += 1.seconds
        lazy.get()
        assertEquals(5, computed)
        clock += 1.seconds
        lazy.get()
        assertEquals(5, computed, "the third undecided window settled it")
    }
}
