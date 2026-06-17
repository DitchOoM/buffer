package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import kotlin.test.assertFailsWith

/**
 * Reusable contract assertions every crypto family inherits, so the dangerous tests can't be
 * skipped by oversight — the harness, not discipline, enforces them.
 */
object CryptoContract {
    /**
     * The capability contract. Where [supported] is `true` the [op] must run to completion;
     * where `false` it must throw [UnsupportedOperationException]. Driving both branches from
     * one test source keeps the unsupported-platform contract (ChaCha-on-web, Ed25519 below
     * Android API 34) as rigorously tested as the supported path.
     */
    inline fun assertCapability(
        supported: Boolean,
        op: () -> Unit,
    ) {
        if (supported) {
            op()
        } else {
            assertFailsWith<UnsupportedOperationException> { op() }
        }
    }

    /**
     * Tamper contract: flips one bit in every byte position of [authenticated] in turn and
     * asserts [verifyOrOpen] rejects each mutant with [VerificationFailed]. Proves no single-byte
     * corruption of a tag / ciphertext / AAD / signature is ever silently accepted.
     *
     * [verifyOrOpen] receives the mutated copy and must perform the authenticated operation
     * (AEAD open, signature verify). The original [authenticated] buffer is never mutated.
     */
    inline fun assertEveryByteFlipRejected(
        authenticated: ReadBuffer,
        verifyOrOpen: (ReadBuffer) -> Unit,
    ) {
        val start = authenticated.position()
        val n = authenticated.remaining()
        require(n > 0) { "nothing to tamper with" }
        for (i in 0 until n) {
            val mutant = BufferFactory.Default.allocate(n)
            for (j in 0 until n) {
                val b = authenticated.get(start + j)
                mutant.writeByte(if (j == i) (b.toInt() xor 0x01).toByte() else b)
            }
            mutant.resetForRead()
            assertFailsWith<VerificationFailed>("byte $i flip must be rejected") {
                verifyOrOpen(mutant)
            }
        }
    }
}
