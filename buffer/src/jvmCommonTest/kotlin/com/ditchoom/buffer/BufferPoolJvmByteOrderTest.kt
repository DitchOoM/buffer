package com.ditchoom.buffer

import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.PooledBuffer
import com.ditchoom.buffer.pool.ThreadingMode
import kotlin.test.Test
import kotlin.test.assertEquals

/** On JVM a holder of the platform buffer can change the live `ByteBuffer` order; the pool undoes it. */
class BufferPoolJvmByteOrderTest {
    @Test
    fun aHolderThatChangedTheUnderlyingOrderDoesNotLeakItIntoTheNextAcquire() {
        for (mode in ThreadingMode.entries) {
            val pool = BufferPool(threadingMode = mode, defaultBufferSize = 64, factory = BufferFactory.managed())
            val first = pool.acquire(64) as PooledBuffer
            // What a consumer holding the platform buffer can do to write a native-order value.
            (first.inner as BaseJvmBuffer).byteBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            first.freeNativeMemory()

            val next = pool.acquire(64) as PlatformBuffer
            assertEquals(1L, pool.stats().poolHits, "$mode")
            next.writeInt(ONE_TWO_THREE_FOUR)
            next.resetForRead()
            assertEquals(BIG_ENDIAN_BYTES, List(BIG_ENDIAN_BYTES.size) { next.readByte() }, "$mode")
            next.freeNativeMemory()
            pool.clear()
        }
    }

    private companion object {
        const val ONE_TWO_THREE_FOUR = 0x01020304
        val BIG_ENDIAN_BYTES: List<Byte> = listOf(0x01, 0x02, 0x03, 0x04)
    }
}
