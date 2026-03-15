package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * JVM-specific tests for deterministic buffer types.
 */
class DeterministicBufferTest {
    @Test
    fun deterministicFactoryAllocatesCloseableBuffer() {
        val buffer = BufferFactory.Deterministic.allocate(64)
        assertIs<CloseableBuffer>(buffer)
        assertIs<NativeMemoryAccess>(buffer)
        buffer.freeNativeMemory()
    }

    @Test
    fun deterministicBufferUseLifecycle() {
        var freed = false
        BufferFactory.Deterministic.allocate(128).use { buffer ->
            buffer.writeInt(42)
            buffer.resetForRead()
            assertEquals(42, buffer.readInt())
            freed = false
        }
        // After use{}, the buffer should be freed
        // (we can't easily check freed flag from outside, but we can verify no crash)
    }

    @Test
    fun deterministicBufferUseWithException() {
        assertFailsWith<RuntimeException> {
            BufferFactory.Deterministic.allocate(64).use { _ ->
                throw RuntimeException("test")
            }
        }
        // Buffer should still be freed even on exception
    }

    @Test
    fun deterministicBufferRoundTripAllPrimitives() {
        BufferFactory.Deterministic.allocate(64).use { buffer ->
            buffer.writeByte(0x42)
            buffer.writeShort(0x1234.toShort())
            buffer.writeInt(0x12345678)
            buffer.writeLong(0x123456789ABCDEF0L)

            buffer.resetForRead()

            assertEquals(0x42.toByte(), buffer.readByte())
            assertEquals(0x1234.toShort(), buffer.readShort())
            assertEquals(0x12345678, buffer.readInt())
            assertEquals(0x123456789ABCDEF0L, buffer.readLong())
        }
    }

    @Test
    fun deterministicBufferBulkOperations() {
        BufferFactory.Deterministic.allocate(256).use { buffer ->
            val data = ByteArray(100) { it.toByte() }
            buffer.writeBytes(data)
            buffer.resetForRead()
            val result = buffer.readByteArray(100)
            assertTrue(data.contentEquals(result))
        }
    }

    @Test
    fun deterministicBufferStringOperations() {
        BufferFactory.Deterministic.allocate(256).use { buffer ->
            buffer.writeString("Hello, World!", Charset.UTF8)
            buffer.resetForRead()
            assertEquals("Hello, World!", buffer.readString(13, Charset.UTF8))
        }
    }

    @Test
    fun deterministicBufferSlice() {
        BufferFactory.Deterministic.allocate(64).use { buffer ->
            buffer.writeInt(0x11111111)
            buffer.writeInt(0x22222222)
            buffer.writeInt(0x33333333)

            buffer.resetForRead()
            buffer.readInt() // skip first int

            val slice = buffer.slice()
            assertEquals(0x22222222, slice.readInt())
            assertEquals(0x33333333, slice.readInt())
        }
    }

    @Test
    fun deterministicBufferNativeAddress() {
        BufferFactory.Deterministic.allocate(64).use { buffer ->
            val nma = buffer as NativeMemoryAccess
            assertTrue(nma.nativeAddress != 0L, "nativeAddress should not be 0")
            assertEquals(64L, nma.nativeSize)
        }
    }

    @Test
    fun deterministicBufferDoubleFreeIsSafe() {
        val buffer = BufferFactory.Deterministic.allocate(64)
        buffer.freeNativeMemory()
        // Second free should be a no-op, not crash
        buffer.freeNativeMemory()
    }

    @Test
    fun deterministicBufferThrowsAfterFree() {
        val buffer = BufferFactory.Deterministic.allocate(64)
        buffer.freeNativeMemory()
        // After free: UnsafePlatformBuffer throws IllegalStateException,
        // FfmBuffer throws BufferUnderflowException (ByteBuffer limit=0)
        val threw =
            try {
                buffer.readByte()
                false
            } catch (_: Exception) {
                true
            }
        assertTrue(threw, "readByte() should throw after freeNativeMemory()")
    }

    @Test
    fun unsafePlatformBufferRoundTrip() {
        val buffer = JvmUnsafePlatformBuffer.allocate(64, ByteOrder.BIG_ENDIAN)
        try {
            buffer.writeByte(0x42)
            buffer.writeShort(0x1234.toShort())
            buffer.writeInt(0x12345678)
            buffer.writeLong(0x123456789ABCDEF0L)

            buffer.resetForRead()

            assertEquals(0x42.toByte(), buffer.readByte())
            assertEquals(0x1234.toShort(), buffer.readShort())
            assertEquals(0x12345678, buffer.readInt())
            assertEquals(0x123456789ABCDEF0L, buffer.readLong())
        } finally {
            buffer.freeNativeMemory()
        }
    }

    @Test
    fun unsafePlatformBufferLittleEndian() {
        val buffer = JvmUnsafePlatformBuffer.allocate(64, ByteOrder.LITTLE_ENDIAN)
        try {
            buffer.writeShort(0x1234.toShort())
            buffer.writeInt(0x12345678)

            buffer.resetForRead()

            assertEquals(0x1234.toShort(), buffer.readShort())
            assertEquals(0x12345678, buffer.readInt())
        } finally {
            buffer.freeNativeMemory()
        }
    }

    @Test
    fun copyBetweenUnsafeAndHeap() {
        val unsafe = JvmUnsafePlatformBuffer.allocate(64, ByteOrder.BIG_ENDIAN)
        try {
            unsafe.writeInt(0xDEADBEEF.toInt())
            unsafe.writeInt(0xCAFEBABE.toInt())
            unsafe.resetForRead()

            val heap = BufferFactory.managed().allocate(64)
            heap.write(unsafe)
            heap.resetForRead()

            assertEquals(0xDEADBEEF.toInt(), heap.readInt())
            assertEquals(0xCAFEBABE.toInt(), heap.readInt())
        } finally {
            unsafe.freeNativeMemory()
        }
    }

    @Test
    fun copyBetweenHeapAndUnsafe() {
        val heap = BufferFactory.managed().allocate(64)
        heap.writeInt(0xDEADBEEF.toInt())
        heap.writeInt(0xCAFEBABE.toInt())
        heap.resetForRead()

        val unsafe = JvmUnsafePlatformBuffer.allocate(64, ByteOrder.BIG_ENDIAN)
        try {
            unsafe.write(heap)
            unsafe.resetForRead()

            assertEquals(0xDEADBEEF.toInt(), unsafe.readInt())
            assertEquals(0xCAFEBABE.toInt(), unsafe.readInt())
        } finally {
            unsafe.freeNativeMemory()
        }
    }

    @Test
    fun copyBetweenUnsafeAndDirect() {
        val unsafe = JvmUnsafePlatformBuffer.allocate(64, ByteOrder.BIG_ENDIAN)
        try {
            unsafe.writeInt(0xDEADBEEF.toInt())
            unsafe.resetForRead()

            val direct = BufferFactory.Default.allocate(64)
            direct.write(unsafe)
            direct.resetForRead()

            assertEquals(0xDEADBEEF.toInt(), direct.readInt())
        } finally {
            unsafe.freeNativeMemory()
        }
    }
}
