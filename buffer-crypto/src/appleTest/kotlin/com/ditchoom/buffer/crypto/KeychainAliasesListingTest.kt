@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_OK
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_keychain_delete
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_keychain_put
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * The Keychain store lists every alias however many it holds: the listing is sized from what the
 * keychain reports, not from a fixed buffer. Records are written straight through the shim, so no
 * Enclave key is minted for them; each run uses its own service name and deletes what it wrote.
 */
class KeychainAliasesListingTest {
    private companion object {
        val BUDGET = 60.seconds
    }

    @Test
    fun aliasesAreListedPastAnyFixedBufferSize() =
        runTest(timeout = BUDGET * 2) {
            if (keychainUnreachable("aliasesAreListedPastAnyFixedBufferSize")) return@runTest
            val service = "bcks-aliases-${Random.nextInt(Int.MAX_VALUE)}"
            // 300 accounts of 250 characters: ~75 KiB of names, past the 64 KiB a fixed buffer allowed.
            val accounts = (0 until 300).map { i -> "a${i.toString().padStart(4, '0')}" + "x".repeat(245) }
            try {
                val listed =
                    onOwnThreads(1, BUDGET, "alias listing") {
                        accounts.forEach { account -> assertEquals(BCKS_OK, putRecord(service, account)) }
                        // The listing needs only the keychain, so the store is opened without the Enclave probe.
                        AppleKeychainKeyStore(enclaveProvider, service).aliases()
                    }.single()
                assertEquals(accounts.toSet(), listed)
            } finally {
                accounts.forEach { account -> bcks_keychain_delete(service, account) }
            }
        }

    private fun putRecord(
        service: String,
        account: String,
    ): Int =
        memScoped {
            val one = allocArray<UByteVar>(1)
            bcks_keychain_put(service, account, one, 1.convert())
        }
}
