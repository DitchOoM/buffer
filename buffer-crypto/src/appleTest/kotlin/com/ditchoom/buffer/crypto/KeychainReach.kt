@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class, DelicateCoroutinesApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_DUPLICATE
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_OK
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_keychain_delete
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_keychain_put
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.posix.getenv
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlin.random.Random
import kotlin.test.fail
import kotlin.time.Duration

/** Whether this test process may add to the keychain, found by adding (and removing) one probe item. */
internal sealed interface KeychainReach {
    data object Reachable : KeychainReach

    /** The add was refused with [status]: a simulator test binary carries no keychain entitlement, for one. */
    data class Refused(
        val status: Int,
    ) : KeychainReach
}

internal fun keychainReach(): KeychainReach {
    val service = "bcks-reach-${Random.nextInt(Int.MAX_VALUE)}"
    val status =
        memScoped {
            val one = allocArray<UByteVar>(1)
            bcks_keychain_put(service, "probe", one, 1.convert())
        }
    bcks_keychain_delete(service, "probe")
    val added = status == BCKS_OK || status == BCKS_ERR_DUPLICATE
    return if (added) KeychainReach.Reachable else KeychainReach.Refused(status)
}

/**
 * `true` (after printing why) when [test] cannot run because the keychain refuses this process. On a macOS CI run
 * (`CI=true`) it fails instead: a macOS test process reaches the login keychain, so a refusal there means the keychain
 * tests stopped running, and a silent skip would read as green.
 */
internal fun keychainUnreachable(test: String): Boolean =
    when (val reach = keychainReach()) {
        KeychainReach.Reachable -> false
        is KeychainReach.Refused -> skipped(test, "the keychain refused this test process (status ${reach.status})")
    }

/** `true` (after printing why) when [test] needs a usable Secure Enclave and this host has none for the process. */
internal fun enclaveUnusable(test: String): Boolean =
    when (val probe = appleEnclaveProbe()) {
        EnclaveProbe.Usable -> false
        EnclaveProbe.NotPresent, EnclaveProbe.NotEntitled, EnclaveProbe.Locked, EnclaveProbe.Refused -> {
            println("SKIPPED $test: no usable Secure Enclave for this process ($probe)")
            true
        }
    }

private fun skipped(
    test: String,
    why: String,
): Boolean {
    val message = "SKIPPED $test: $why"
    if (Platform.osFamily == OsFamily.MACOSX && getenv("CI")?.toKString() == "true") fail(message)
    println(message)
    return true
}

/**
 * Runs [threads] copies of [work] on a thread pool of their own and waits for them, in real time, for at most
 * [budget] from [Dispatchers.Default], which the pool does not share. Work that deadlocks inside the keychain fails
 * here in bounded time instead of hanging the run, however few cores the host has. The pool is closed once the work
 * finishes; after a timeout its threads are left blocked (closing would wait on them), and the test has failed.
 */
internal suspend fun <T> onOwnThreads(
    threads: Int,
    budget: Duration,
    what: String,
    work: suspend (Int) -> T,
): List<T> {
    val pool = newFixedThreadPoolContext(threads, what)
    val jobs = (0 until threads).map { i -> CoroutineScope(pool).async { work(i) } }
    val results =
        withContext(Dispatchers.Default) { withTimeoutOrNull(budget) { jobs.awaitAll() } }
            ?: fail("$what on $threads threads did not finish within $budget")
    pool.close()
    return results
}
