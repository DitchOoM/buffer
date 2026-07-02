package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.managed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Byte-asserts [HpkeContext.close]'s zeroization contract: the derived AEAD `key`, `base_nonce`,
 * and `exporter_secret` must all be wiped by close. In production these come from [secureScratch]
 * (deterministic backing — freed memory is unreadable), so the wipe cannot be observed directly;
 * the [keySchedule] output-factory seam injects a managed secure factory instead, whose free is a
 * GC no-op after the wipe, keeping the zeroed bytes readable — the [AeadKeyCloseTest] technique.
 */
class HpkeContextCloseWipeTest {
    private val secureManaged = BufferFactory.managed().secure()

    private val suite = HpkeSuite(HpkeKem.DhkemX25519HkdfSha256, HpkeKdf.HkdfSha256, HpkeAead.Aes128Gcm)

    /** Runs the key schedule on a fixed 32-byte shared secret with managed-secure outputs. */
    private fun scheduleWithReadableOutputs(): ScheduleOutput {
        val sharedSecretBytes = 32
        val shared = secureManaged.allocate(sharedSecretBytes)
        repeat(sharedSecretBytes) { shared.writeByte(0x42) }
        shared.resetForRead()
        return keySchedule(suite, HpkeMode.Base, shared, EMPTY, psk = null, outputFactory = secureManaged)
    }

    private fun assertAllZero(
        buffer: ReadBuffer,
        name: String,
    ) {
        for (i in 0 until buffer.limit()) {
            assertEquals(0, buffer.get(i).toInt(), "$name byte $i not wiped after close")
        }
    }

    private fun anyNonZero(buffer: ReadBuffer): Boolean {
        for (i in 0 until buffer.limit()) if (buffer.get(i).toInt() != 0) return true
        return false
    }

    @Test
    fun closeWipesKeyBaseNonceAndExporterSecret() {
        val out = scheduleWithReadableOutputs()
        val captured: List<Pair<String, PlatformBuffer>> =
            listOf("key" to out.key, "baseNonce" to out.baseNonce, "exporterSecret" to out.exporterSecret)
        val ctx = HpkeContext.Sender(suite, out.key, out.baseNonce, out.exporterSecret)
        // The schedule outputs are real KDF output — non-zero before close.
        for ((name, buffer) in captured) {
            assertTrue(anyNonZero(buffer), "$name should be non-zero before close")
        }
        ctx.close()
        for ((name, buffer) in captured) assertAllZero(buffer, name)
    }

    @Test
    fun closeIsIdempotentAndKeepsSecretsWiped() {
        val out = scheduleWithReadableOutputs()
        val ctx = HpkeContext.Receiver(suite, out.key, out.baseNonce, out.exporterSecret)
        ctx.close()
        ctx.close() // second close must not throw (and must not double-free)
        assertAllZero(out.key, "key")
        assertAllZero(out.baseNonce, "baseNonce")
        assertAllZero(out.exporterSecret, "exporterSecret")
    }
}
