@file:OptIn(
    ExperimentalAtomicApi::class,
    ExperimentalForeignApi::class,
    DelicateCoroutinesApi::class,
    ExperimentalCoroutinesApi::class,
)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_INTERNAL
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_LOCKED
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_OK
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.posix.usleep
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * What the Apple Keychain store decides from what the keychain answers, over a fake keychain: no Enclave and no real
 * keychain is needed, so these run on every Apple host.
 */
class KeychainStoreDecisionsTest {
    @Test
    fun aGeneratedKeyWhoseRecordCannotBeAddedIsReleased() =
        runTest {
            val records = FakeRecords().apply { putFailure = { KeyStoreException.DeviceLocked() } }
            var released = 0
            assertFailsWith<KeyStoreException.DeviceLocked> {
                AppleKeychainKeyStore(enclaveProvider, records).getOrGenerate(
                    "one",
                    ProtectedKeyAlgorithm.EcdsaP256,
                    attach = { "attached" },
                    generate = { "generated" to signingRecord() },
                    release = { released++ },
                )
            }
            assertEquals(1, released, "the key the failed add would have kept is released")
        }

    @Test
    fun aDuplicateThatNoReadReturnsIsAccessDeniedNotARetryableFailure() =
        runTest {
            // The keychain holds an item under the alias that this process cannot read.
            val records = FakeRecords().apply { invisibleDuplicate = true }
            var released = 0
            assertFailsWith<KeyStoreException.AccessDenied> {
                AppleKeychainKeyStore(enclaveProvider, records).getOrGenerate(
                    "one",
                    ProtectedKeyAlgorithm.EcdsaP256,
                    attach = { "attached" },
                    generate = { "generated" to signingRecord() },
                    release = { released++ },
                )
            }
            assertEquals(1, released)
            assertEquals(2, records.puts, "the add is asked once more before the record is called invisible")
        }

    @Test
    fun aDuplicateDeletedBeforeItCouldBeReadLetsTheSecondAddSucceed() =
        runTest {
            val records = FakeRecords().apply { duplicateThenGone = true }
            var released = 0
            val key =
                AppleKeychainKeyStore(enclaveProvider, records).getOrGenerate(
                    "one",
                    ProtectedKeyAlgorithm.EcdsaP256,
                    attach = { "attached" },
                    generate = { "generated" to signingRecord() },
                    release = { released++ },
                )
            assertEquals("generated", key)
            assertEquals(0, released)
        }

    /**
     * The caller is cancelled while its record is being added: the record is kept (the key is stored), and the key
     * handle the caller will never receive is released.
     */
    @Test
    fun aKeyAddedForACallerCancelledMeanwhileIsReleased() =
        runTest {
            val records = FakeRecords()
            var released = 0
            val caller =
                launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                    AppleKeychainKeyStore(enclaveProvider, records).getOrGenerate<String>(
                        "one",
                        ProtectedKeyAlgorithm.EcdsaP256,
                        attach = { "attached" },
                        generate = { "generated" to signingRecord() },
                        release = { released++ },
                    )
                }
            records.onPut = { caller.cancel() }
            caller.start()
            caller.join()
            assertTrue(caller.isCancelled)
            assertEquals(1, records.puts)
            assertEquals(1, released, "the handle the cancelled caller never received is released")
        }

    /**
     * A class move that settled by failing does not let a listing skip its guard: while the device is locked, a record
     * still under the earlier class is withheld, so the listing is DeviceLocked, not a listing that misses it.
     */
    @Test
    fun aListingAfterAMoveThatFailedStillRefusesWhileLocked() =
        runTest {
            val records = FakeRecords().apply { migrateStatuses = mutableListOf(BCKS_ERR_INTERNAL, BCKS_ERR_LOCKED) }
            val store = AppleKeychainKeyStore(enclaveProvider, records)
            store.contains("one")
            assertFailsWith<KeyStoreException.DeviceLocked> { store.aliases() }
        }

    @Test
    fun anEmptyOrTruncatedRecordIsACorruptEntry() {
        assertFailsWith<KeyStoreException.CorruptEntry> { enclaveRecordAlgorithm(BufferFactory.Default.allocate(0)) }
        val truncated = BufferFactory.Default.allocate(3)
        truncated.writeByte(EnclaveKeyKind.Signing.tag.toByte())
        truncated.writeShort(P256_POINT_BYTES.toShort())
        truncated.resetForRead()
        assertFailsWith<KeyStoreException.CorruptEntry> { parseEnclaveRecord(truncated) }
    }

    @Test
    fun hardwareWithNoEnclaveIsAStoreFailureThatRetryingWillNotFix() {
        val opening = openAppleKeychainStore("bcks-none", EnclaveProbe.NotPresent)
        val refused = assertIs<PlatformStoreOpening.Refused>(opening)
        val failure = assertIs<KeyStoreException.StorageFailure>(refused.failure)
        assertTrue(!failure.retryable, "no later call finds an Enclave on this hardware")
    }

    @Test
    fun custodyForAnAlgorithmTheEnclaveCannotHoldIsNotEligible() {
        val store = AppleKeychainKeyStore(enclaveProvider, FakeRecords())
        listOf(ProtectedKeyAlgorithm.Ed25519, ProtectedKeyAlgorithm.AesGcm, ProtectedKeyAlgorithm.EcdsaP384).forEach {
            assertFailsWith<HardwareKeyException.AlgorithmNotEligible>("$it") { store.custodyFor(it) }
        }
        assertEquals(enclaveProvider.custody, store.custodyFor(ProtectedKeyAlgorithm.EcdsaP256))
    }

    /**
     * One keychain call blocks (a macOS access prompt nobody answers) while many more callers arrive. They must wait
     * suspended, not each hold a thread blocked behind it: on Darwin `Dispatchers.Default` is GCD's pool, which adds a
     * thread for every blocked caller until its limit, where every GCD client in the process stalls.
     */
    @Test
    fun callersWaitingOnABlockedKeychainCallHoldNoThread() =
        runTest(timeout = TEST_BUDGET) {
            val open = AtomicInt(0)
            val records = FakeRecords().apply { blockContainsUntil = open }
            val store = AppleKeychainKeyStore(enclaveProvider, records)
            val checker = newSingleThreadContext("keychain-checker")
            val callers = CoroutineScope(Dispatchers.Default)
            try {
                val waiting = (0 until CALLERS).map { callers.async { store.contains("one") } }
                withContext(checker) {
                    val started = TimeSource.Monotonic.markNow()
                    while (records.containsEntered.load() == 0 && started.elapsedNow() < TEST_BUDGET) usleep(POLL_US)
                    // Time for every caller that would block a thread to reach the keychain call.
                    usleep(SETTLE_US)
                }
                val blockedAtOnce = records.mostInsideAtOnce.load()
                open.store(1)
                withContext(checker) { withTimeoutOrNull(TEST_BUDGET) { waiting.awaitAll() } }
                    ?: fail("the waiting callers did not finish after the blocked call returned")
                assertEquals(1, blockedAtOnce, "callers waiting on the blocked keychain call each held a thread")
                assertEquals(1, records.mostInsideAtOnce.load())
            } finally {
                open.store(1)
                checker.close()
            }
        }

    private fun signingRecord(): PlatformBuffer {
        val record = BufferFactory.Default.allocate(1)
        record.writeByte(EnclaveKeyKind.Signing.tag.toByte())
        record.resetForRead()
        return record
    }

    /** An in-memory keychain with the answers a real one gives in the cases under test. */
    private class FakeRecords : KeychainRecords {
        private val stored = mutableMapOf<String, Byte>()
        var putFailure: (() -> KeyStoreException)? = null
        var onPut: () -> Unit = {}
        var migrateStatuses = mutableListOf<Int>()
        var invisibleDuplicate = false
        var duplicateThenGone = false
        var blockContainsUntil: AtomicInt? = null
        val containsEntered = AtomicInt(0)
        private val inside = AtomicInt(0)
        val mostInsideAtOnce = AtomicInt(0)
        var puts = 0

        override fun put(
            alias: String,
            record: ReadBuffer,
        ): KeychainPut {
            puts++
            onPut()
            putFailure?.let { throw it() }
            val present = invisibleDuplicate || (duplicateThenGone && puts == 1) || stored.containsKey(alias)
            if (!present) stored[alias] = record.get(record.position())
            return if (present) KeychainPut.AlreadyPresent else KeychainPut.Added
        }

        override fun get(alias: String): PlatformBuffer? =
            stored[alias]?.let { kind ->
                BufferFactory.Default.allocate(1).also {
                    it.writeByte(kind)
                    it.resetForRead()
                }
            }

        override fun contains(alias: String): Boolean {
            containsEntered.addAndFetch(1)
            val now = inside.addAndFetch(1)
            while (true) {
                val most = mostInsideAtOnce.load()
                if (now <= most || mostInsideAtOnce.compareAndSet(most, now)) break
            }
            try {
                val gate = blockContainsUntil
                if (gate != null) while (gate.load() == 0) usleep(POLL_US)
                return stored.containsKey(alias)
            } finally {
                inside.addAndFetch(-1)
            }
        }

        override fun delete(alias: String): Boolean = stored.remove(alias) != null

        override fun aliases(): Set<String> = stored.keys.toSet()

        override fun migrate(): Int = if (migrateStatuses.isEmpty()) BCKS_OK else migrateStatuses.removeAt(0)
    }

    private companion object {
        val TEST_BUDGET = 30.seconds
        const val CALLERS = 32
        const val SETTLE_US = 300_000u
        const val POLL_US = 1_000u
    }
}
