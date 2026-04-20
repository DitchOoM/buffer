package com.ditchoom.buffer

import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.withPool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Exhaustive use-after-free audit for PooledBuffer.
 *
 * Every data-access and mutation operation must throw [IllegalStateException] after
 * [PlatformBuffer.freeNativeMemory]. Metadata reads (`position()`, `limit()`, `byteOrder`,
 * `remaining()`, `hasRemaining()`, `capacity`) are intentionally left unchecked for
 * performance — reading stale metadata after free is benign.
 */
class PooledBufferUseAfterFreeTest {
    private inline fun afterFree(block: (PlatformBuffer) -> Unit) =
        withPool(defaultBufferSize = 64) { pool: BufferPool ->
            val buffer = pool.acquire(32)
            // Seed some content so reads have data to attempt
            buffer.writeLong(0x0011_2233_4455_6677L)
            buffer.resetForRead()
            buffer.freeNativeMemory()
            block(buffer)
        }

    // ========================================================================
    // Relative reads
    // ========================================================================

    @Test fun readByteThrows() = afterFree { assertFailsWith<IllegalStateException> { it.readByte() } }

    @Test fun readShortThrows() = afterFree { assertFailsWith<IllegalStateException> { it.readShort() } }

    @Test fun readIntThrows() = afterFree { assertFailsWith<IllegalStateException> { it.readInt() } }

    @Test fun readLongThrows() = afterFree { assertFailsWith<IllegalStateException> { it.readLong() } }

    @Test fun readFloatThrows() = afterFree { assertFailsWith<IllegalStateException> { it.readFloat() } }

    @Test fun readDoubleThrows() = afterFree { assertFailsWith<IllegalStateException> { it.readDouble() } }

    @Test fun readByteArrayThrows() = afterFree { assertFailsWith<IllegalStateException> { it.readByteArray(1) } }

    @Test fun readStringThrows() = afterFree { assertFailsWith<IllegalStateException> { it.readString(1, Charset.UTF8) } }

    @Test fun readLineThrows() = afterFree { assertFailsWith<IllegalStateException> { it.readLine() } }

    // ========================================================================
    // Absolute reads
    // ========================================================================

    @Test fun getThrows() = afterFree { assertFailsWith<IllegalStateException> { it.get(0) } }

    @Test fun getShortThrows() = afterFree { assertFailsWith<IllegalStateException> { it.getShort(0) } }

    @Test fun getIntThrows() = afterFree { assertFailsWith<IllegalStateException> { it.getInt(0) } }

    @Test fun getLongThrows() = afterFree { assertFailsWith<IllegalStateException> { it.getLong(0) } }

    @Test fun getFloatThrows() = afterFree { assertFailsWith<IllegalStateException> { it.getFloat(0) } }

    @Test fun getDoubleThrows() = afterFree { assertFailsWith<IllegalStateException> { it.getDouble(0) } }

    // ========================================================================
    // Relative writes
    // ========================================================================

    @Test fun writeByteThrows() = afterFree { assertFailsWith<IllegalStateException> { it.writeByte(1) } }

    @Test fun writeBytesThrows() = afterFree { assertFailsWith<IllegalStateException> { it.writeBytes(byteArrayOf(1)) } }

    @Test fun writeShortThrows() = afterFree { assertFailsWith<IllegalStateException> { it.writeShort(1) } }

    @Test fun writeIntThrows() = afterFree { assertFailsWith<IllegalStateException> { it.writeInt(1) } }

    @Test fun writeLongThrows() = afterFree { assertFailsWith<IllegalStateException> { it.writeLong(1) } }

    @Test fun writeFloatThrows() = afterFree { assertFailsWith<IllegalStateException> { it.writeFloat(1f) } }

    @Test fun writeDoubleThrows() = afterFree { assertFailsWith<IllegalStateException> { it.writeDouble(1.0) } }

    @Test fun writeStringThrows() = afterFree { assertFailsWith<IllegalStateException> { it.writeString("x") } }

    @Test
    fun writeBufferThrows() {
        val other =
            BufferFactory.Default.allocate(4).apply {
                writeInt(1)
                resetForRead()
            }
        afterFree { assertFailsWith<IllegalStateException> { it.write(other) } }
    }

    // ========================================================================
    // Absolute writes
    // ========================================================================

    @Test fun setByteThrows() = afterFree { assertFailsWith<IllegalStateException> { it.set(0, 1.toByte()) } }

    @Test fun setShortThrows() = afterFree { assertFailsWith<IllegalStateException> { it.set(0, 1.toShort()) } }

    @Test fun setIntThrows() = afterFree { assertFailsWith<IllegalStateException> { it.set(0, 1) } }

    @Test fun setLongThrows() = afterFree { assertFailsWith<IllegalStateException> { it.set(0, 1L) } }

    @Test fun setFloatThrows() = afterFree { assertFailsWith<IllegalStateException> { it.set(0, 1f) } }

    @Test fun setDoubleThrows() = afterFree { assertFailsWith<IllegalStateException> { it.set(0, 1.0) } }

    // ========================================================================
    // Reset / slice
    // ========================================================================

    @Test fun resetForReadThrows() = afterFree { assertFailsWith<IllegalStateException> { it.resetForRead() } }

    @Test fun resetForWriteThrows() = afterFree { assertFailsWith<IllegalStateException> { it.resetForWrite() } }

    @Test fun positionSetThrows() = afterFree { assertFailsWith<IllegalStateException> { it.position(0) } }

    @Test fun setLimitThrows() = afterFree { assertFailsWith<IllegalStateException> { it.setLimit(0) } }

    @Test fun sliceThrows() = afterFree { assertFailsWith<IllegalStateException> { it.slice() } }

    // ========================================================================
    // Search / compare
    // ========================================================================

    @Test
    fun contentEqualsThrows() {
        val other =
            BufferFactory.Default.allocate(4).apply {
                writeInt(1)
                resetForRead()
            }
        afterFree { assertFailsWith<IllegalStateException> { it.contentEquals(other) } }
    }

    @Test
    fun mismatchThrows() {
        val other =
            BufferFactory.Default.allocate(4).apply {
                writeInt(1)
                resetForRead()
            }
        afterFree { assertFailsWith<IllegalStateException> { it.mismatch(other) } }
    }

    @Test fun indexOfByteThrows() = afterFree { assertFailsWith<IllegalStateException> { it.indexOf(1.toByte()) } }

    @Test fun indexOfShortThrows() = afterFree { assertFailsWith<IllegalStateException> { it.indexOf(1.toShort()) } }

    @Test fun indexOfIntThrows() = afterFree { assertFailsWith<IllegalStateException> { it.indexOf(1) } }

    @Test fun indexOfLongThrows() = afterFree { assertFailsWith<IllegalStateException> { it.indexOf(1L) } }

    // ========================================================================
    // Fill / mask
    // ========================================================================

    @Test fun fillThrows() = afterFree { assertFailsWith<IllegalStateException> { it.fill(0.toByte()) } }

    @Test fun xorMaskThrows() = afterFree { assertFailsWith<IllegalStateException> { it.xorMask(0x12345678) } }

    @Test
    fun xorMaskCopyThrows() {
        val src =
            BufferFactory.Default.allocate(4).apply {
                writeInt(1)
                resetForRead()
            }
        afterFree { assertFailsWith<IllegalStateException> { it.xorMaskCopy(src, 0x12345678) } }
    }

    // ========================================================================
    // Data extraction (guarded via unwrapFully)
    // ========================================================================

    @Test fun toByteArrayThrows() = afterFree { assertFailsWith<IllegalStateException> { it.toByteArray() } }

    @Test fun toNativeDataThrows() = afterFree { assertFailsWith<IllegalStateException> { it.toNativeData() } }

    @Test fun toMutableNativeDataThrows() = afterFree { assertFailsWith<IllegalStateException> { it.toMutableNativeData() } }

    @Test fun unwrapFullyThrows() = afterFree { assertFailsWith<IllegalStateException> { (it as ReadBuffer).unwrapFully() } }

    @Test
    fun memoryAccessExtensionsThrow() =
        afterFree {
            assertFailsWith<IllegalStateException> { it.nativeMemoryAccess }
            assertFailsWith<IllegalStateException> { it.managedMemoryAccess }
            assertFailsWith<IllegalStateException> { it.sharedMemoryAccess }
        }

    // ========================================================================
    // Metadata — intentionally NOT guarded (performance)
    // ========================================================================

    @Test
    fun metadataReadsDoNotThrow() =
        afterFree { buffer ->
            // These must not throw — we chose to skip the freed check on metadata for performance.
            buffer.position()
            buffer.limit()
            buffer.byteOrder
            buffer.remaining()
            buffer.hasRemaining()
            @Suppress("UNUSED_EXPRESSION")
            buffer.capacity
        }

    @Test
    fun isFreedIsTrueAfterFree() =
        afterFree { buffer ->
            buffer as CloseableBuffer
            assertTrue(buffer.isFreed)
        }

    @Test
    fun freeIsIdempotent() =
        withPool(defaultBufferSize = 64) { pool ->
            val buffer = pool.acquire(32)
            buffer.freeNativeMemory()
            buffer.freeNativeMemory() // second call is a no-op
            assertEquals(true, (buffer as CloseableBuffer).isFreed)
        }
}
