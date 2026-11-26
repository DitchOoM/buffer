@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_DENIED
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_DUPLICATE
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_INTERNAL
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_LOCKED
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_OK
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_keychain_delete
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_keychain_get
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_keychain_put
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * The Keychain store's failure and concurrency contract: Enclave failures by family, a locked store open, add-only
 * writes, and keychain calls from many threads. How each keychain status is classified is in
 * [KeychainStatusClassificationTest].
 */
class KeychainStoreHardeningTest {
    @Test
    fun aLockedOrUnentitledEnclaveOperationIsAKeyFailureNotAStoreFailure() {
        assertIs<HardwareKeyException.DeviceLocked>(enclaveGenerateFailure(BCKS_ERR_LOCKED))
        assertIs<HardwareKeyException.AccessDenied>(enclaveGenerateFailure(BCKS_ERR_DENIED))
        assertIs<HardwareKeyException.SecureElementUnavailable>(enclaveGenerateFailure(BCKS_ERR_INTERNAL))
        assertIs<HardwareKeyException.DeviceLocked>(enclaveSignFailure(BCKS_ERR_LOCKED))
        assertIs<HardwareKeyException.DeviceLocked>(enclaveAgreeFailure(BCKS_ERR_LOCKED))
    }

    @Test
    fun aStoreOpenedOnALockedDeviceIsDeviceLocked() =
        runTest {
            val probe = enclaveProbeOf(BCKS_ERR_LOCKED)
            assertIs<Settlement.Unsettled<EnclaveProbe>>(probe, "asked again after the unlock")
            val store = RetryingKeyStore { openAppleKeychainStore("bcks-probe-locked", probe.value) }
            assertFailsWith<KeyStoreException.DeviceLocked> { store.aliases() }
        }

    /**
     * The racers run detached from the test, under a real-time bound: concurrent keychain calls that deadlock fail
     * here instead of hanging the run, and nothing touches the keychain after such a failure.
     */
    @Test
    fun fourEnclaveStoresRacingToCreateOneAliasEndWithOneKey() =
        runTest(timeout = RACE_BUDGET * 2) {
            if (appleEnclaveProviderOrNull() == null) return@runTest
            val name = "bcks-race-${kotlin.random.Random.nextInt(Int.MAX_VALUE)}"
            val racers = CoroutineScope(Dispatchers.Default)
            val keys =
                withContext(Dispatchers.Default) {
                    withTimeoutOrNull(RACE_BUDGET) {
                        (0 until 4)
                            .map {
                                racers.async {
                                    CryptoCapabilities
                                        .keyStore(KeyStoreConfig(name = name))
                                        .getOrGenerateSigning("one", SignatureScheme.EcdsaP256)
                                }
                            }.awaitAll()
                    }
                } ?: fail("four concurrent get-or-generates did not finish within $RACE_BUDGET")
            try {
                val stored = assertNotNull(CryptoCapabilities.keyStore(KeyStoreConfig(name = name)).loadSigning("one"))
                val expected = stored.verifyKey.exportEncoded().toHexForTest()
                keys.forEach { key ->
                    assertEquals(expected, key.verifyKey.exportEncoded().toHexForTest(), "each racer holds it")
                }
            } finally {
                CryptoCapabilities.keyStore(KeyStoreConfig(name = name)).delete("one")
            }
        }

    /**
     * Adds, reads and deletes from many threads at once: the call mix that deadlocks inside the macOS file keychain
     * when two calls run concurrently (an add holds the keychain lock and waits for an item's, a read holds the item's
     * and waits for the keychain's). Bounded in real time, so a deadlock fails instead of hanging.
     */
    @Test
    fun keychainCallsFromManyThreadsAllFinish() =
        runTest(timeout = RACE_BUDGET * 2) {
            if (appleEnclaveProviderOrNull() == null) return@runTest
            val service = "bcks-threads-${kotlin.random.Random.nextInt(Int.MAX_VALUE)}"
            val workers = CoroutineScope(Dispatchers.Default)
            val finished =
                withContext(Dispatchers.Default) {
                    withTimeoutOrNull(RACE_BUDGET) {
                        (0 until KEYCHAIN_THREADS)
                            .map { worker ->
                                workers.async {
                                    // Every worker contends on one item: reads land on an item another is adding.
                                    repeat(KEYCHAIN_ROUNDS) { round ->
                                        putByte(service, FIRST, SHARED_ACCOUNT)
                                        getStatus(service, SHARED_ACCOUNT)
                                        if ((round + worker) % 2 == 0) bcks_keychain_delete(service, SHARED_ACCOUNT)
                                    }
                                }
                            }.awaitAll()
                    }
                }
            assertNotNull(finished, "keychain calls from $KEYCHAIN_THREADS threads did not finish within $RACE_BUDGET")
            bcks_keychain_delete(service, SHARED_ACCOUNT)
        }

    @Test
    fun aPutNeverReplacesTheRecordAlreadyUnderAnAlias() {
        // The add-only write is what lets a racing get-or-generate keep the first key. The Keychain
        // store opens only where the Secure Enclave is usable, so the test runs on the same hosts.
        if (appleEnclaveProviderOrNull() == null) return
        val service = "bcks-put-${kotlin.random.Random.nextInt(Int.MAX_VALUE)}"
        try {
            val first = putByte(service, FIRST)
            assertEquals(BCKS_OK, first, "first put status $first")
            val second = putByte(service, SECOND)
            assertEquals(BCKS_ERR_DUPLICATE, second, "second put status $second")
            assertEquals(FIRST, readByte(service))
        } finally {
            bcks_keychain_delete(service, "one")
        }
    }

    private fun putByte(
        service: String,
        value: Byte,
        account: String = "one",
    ): Int =
        memScoped {
            val one = allocArray<UByteVar>(1)
            one[0] = value.toUByte()
            bcks_keychain_put(service, account, one, 1.convert())
        }

    private fun getStatus(
        service: String,
        account: String,
    ): Int =
        memScoped {
            val out = allocArray<UByteVar>(1)
            val len = alloc<platform.posix.size_tVar>()
            bcks_keychain_get(service, account, out, 1.convert(), len.ptr)
        }

    private fun readByte(
        service: String,
        account: String = "one",
    ): Byte =
        memScoped {
            val out = allocArray<UByteVar>(1)
            val len = alloc<platform.posix.size_tVar>()
            assertEquals(BCKS_OK, bcks_keychain_get(service, account, out, 1.convert(), len.ptr))
            out[0].toByte()
        }

    @Test
    fun aRefusedEnclaveProbeIsTypedAndNeverASoftwareStore() =
        runTest {
            val unentitled =
                RetryingKeyStore { openAppleKeychainStore("bcks-probe-refused", EnclaveProbe.NotEntitled) }
            assertFailsWith<KeyStoreException.AccessDenied> { unentitled.aliases() }

            val refused = RetryingKeyStore { openAppleKeychainStore("bcks-probe-refused", EnclaveProbe.Refused) }
            assertTrue(assertFailsWith<KeyStoreException.StorageFailure> { refused.aliases() }.retryable)

            assertIs<Settlement.Settled<EnclaveProbe>>(enclaveProbeOf(BCKS_ERR_DENIED))
            assertIs<Settlement.Settled<EnclaveProbe>>(enclaveProbeOf(BCKS_OK))
            assertIs<Settlement.Unsettled<EnclaveProbe>>(
                enclaveProbeOf(BCKS_ERR_INTERNAL),
                "a refused probe is asked again",
            )
        }

    private companion object {
        const val BYTE_MASK = 0xFF
        const val FIRST: Byte = 1
        const val SECOND: Byte = 2
        const val HEX = 16
        val RACE_BUDGET = 30.seconds
        const val KEYCHAIN_THREADS = 8
        const val KEYCHAIN_ROUNDS = 200
        const val SHARED_ACCOUNT = "shared"
    }

    private fun com.ditchoom.buffer.ReadBuffer.toHexForTest(): String =
        (0 until remaining()).joinToString("") { i ->
            (get(position() + i).toInt() and BYTE_MASK).toString(HEX).padStart(2, '0')
        }
}
