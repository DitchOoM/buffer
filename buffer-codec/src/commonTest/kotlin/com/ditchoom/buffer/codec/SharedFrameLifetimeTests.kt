package com.ditchoom.buffer.codec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ExperimentalFanoutApi
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.pool.SharedBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [SharedFrame.close] is documented as exactly-once and deliberately non-idempotent. Delegating
 * straight to [SharedBytes.release] did not make that true: `release` only throws once the count
 * is already at *zero*, so with consumer references still outstanding a double close silently
 * consumed **someone else's** reference instead — freeing the storage one release early and
 * surfacing as another connection's bytes on the wire, far from the buggy call site.
 */
@OptIn(ExperimentalFanoutApi::class)
class SharedFrameLifetimeTests {
    private fun payload(): PlatformBuffer =
        BufferFactory.Default.allocate(PAYLOAD_SIZE).apply {
            repeat(PAYLOAD_SIZE) { writeByte(it.toByte()) }
            resetForRead()
        }

    @Test
    fun aSecondCloseThrowsInsteadOfStealingAConsumersReference() {
        val bytes = SharedBytes.adopt(payload())
        val frame = SharedFrame("message", bytes)

        // One connection has taken the frame: creator + consumer = 2 references.
        bytes.retain()

        frame.close() // the creator's, leaving the consumer's alone

        assertFailsWith<IllegalStateException>("the second close must be diagnosed, not absorbed") {
            frame.close()
        }

        // The consumer's reference is intact and is the one that frees the storage.
        bytes.release()
        assertFailsWith<IllegalStateException>("storage should now be at zero") { bytes.release() }
    }

    @Test
    fun theCreatorsCloseStillReleasesExactlyOneReference() {
        val bytes = SharedBytes.adopt(payload())
        val frame = SharedFrame("message", bytes)
        assertEquals("message", frame.origin)

        frame.close()

        assertFailsWith<IllegalStateException>("close must have consumed the creator's reference") {
            bytes.release()
        }
    }

    private companion object {
        const val PAYLOAD_SIZE = 16
    }
}
