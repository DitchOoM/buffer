package com.ditchoom.buffer.ktor

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.managed
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

/** Counts allocations so a mid-transfer cancellation can be shown to allocate nothing. */
private class CountingFactory(
    private val delegate: BufferFactory,
) : BufferFactory by delegate {
    var allocations = 0
        private set

    override fun allocate(
        size: Int,
        byteOrder: ByteOrder,
    ): PlatformBuffer {
        allocations++
        return delegate.allocate(size, byteOrder)
    }
}

/**
 * Test group D (Ktor side): a channel read cancelled mid-transfer must not leave an allocated
 * (pool) buffer dangling. [readRemainingBuffer] allocates from the factory only after the channel
 * read completes, so a cancellation while the read is still pending allocates nothing.
 */
class ChannelLeakTest {
    @Test
    fun cancelledReadRemaining_allocatesNothing() =
        runTest {
            val factory = CountingFactory(BufferFactory.managed())
            val channel = ByteChannel(autoFlush = true)
            // Partial data with the channel left open, so readRemaining() suspends awaiting close.
            channel.writeByteArray(bytesOf(16))

            val job = launch { channel.readRemainingBuffer(factory) }
            yield() // let the read start and suspend
            job.cancel()

            assertEquals(0, factory.allocations, "cancelled mid-transfer read must not allocate a buffer")
        }
}
