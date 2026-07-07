package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import com.ditchoom.buffer.crypto.CryptoTestVectors.repeatedByte
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Use-after-close guard on the HPKE PSK wrapper. A closed [HpkePsk]'s secret is already wiped
 * (or, on deterministic backings, freed) — the key schedule must fail fast rather than derive a
 * context from that memory. The context-level close guards (seal/open/export after close) are
 * pinned by [HpkeRoundTripTest.closedContextRejectsEveryOp].
 */
class HpkeCloseGuardTest {
    private val candidateSuites =
        listOf(
            HpkeSuite(HpkeKem.DhkemX25519HkdfSha256, HpkeKdf.HkdfSha256, HpkeAead.Aes128Gcm),
            HpkeSuite(HpkeKem.DhkemP256HkdfSha256, HpkeKdf.HkdfSha256, HpkeAead.Aes128Gcm),
        )

    private fun firstSupported(): HpkeSuite? = candidateSuites.firstOrNull { HpkeTestSupport.suiteSupported(it) }

    @Test
    fun closedPskIsRejectedAtSetup() =
        runTest {
            val suite = firstSupported() ?: return@runTest
            val recipient = hpkeGenerateKeyPair(suite.kem)
            val psk = HpkePsk.of(repeatedByte(0x42, 32), ascii("psk-id"))
            psk.close()
            psk.close() // idempotent
            assertFailsWith<IllegalStateException> {
                hpkeSetupPskSender(suite, recipient.publicKey, ascii("info"), psk)
            }
            recipient.close()
        }
}
