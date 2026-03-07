package com.ditchoom.buffer

import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.withBuffer
import com.ditchoom.buffer.pool.withPool
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for FfmBuffer — FFM Arena-backed PlatformBuffer on JVM 21+.
 *
 * FfmBuffer is in the jvm21Main compilation, added to jvmTest compile classpath
 * so we can reference it directly. These tests verify FfmBuffer behavior independently.
 *
 * The transparent upgrade (BufferFactory routing allocate → FfmBuffer) is tested
 * by the jvmFfmTest Gradle task, which runs ALL common tests with java21 classes first
 * on the classpath, making BufferFactory.Default.allocate() return FfmBuffer.
 */
class FfmBufferTest {
    private fun createFfmBuffer(
        size: Int,
        byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
    ): FfmBuffer {
        val arena = Arena.ofShared()
        val segment = arena.allocate(size.toLong())
        val javaByteOrder = byteOrder.toJava()
        // Match the factory: global-scope ByteBuffer for JDK API compatibility
        val globalView =
            MemorySegment
                .ofAddress(segment.address())
                .reinterpret(size.toLong())
        val byteBuffer = globalView.asByteBuffer().order(javaByteOrder)
        return FfmBuffer(arena, segment, byteBuffer)
    }

    // ============================================================================
    // Type & Interface Tests
    // ============================================================================

    @Test
    fun `FfmBuffer is a PlatformBuffer`() =
        createFfmBuffer(64).use { buffer ->
            assertIs<PlatformBuffer>(buffer)
            Unit
        }

    @Test
    fun `FfmBuffer implements NativeMemoryAccess`() {
        val buffer = createFfmBuffer(64)
        try {
            assertIs<NativeMemoryAccess>(buffer)
            assertNotNull((buffer as ReadBuffer).nativeMemoryAccess)
        } finally {
            buffer.freeNativeMemory()
        }
    }

    @Test
    fun `FfmBuffer implements CloseableBuffer`() {
        val buffer = createFfmBuffer(64)
        try {
            assertIs<CloseableBuffer>(buffer)
        } finally {
            buffer.freeNativeMemory()
        }
    }

    @Test
    fun `nativeAddress returns valid non-zero address`() {
        val buffer = createFfmBuffer(1024)
        try {
            assertTrue(buffer.nativeAddress != 0L)
        } finally {
            buffer.freeNativeMemory()
        }
    }

    @Test
    fun `nativeSize matches allocated size`() {
        val buffer = createFfmBuffer(1024)
        try {
            assertEquals(1024L, buffer.nativeSize)
        } finally {
            buffer.freeNativeMemory()
        }
    }

    // ============================================================================
    // Deterministic Memory Management Tests
    // ============================================================================

    @Test
    fun `freeNativeMemory closes Arena`() {
        val buffer = createFfmBuffer(1024)
        assertTrue(buffer.segment.scope().isAlive)
        buffer.freeNativeMemory()
        assertFalse(buffer.segment.scope().isAlive)
    }

    @Test
    fun `freeNativeMemory is idempotent`() {
        val buffer = createFfmBuffer(1024)
        buffer.freeNativeMemory()
        buffer.freeNativeMemory() // Should not throw
        assertFalse(buffer.segment.scope().isAlive)
    }

    @Test
    fun `isFreed reflects Arena state`() {
        val buffer = createFfmBuffer(64)
        assertFalse(buffer.isFreed)
        buffer.freeNativeMemory()
        assertTrue(buffer.isFreed)
    }

    @Test
    fun `arena is not alive after free`() {
        val buffer = createFfmBuffer(1024)
        buffer.freeNativeMemory()
        assertFalse(buffer.segment.scope().isAlive)
    }

    @Test
    fun `use block frees memory`() {
        val buffer = createFfmBuffer(64)
        val segment = buffer.segment
        buffer.use {
            it.writeInt(42)
            it.resetForRead()
            assertEquals(42, it.readInt())
        }
        assertFalse(segment.scope().isAlive)
    }

    @Test
    fun `readInt after free throws`() {
        val buffer = createFfmBuffer(64)
        buffer.writeInt(42)
        buffer.resetForRead()
        buffer.freeNativeMemory()
        assertFailsWith<java.nio.BufferUnderflowException> { buffer.readInt() }
    }

    @Test
    fun `writeInt after free throws`() {
        val buffer = createFfmBuffer(64)
        buffer.freeNativeMemory()
        assertFailsWith<java.nio.BufferOverflowException> { buffer.writeInt(42) }
    }

    @Test
    fun `resetForRead after free throws with assertions`() {
        val buffer = createFfmBuffer(64)
        buffer.writeInt(42)
        buffer.freeNativeMemory()
        assertFailsWith<AssertionError> { buffer.resetForRead() }
    }

    @Test
    fun `resetForWrite after free throws with assertions`() {
        val buffer = createFfmBuffer(64)
        buffer.freeNativeMemory()
        assertFailsWith<AssertionError> { buffer.resetForWrite() }
    }

    @Test
    fun `nativeAddress after free throws with assertions`() {
        val buffer = createFfmBuffer(64)
        buffer.freeNativeMemory()
        assertFailsWith<AssertionError> { buffer.nativeAddress }
    }

    @Test
    fun `absolute get after free throws`() {
        val buffer = createFfmBuffer(64)
        buffer.writeInt(42)
        buffer.freeNativeMemory()
        assertFailsWith<IndexOutOfBoundsException> { buffer.get(0) }
    }

    @Test
    fun `use block frees memory on exception`() {
        val buffer = createFfmBuffer(64)
        val segment = buffer.segment
        assertFailsWith<RuntimeException> {
            buffer.use {
                it.writeInt(42)
                throw RuntimeException("test error")
            }
        }
        assertFalse(segment.scope().isAlive, "Buffer should be freed even after exception")
    }

    @Test
    fun `use block tolerates freeNativeMemory inside block`() {
        val buffer = createFfmBuffer(64)
        val segment = buffer.segment
        buffer.use {
            it.writeInt(42)
            it.freeNativeMemory() // explicit free inside use
        } // use calls freeNativeMemory again — idempotent
        assertFalse(segment.scope().isAlive)
    }

    @Test
    fun `slice after parent free throws IllegalStateException`() {
        val buffer = createFfmBuffer(64)
        buffer.writeInt(0x12345678)
        buffer.resetForRead()
        val slice = buffer.slice()
        buffer.freeNativeMemory()
        assertIs<FfmSliceBuffer>(slice)
        assertFailsWith<IllegalStateException> { slice.readInt() }
    }

    @Test
    fun `slice of slice throws after parent free`() {
        val buffer = createFfmBuffer(64)
        buffer.writeLong(0x123456789ABCDEF0L)
        buffer.resetForRead()
        val slice1 = buffer.slice()
        val slice2 = slice1.slice()
        buffer.freeNativeMemory()
        assertFailsWith<IllegalStateException> { slice1.readLong() }
        assertFailsWith<IllegalStateException> { slice2.readLong() }
    }

    @Test
    fun `FfmSliceBuffer nativeAddress throws after parent free`() {
        val buffer = createFfmBuffer(64)
        buffer.writeInt(42)
        buffer.resetForRead()
        val slice = buffer.slice() as FfmSliceBuffer
        buffer.freeNativeMemory()
        assertFailsWith<IllegalStateException> { slice.nativeAddress }
    }

    @Test
    fun `FfmSliceBuffer nativeSize throws after parent free`() {
        val buffer = createFfmBuffer(64)
        buffer.writeInt(42)
        buffer.resetForRead()
        val slice = buffer.slice() as FfmSliceBuffer
        buffer.freeNativeMemory()
        assertFailsWith<IllegalStateException> { slice.nativeSize }
    }

    @Test
    fun `slice write after parent free throws IllegalStateException`() {
        val buffer = createFfmBuffer(64)
        val slice = buffer.slice()
        buffer.freeNativeMemory()
        assertFailsWith<IllegalStateException> { slice.writeInt(42) }
    }

    @Test
    fun `slice created from dead FfmSliceBuffer cannot read`() {
        val buffer = createFfmBuffer(64)
        buffer.writeInt(0x12345678)
        buffer.resetForRead()
        val slice = buffer.slice()
        buffer.freeNativeMemory()
        val subSlice = slice.slice()
        assertFailsWith<IllegalStateException> { subSlice.readInt() }
    }

    @Test
    fun `FfmSliceBuffer implements NativeMemoryAccess`() =
        createFfmBuffer(64).use { buffer ->
            buffer.writeInt(0x12345678)
            buffer.resetForRead()
            val slice = buffer.slice()
            assertIs<NativeMemoryAccess>(slice)
            assertNotNull((slice as ReadBuffer).nativeMemoryAccess)
            Unit
        }

    @Test
    fun `slice preserves little-endian byte order`() =
        createFfmBuffer(64, ByteOrder.LITTLE_ENDIAN).use { buffer ->
            buffer.writeInt(0x01020304)
            buffer.resetForRead()
            val slice = buffer.slice()
            assertEquals(0x01020304, slice.readInt())
            slice.position(0)
            assertEquals(0x04.toByte(), slice.readByte())
        }

    @Test
    fun `multiple independent slices all invalidated after parent free`() {
        val buffer = createFfmBuffer(64)
        repeat(16) { buffer.writeInt(it) }
        buffer.resetForRead()
        val slices = (0 until 4).map { buffer.slice() }
        buffer.freeNativeMemory()
        slices.forEach { slice ->
            assertFailsWith<IllegalStateException> { slice.readInt() }
        }
    }

    // ============================================================================
    // Buffer Operations Tests
    // ============================================================================

    @Test
    fun `basic read write operations work`() =
        createFfmBuffer(64).use { buffer ->
            buffer.writeInt(0x12345678)
            buffer.writeLong(0x123456789ABCDEF0L)
            buffer.writeShort(0x1234.toShort())
            buffer.writeByte(0x42)
            buffer.resetForRead()
            assertEquals(0x12345678, buffer.readInt())
            assertEquals(0x123456789ABCDEF0L, buffer.readLong())
            assertEquals(0x1234.toShort(), buffer.readShort())
            assertEquals(0x42.toByte(), buffer.readByte())
        }

    @Test
    fun `string operations work`() =
        createFfmBuffer(256).use { buffer ->
            val text = "Hello, FFM Buffer!"
            buffer.writeString(text, Charset.UTF8)
            buffer.resetForRead()
            val result = buffer.readString(buffer.remaining(), Charset.UTF8)
            assertEquals(text, result)
        }

    @Test
    fun `byte order is respected`() {
        createFfmBuffer(8, ByteOrder.BIG_ENDIAN).use { bufferBE ->
            createFfmBuffer(8, ByteOrder.LITTLE_ENDIAN).use { bufferLE ->
                bufferBE.writeInt(0x01020304)
                bufferLE.writeInt(0x01020304)
                bufferBE.resetForRead()
                bufferLE.resetForRead()
                val beByte = bufferBE.readByte()
                bufferLE.position(0)
                val leByte = bufferLE.readByte()
                assertNotEquals(beByte, leByte, "Byte order should affect raw byte layout")
            }
        }
    }

    // ============================================================================
    // Slice Tests
    // ============================================================================

    @Test
    fun `slice returns FfmSliceBuffer not FfmBuffer`() =
        createFfmBuffer(64).use { buffer ->
            buffer.writeInt(0x12345678)
            buffer.resetForRead()
            val slice = buffer.slice()
            assertIs<FfmSliceBuffer>(slice)
            Unit
        }

    @Test
    fun `slice does not close parent Arena`() {
        val buffer = createFfmBuffer(64)
        try {
            buffer.writeInt(0x12345678)
            buffer.resetForRead()
            val slice = buffer.slice()
            slice.freeNativeMemory() // no-op on FfmSliceBuffer
            assertTrue(buffer.segment.scope().isAlive, "Parent Arena should still be alive")
            buffer.position(0)
            assertEquals(0x12345678, buffer.readInt())
        } finally {
            buffer.freeNativeMemory()
        }
    }

    @Test
    fun `slice shares parent data`() =
        createFfmBuffer(64).use { buffer ->
            buffer.writeInt(0x12345678)
            buffer.resetForRead()
            val slice = buffer.slice()
            assertEquals(0x12345678, slice.readInt())
        }

    // ============================================================================
    // NativeData Conversion Tests
    // ============================================================================

    @Test
    fun `toNativeData zero-copy returns direct ByteBuffer`() =
        createFfmBuffer(64).use { buffer ->
            buffer.writeInt(0x12345678)
            buffer.resetForRead()
            val nativeData = buffer.toNativeData()
            assertTrue(nativeData.byteBuffer.isDirect)
            assertEquals(0x12345678, nativeData.byteBuffer.getInt())
        }

    @Test
    fun `toByteArray copies correctly`() =
        createFfmBuffer(4).use { buffer ->
            buffer.writeByte(1)
            buffer.writeByte(2)
            buffer.writeByte(3)
            buffer.writeByte(4)
            buffer.resetForRead()
            val array = buffer.toByteArray()
            assertEquals(4, array.size)
            assertEquals(1.toByte(), array[0])
            assertEquals(4.toByte(), array[3])
        }

    // ============================================================================
    // Pool Integration Tests
    // ============================================================================

    @Test
    fun `pool clear frees FfmBuffer memory`() {
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 4)
        val buffers = (1..4).map { pool.acquire(512) }
        buffers.forEach { pool.release(it) }
        pool.clear()
    }

    @Test
    fun `pool overflow frees FfmBuffer memory`() =
        withPool(defaultBufferSize = 1024, maxPoolSize = 2) { pool ->
            val buffers = (1..5).map { pool.acquire(512) }
            buffers.forEach { pool.release(it) }
            assertTrue(pool.stats().currentPoolSize <= 2)
        }

    @Test
    fun `pool withBuffer works`() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(512) { buffer ->
                buffer.writeInt(42)
                buffer.resetForRead()
                assertEquals(42, buffer.readInt())
            }
        }

    // ============================================================================
    // Cross-Buffer Interop Tests
    // ============================================================================

    @Test
    fun `write FfmBuffer to HeapJvmBuffer`() =
        createFfmBuffer(64).use { ffm ->
            ffm.writeInt(0x12345678)
            ffm.writeLong(0x1234567890ABCDEFL)
            ffm.resetForRead()

            val heap = BufferFactory.managed().allocate(64)
            heap.write(ffm)
            heap.resetForRead()

            assertEquals(0x12345678, heap.readInt())
            assertEquals(0x1234567890ABCDEFL, heap.readLong())
        }

    @Test
    fun `write HeapJvmBuffer to FfmBuffer`() =
        createFfmBuffer(64).use { ffm ->
            val heap = BufferFactory.managed().allocate(64)
            heap.writeInt(0x12345678)
            heap.resetForRead()

            ffm.write(heap)
            ffm.resetForRead()

            assertEquals(0x12345678, ffm.readInt())
        }

    @Test
    fun `write FfmBuffer to DirectJvmBuffer`() =
        createFfmBuffer(64).use { ffm ->
            ffm.writeInt(0x12345678)
            ffm.resetForRead()

            val direct = DirectJvmBuffer(java.nio.ByteBuffer.allocateDirect(64))
            direct.write(ffm)
            direct.resetForRead()

            assertEquals(0x12345678, direct.readInt())
        }

    @Test
    fun `contentEquals between FfmBuffer and HeapJvmBuffer`() =
        createFfmBuffer(64).use { ffm ->
            val heap = BufferFactory.managed().allocate(64)
            repeat(64) {
                ffm.writeByte(it.toByte())
                heap.writeByte(it.toByte())
            }
            ffm.resetForRead()
            heap.resetForRead()
            assertTrue(ffm.contentEquals(heap))
        }

    // ============================================================================
    // FFM Interop Extension Tests
    // ============================================================================

    @Test
    fun `asMemorySegment returns valid segment for FfmBuffer`() =
        createFfmBuffer(64).use { buffer ->
            buffer.writeInt(0x12345678)
            buffer.resetForRead()

            val segment = buffer.asMemorySegment()
            assertNotNull(segment)
            assertEquals(buffer.remaining().toLong(), segment.byteSize())
        }

    @Test
    fun `asMemorySegment returns null for heap buffer`() {
        val buffer = BufferFactory.managed().allocate(64)
        val segment = buffer.asMemorySegment()
        assertEquals(null, segment)
    }

    @Test
    fun `asMemorySegment returns valid segment for DirectJvmBuffer`() {
        val buffer = DirectJvmBuffer(java.nio.ByteBuffer.allocateDirect(64))
        buffer.writeInt(0x12345678)
        buffer.resetForRead()

        val segment = buffer.asMemorySegment()
        assertNotNull(segment)
        assertEquals(buffer.remaining().toLong(), segment.byteSize())
    }

    @Test
    fun `asMemorySegment returns valid segment for FfmSliceBuffer`() =
        createFfmBuffer(64).use { buffer ->
            buffer.writeInt(0x12345678)
            buffer.writeLong(0x123456789ABCDEF0L)
            buffer.resetForRead()
            val slice = buffer.slice()
            val segment = slice.asMemorySegment()
            assertNotNull(segment)
            assertEquals(slice.remaining().toLong(), segment.byteSize())
        }

    @Test
    fun `asMemorySegment reflects position and limit`() =
        createFfmBuffer(64).use { buffer ->
            repeat(64) { buffer.writeByte(it.toByte()) }
            buffer.resetForRead()
            buffer.position(10)
            buffer.setLimit(50)

            val segment = buffer.asMemorySegment()
            assertNotNull(segment)
            assertEquals(40L, segment.byteSize())
        }
}
