package com.ditchoom.buffer

import com.ditchoom.buffer.pool.withPool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the BufferFactory v2 API.
 */
class BufferFactoryTests {
    // ============================================================================
    // Preset Tests
    // ============================================================================

    @Test
    fun defaultFactoryAllocatesBuffer() {
        val buffer = BufferFactory.Default.allocate(64)
        assertEquals(64, buffer.capacity)
        assertEquals(0, buffer.position())
        buffer.writeInt(0x12345678)
        buffer.resetForRead()
        assertEquals(0x12345678, buffer.readInt())
    }

    @Test
    fun defaultFactoryWrapsArray() {
        val array = byteArrayOf(1, 2, 3, 4)
        val buffer = BufferFactory.Default.wrap(array)
        assertEquals(1, buffer.readByte())
        assertEquals(2, buffer.readByte())
        assertEquals(3, buffer.readByte())
        assertEquals(4, buffer.readByte())
    }

    @Test
    fun managedFactoryAllocatesBuffer() {
        val buffer = BufferFactory.managed().allocate(32)
        assertEquals(32, buffer.capacity)
        buffer.writeShort(0x1234.toShort())
        buffer.resetForRead()
        assertEquals(0x1234.toShort(), buffer.readShort())
    }

    @Test
    fun managedFactoryWrapsArray() {
        val array = byteArrayOf(10, 20, 30)
        val buffer = BufferFactory.managed().wrap(array)
        assertEquals(10, buffer.readByte())
        assertEquals(20, buffer.readByte())
        assertEquals(30, buffer.readByte())
    }

    @Test
    fun sharedFactoryAllocatesBuffer() {
        val buffer = BufferFactory.shared().allocate(64)
        assertTrue(buffer.capacity >= 64)
        buffer.writeLong(0x123456789ABCDEF0L)
        buffer.resetForRead()
        assertEquals(0x123456789ABCDEF0L, buffer.readLong())
    }

    // ============================================================================
    // Deterministic Factory Tests
    // ============================================================================

    @Test
    fun deterministicFactoryAllocatesBuffer() {
        val buffer = BufferFactory.Deterministic.allocate(64)
        assertEquals(64, buffer.capacity)
        buffer.writeInt(0x12345678)
        buffer.resetForRead()
        assertEquals(0x12345678, buffer.readInt())
        buffer.freeNativeMemory()
    }

    @Test
    fun deterministicFactoryUsePattern() {
        BufferFactory.Deterministic.allocate(128).use { buffer ->
            buffer.writeLong(0x123456789ABCDEF0L)
            buffer.resetForRead()
            assertEquals(0x123456789ABCDEF0L, buffer.readLong())
        }
    }

    @Test
    fun deterministicFactoryUseWithException() {
        assertFailsWith<RuntimeException> {
            BufferFactory.Deterministic.allocate(64).use { _ ->
                throw RuntimeException("test exception")
            }
        }
    }

    @Test
    fun deterministicFactoryWrapsArray() {
        val array = byteArrayOf(1, 2, 3, 4)
        val buffer = BufferFactory.Deterministic.wrap(array)
        assertEquals(1, buffer.readByte())
        assertEquals(2, buffer.readByte())
    }

    @Test
    fun deterministicFactoryRespectsLittleEndianByteOrder() {
        BufferFactory.Deterministic.allocate(8, ByteOrder.LITTLE_ENDIAN).use { buffer ->
            assertEquals(ByteOrder.LITTLE_ENDIAN, buffer.byteOrder)
            buffer.writeInt(0x12345678)
            buffer.resetForRead()
            assertEquals(0x12345678, buffer.readInt())
        }
    }

    @Test
    fun deterministicFactoryRespectsBigEndianByteOrder() {
        BufferFactory.Deterministic.allocate(8, ByteOrder.BIG_ENDIAN).use { buffer ->
            assertEquals(ByteOrder.BIG_ENDIAN, buffer.byteOrder)
            buffer.writeInt(0x12345678)
            buffer.resetForRead()
            assertEquals(0x12345678, buffer.readInt())
        }
    }

    @Test
    fun negativeSizeAllocationThrows() {
        assertFailsWith<IllegalArgumentException> {
            BufferFactory.Default.allocate(-1)
        }
    }

    @Test
    fun defaultFactoryRespectsLittleEndianByteOrder() {
        val buffer = BufferFactory.Default.allocate(4, ByteOrder.LITTLE_ENDIAN)
        assertEquals(ByteOrder.LITTLE_ENDIAN, buffer.byteOrder)
    }

    // ============================================================================
    // Decorator: requiring<T>()
    // ============================================================================

    @Test
    fun requiringManagedMemoryAccessWithManagedFactory() {
        val factory = BufferFactory.managed().requiring<ManagedMemoryAccess>()
        val buffer = factory.allocate(32)
        val mma = buffer.unwrapFully() as? ManagedMemoryAccess
        assertNotNull(mma, "Managed factory should produce ManagedMemoryAccess buffers")
        assertNotNull(mma.backingArray)
    }

    @Test
    fun requiringThrowsWhenUnsatisfied() {
        // managed() produces ByteArray-backed buffers — they lack NativeMemoryAccess on most platforms
        // but on JS all buffers have NativeMemoryAccess, so we test with a custom capability
        val factory = BufferFactory.managed().requiring<SharedMemoryAccess>()
        // SharedMemoryAccess is not available on managed factory for most platforms
        // This should throw on platforms where managed != shared
        try {
            val buffer = factory.allocate(32)
            // If it doesn't throw, the buffer actually has the capability (e.g., JS)
            val unwrapped = buffer.unwrapFully()
            assertTrue(
                unwrapped is SharedMemoryAccess || buffer is SharedMemoryAccess,
                "If no exception, buffer must actually have the capability",
            )
        } catch (e: UnsupportedOperationException) {
            assertTrue(e.message!!.contains("requiring"))
        }
    }

    @Test
    fun requiringFreesBufferOnFailure() {
        // We can't directly observe the free, but we verify it doesn't throw anything unexpected
        val factory = BufferFactory.managed().requiring<SharedMemoryAccess>()
        try {
            factory.allocate(32)
        } catch (_: UnsupportedOperationException) {
            // Expected — the buffer should have been freed before throwing
        }
    }

    // ============================================================================
    // Decorator: preferring<T>()
    // ============================================================================

    @Test
    fun preferringNeverThrows() {
        val factory = BufferFactory.Default.preferring<ManagedMemoryAccess>()
        val buffer = factory.allocate(32)
        assertNotNull(buffer)
        assertEquals(32, buffer.capacity)
    }

    @Test
    fun preferringWrapDelegatesToUnderlying() {
        val array = byteArrayOf(5, 10, 15)
        val factory = BufferFactory.Default.preferring<NativeMemoryAccess>()
        val buffer = factory.wrap(array)
        assertEquals(5, buffer.readByte())
    }

    // ============================================================================
    // Decorator: withSizeLimit()
    // ============================================================================

    @Test
    fun sizeLimitAllowsWithinLimit() {
        val factory = BufferFactory.Default.withSizeLimit(1024)
        val buffer = factory.allocate(512)
        assertEquals(512, buffer.capacity)
    }

    @Test
    fun sizeLimitThrowsOverLimit() {
        val factory = BufferFactory.Default.withSizeLimit(100)
        assertFailsWith<IllegalArgumentException> {
            factory.allocate(200)
        }
    }

    @Test
    fun sizeLimitAllowsExactLimit() {
        val factory = BufferFactory.Default.withSizeLimit(256)
        val buffer = factory.allocate(256)
        assertEquals(256, buffer.capacity)
    }

    @Test
    fun sizeLimitAppliesToWrap() {
        val factory = BufferFactory.Default.withSizeLimit(10)
        assertFailsWith<IllegalArgumentException> {
            factory.wrap(ByteArray(20))
        }
    }

    // ============================================================================
    // Decorator: withPooling()
    // ============================================================================

    @Test
    fun poolingFactoryAcquiresFromPool() =
        withPool(defaultBufferSize = 128) { pool ->
            val factory = BufferFactory.Default.withPooling(pool)

            // Acquire via factory
            val buffer = factory.allocate(64)
            assertNotNull(buffer)
            assertTrue(buffer.capacity >= 64)

            // Stats should reflect allocation
            val stats = pool.stats()
            assertTrue(stats.totalAllocations >= 1)
        }

    @Test
    fun poolingFactoryWrapDelegatesToUnderlying() =
        withPool { pool ->
            val factory = BufferFactory.Default.withPooling(pool)
            val array = byteArrayOf(1, 2, 3)
            val buffer = factory.wrap(array)
            assertEquals(1, buffer.readByte())
        }

    // ============================================================================
    // Decorator Composition
    // ============================================================================

    @Test
    fun composedDecoratorsWork() {
        val factory =
            BufferFactory.Default
                .withSizeLimit(4096)
                .preferring<NativeMemoryAccess>()
        val buffer = factory.allocate(1024)
        assertNotNull(buffer)
        assertTrue(buffer.capacity >= 1024)

        // Size limit still applies through composition
        assertFailsWith<IllegalArgumentException> {
            factory.allocate(8192)
        }
    }

    @Test
    fun sizeLimitComposedWithPooling(): Unit =
        withPool(defaultBufferSize = 512) { pool ->
            val factory =
                BufferFactory.Default
                    .withPooling(pool)
                    .withSizeLimit(1024)

            val buffer = factory.allocate(512)
            assertNotNull(buffer)

            assertFailsWith<IllegalArgumentException> {
                factory.allocate(2048)
            }
            Unit
        }

    // ============================================================================
    // Buffer.use {} Extension
    // ============================================================================

    @Test
    fun useReturnsBlockValue() {
        val result =
            BufferFactory.Default.allocate(16).use { buffer ->
                buffer.writeInt(42)
                buffer.resetForRead()
                buffer.readInt()
            }
        assertEquals(42, result)
    }

    @Test
    fun useHandlesException() {
        assertFailsWith<RuntimeException> {
            BufferFactory.Default.allocate(16).use {
                throw RuntimeException("test")
            }
        }
    }

    // ============================================================================
    // CloseableBuffer Marker Tests
    // ============================================================================

    @Test
    fun closeableBufferCheckWorks() {
        val buffer = BufferFactory.Default.allocate(32)
        // CloseableBuffer is platform-specific — just verify the type check doesn't crash
        if (buffer is CloseableBuffer) {
            // On platforms with closeable buffers (Linux NativeBuffer, WASM LinearBuffer)
            @Suppress("USELESS_IS_CHECK")
            assertTrue(buffer is PlatformBuffer)
        }
    }

    // ============================================================================
    // BufferFactory produces Buffer type
    // ============================================================================

    @Test
    fun factoryAllocateReturnsBuffer() {
        val buffer: PlatformBuffer = BufferFactory.Default.allocate(16)
        assertIs<PlatformBuffer>(buffer)
    }

    @Test
    fun factoryWrapReturnsBuffer() {
        val buffer: PlatformBuffer = BufferFactory.Default.wrap(byteArrayOf(1, 2))
        assertIs<PlatformBuffer>(buffer)
    }

    @Test
    fun managedFactoryAllocateReturnsBuffer() {
        val buffer: PlatformBuffer = BufferFactory.managed().allocate(16)
        assertIs<PlatformBuffer>(buffer)
    }

    @Test
    fun allPresetsReturnSameCompanionType() {
        // Verify that Default, managed(), and shared() all work through the companion
        val defaultBuf = BufferFactory.Default.allocate(8)
        val managedBuf = BufferFactory.managed().allocate(8)
        val sharedBuf = BufferFactory.shared().allocate(8)

        assertIs<PlatformBuffer>(defaultBuf)
        assertIs<PlatformBuffer>(managedBuf)
        assertIs<PlatformBuffer>(sharedBuf)
    }
}
