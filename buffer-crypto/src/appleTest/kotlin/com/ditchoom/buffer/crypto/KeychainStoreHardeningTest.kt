@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_DENIED
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_DUPLICATE
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_INTERNAL
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_LOCKED
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_OK
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_keychain_delete
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_keychain_get
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_keychain_migrate
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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The Keychain store's failure and concurrency contract: Enclave failures by family, a locked store open, add-only
 * writes, and keychain calls from many threads. How each keychain status is classified is in
 * [KeychainStatusClassificationTest]. A test that needs the keychain or the Enclave and cannot have it here says so
 * on stdout, and fails on a macOS CI run where the keychain refuses (see [keychainUnreachable]).
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

    /** A caller told the device is locked retries after the unlock, and that retry probes again at once. */
    @Test
    fun aLockedProbeIsAskedAgainOnTheNextCall() {
        var status = BCKS_ERR_LOCKED
        var probes = 0
        val probe =
            SettlingLazy(PROBE_RETRY_AFTER, kotlin.time.TestTimeSource()) {
                probes++
                enclaveProbeOf(status)
            }
        assertEquals(EnclaveProbe.Locked, probe.get())
        status = BCKS_OK
        assertEquals(EnclaveProbe.Usable, probe.get(), "the unlocked device is probed again, not held locked")
        assertEquals(2, probes)
    }

    /** Needs the Enclave (the store mints Enclave keys); bounded in real time on threads of its own. */
    @Test
    fun fourEnclaveStoresRacingToCreateOneAliasEndWithOneKey() =
        runTest(timeout = RACE_BUDGET * 2) {
            if (enclaveUnusable("fourEnclaveStoresRacingToCreateOneAliasEndWithOneKey")) return@runTest
            val name = "bcks-race-${kotlin.random.Random.nextInt(Int.MAX_VALUE)}"
            val keys =
                onOwnThreads(STORE_RACERS, RACE_BUDGET, "four concurrent get-or-generates") {
                    CryptoCapabilities
                        .keyStore(KeyStoreConfig(name = name))
                        .getOrGenerateSigning("one", SignatureScheme.EcdsaP256)
                }
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
     * and waits for the keychain's). The racers have [KEYCHAIN_THREADS] threads of their own and the bound is kept off
     * them, so a deadlock fails in bounded time on any host.
     */
    @Test
    fun keychainCallsFromManyThreadsAllFinish() =
        runTest(timeout = RACE_BUDGET * 2) {
            if (keychainUnreachable("keychainCallsFromManyThreadsAllFinish")) return@runTest
            val service = "bcks-threads-${kotlin.random.Random.nextInt(Int.MAX_VALUE)}"
            try {
                onOwnThreads(KEYCHAIN_THREADS, RACE_BUDGET, "keychain calls") { worker ->
                    // Every worker contends on one item: reads land on an item another is adding.
                    repeat(KEYCHAIN_ROUNDS) { round ->
                        putByte(service, FIRST, SHARED_ACCOUNT)
                        getStatus(service, SHARED_ACCOUNT)
                        if ((round + worker) % 2 == 0) bcks_keychain_delete(service, SHARED_ACCOUNT)
                    }
                }
            } finally {
                bcks_keychain_delete(service, SHARED_ACCOUNT)
            }
        }

    /** The add-only write is what lets a racing get-or-generate keep the first key. Needs only the keychain. */
    @Test
    fun aPutNeverReplacesTheRecordAlreadyUnderAnAlias() =
        runTest(timeout = RACE_BUDGET * 2) {
            if (keychainUnreachable("aPutNeverReplacesTheRecordAlreadyUnderAnAlias")) return@runTest
            val service = "bcks-put-${kotlin.random.Random.nextInt(Int.MAX_VALUE)}"
            try {
                onOwnThreads(1, RACE_BUDGET, "add-only put") {
                    val first = putByte(service, FIRST)
                    assertEquals(BCKS_OK, first, "first put status $first")
                    val second = putByte(service, SECOND)
                    assertEquals(BCKS_ERR_DUPLICATE, second, "second put status $second")
                    assertEquals(FIRST, readByte(service))
                }
            } finally {
                bcks_keychain_delete(service, "one")
            }
        }

    /**
     * The protection-class move matches only items under the earlier class, so it is accepted by this host's keychain
     * and leaves an item already under the current class as it was. That an earlier-class item is moved is shown only
     * by the data protection keychain on a device.
     */
    @Test
    fun theClassMoveLeavesACurrentItemAsItIs() =
        runTest(timeout = RACE_BUDGET * 2) {
            if (keychainUnreachable("theClassMoveLeavesACurrentItemAsItIs")) return@runTest
            val service = "bcks-migrate-${kotlin.random.Random.nextInt(Int.MAX_VALUE)}"
            try {
                onOwnThreads(1, RACE_BUDGET, "class move") {
                    assertEquals(BCKS_OK, putByte(service, FIRST))
                    assertEquals(BCKS_OK, bcks_keychain_migrate(service), "no item of the earlier class to move")
                    assertEquals(FIRST, readByte(service))
                }
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
        const val STORE_RACERS = 4
        const val KEYCHAIN_ROUNDS = 50
        const val SHARED_ACCOUNT = "shared"
    }

    private fun com.ditchoom.buffer.ReadBuffer.toHexForTest(): String =
        (0 until remaining()).joinToString("") { i ->
            (get(position() + i).toInt() and BYTE_MASK).toString(HEX).padStart(2, '0')
        }
}
