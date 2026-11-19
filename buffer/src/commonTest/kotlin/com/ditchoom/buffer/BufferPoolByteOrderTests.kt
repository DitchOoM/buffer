package com.ditchoom.buffer

import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import kotlin.test.Test
import kotlin.test.assertEquals

/** A pooled buffer is always in the pool's declared byte order, whatever order its previous user used. */
class BufferPoolByteOrderTests {
    private val modes = ThreadingMode.entries

    @Test
    fun littleEndianBufferReleasedToThePoolIsHandedOutBigEndian() {
        for (mode in modes) {
            val pool = BufferPool(threadingMode = mode, defaultBufferSize = 64)
            val littleEndian = pool.allocate(64, ByteOrder.LITTLE_ENDIAN)
            assertEquals(ByteOrder.LITTLE_ENDIAN, littleEndian.byteOrder)
            pool.release(littleEndian)

            val next = pool.acquire(64) as PlatformBuffer
            assertEquals(ByteOrder.BIG_ENDIAN, next.byteOrder, "$mode")
            next.writeInt(0x01020304)
            next.writeLong(0x0102030405060708L)
            next.resetForRead()
            assertEquals(listOf<Byte>(1, 2, 3, 4), List(4) { next.readByte() }, "$mode")
            assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6, 7, 8), List(8) { next.readByte() }, "$mode")
            next.freeNativeMemory()
            pool.clear()
        }
    }

    @Test
    fun allocateWithTheDeclaredOrderStillReusesPooledBuffers() {
        for (mode in modes) {
            val pool = BufferPool(threadingMode = mode, defaultBufferSize = 64)
            (pool.acquire(64) as PlatformBuffer).freeNativeMemory()
            val reused = pool.allocate(64, ByteOrder.BIG_ENDIAN)
            assertEquals(1L, pool.stats().poolHits, "$mode")
            assertEquals(ByteOrder.BIG_ENDIAN, reused.byteOrder)
            reused.freeNativeMemory()
            pool.clear()
        }
    }

    @Test
    fun poolDeclaredLittleEndianStaysLittleEndianAcrossReuse() {
        val littleEndianFactory =
            object : BufferFactory {
                override fun allocate(
                    size: Int,
                    byteOrder: ByteOrder,
                ) = BufferFactory.Default.allocate(size, ByteOrder.LITTLE_ENDIAN)

                override fun wrap(
                    array: ByteArray,
                    byteOrder: ByteOrder,
                ) = BufferFactory.Default.wrap(array, ByteOrder.LITTLE_ENDIAN)
            }
        for (mode in modes) {
            val pool = BufferPool(threadingMode = mode, defaultBufferSize = 64, factory = littleEndianFactory)
            (pool.acquire(64) as PlatformBuffer).freeNativeMemory()
            val again = pool.acquire(64) as PlatformBuffer
            assertEquals(1L, pool.stats().poolHits, "$mode")
            assertEquals(ByteOrder.LITTLE_ENDIAN, again.byteOrder)
            again.freeNativeMemory()
            pool.clear()
        }
    }
}
