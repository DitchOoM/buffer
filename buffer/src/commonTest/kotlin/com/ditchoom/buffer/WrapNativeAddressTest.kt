package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WrapNativeAddressTest {
    // ========== Basic read/write through ==========

    @Test
    fun wrapNativeAddressReadsThroughOriginal() {
        val original = BufferFactory.Default.allocate(64)
        original.writeInt(0x12345678)
        original.writeInt(0xABCDEF00.toInt())
        original.resetForRead()

        val nma = original.nativeMemoryAccess ?: return

        val wrapped = PlatformBuffer.wrapNativeAddress(nma.nativeAddress, 64)
        assertEquals(0x12345678, wrapped.readInt())
        assertEquals(0xABCDEF00.toInt(), wrapped.readInt())
    }

    @Test
    fun wrapNativeAddressWritesThroughToOriginal() {
        val original = BufferFactory.Default.allocate(64)
        val nma = original.nativeMemoryAccess ?: return

        val wrapped = PlatformBuffer.wrapNativeAddress(nma.nativeAddress, 64)
        wrapped.writeInt(42)
        wrapped.writeShort(1000.toShort())

        // Absolute reads — buffers share memory but not position/limit state
        assertEquals(42, original.getInt(0))
        assertEquals(1000.toShort(), original.getShort(4))
    }

    @Test
    fun wrappedBufferHasNativeMemoryAccess() {
        val original = BufferFactory.Default.allocate(32)
        val nma = original.nativeMemoryAccess ?: return

        val wrapped = PlatformBuffer.wrapNativeAddress(nma.nativeAddress, 32)
        val wrappedNma = wrapped.nativeMemoryAccess
        assertNotNull(wrappedNma)
        assertEquals(nma.nativeAddress, wrappedNma.nativeAddress)
    }

    // ========== Size and offset ==========

    @Test
    fun wrappedBufferRespectsSize() {
        val original = BufferFactory.Default.allocate(128)
        val nma = original.nativeMemoryAccess ?: return

        val wrapped = PlatformBuffer.wrapNativeAddress(nma.nativeAddress, 16)
        assertEquals(16, wrapped.capacity)
        assertEquals(16, wrapped.remaining())
    }

    @Test
    fun wrappedBufferRespectsOffset() {
        val original = BufferFactory.Default.allocate(64)
        original.writeInt(0x11111111)
        original.writeInt(0x22222222)
        original.writeInt(0x33333333)
        original.resetForRead()

        val nma = original.nativeMemoryAccess ?: return

        // Wrap starting at offset 4 (second int)
        val wrapped = PlatformBuffer.wrapNativeAddress(nma.nativeAddress + 4, 8)
        assertEquals(0x22222222, wrapped.readInt())
        assertEquals(0x33333333, wrapped.readInt())
    }

    @Test
    fun bufferFactoryConvenienceWorks() {
        val original = BufferFactory.Default.allocate(32)
        val nma = original.nativeMemoryAccess ?: return

        val wrapped = BufferFactory.wrapNativeAddress(nma.nativeAddress, 32)
        assertTrue(wrapped is PlatformBuffer)
        assertEquals(32, wrapped.capacity)
    }

    // ========== Byte order ==========

    @Test
    fun wrappedBufferRespectsBigEndianByteOrder() {
        val original = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        val nma = original.nativeMemoryAccess ?: return

        val wrapped = PlatformBuffer.wrapNativeAddress(nma.nativeAddress, 64, ByteOrder.BIG_ENDIAN)
        wrapped.writeShort(0x0102.toShort())
        // Big endian: 0x01 at offset 0, 0x02 at offset 1
        assertEquals(0x01.toByte(), original[0])
        assertEquals(0x02.toByte(), original[1])
    }

    @Test
    fun wrappedBufferRespectsLittleEndianByteOrder() {
        val original = BufferFactory.Default.allocate(64, ByteOrder.LITTLE_ENDIAN)
        val nma = original.nativeMemoryAccess ?: return

        val wrapped = PlatformBuffer.wrapNativeAddress(nma.nativeAddress, 64, ByteOrder.LITTLE_ENDIAN)
        wrapped.writeShort(0x0102.toShort())
        // Little endian: 0x02 at offset 0, 0x01 at offset 1
        assertEquals(0x02.toByte(), original[0])
        assertEquals(0x01.toByte(), original[1])
    }

    // ========== Ownership: wrapped buffer does NOT own memory ==========

    @Test
    fun wrappedBufferDoesNotImplementCloseableBuffer() {
        // wrapNativeAddress should return a non-owning buffer.
        // On most platforms this means it does NOT implement CloseableBuffer.
        val original = BufferFactory.deterministic().allocate(64)
        val nma =
            original.nativeMemoryAccess ?: run {
                original.freeNativeMemory()
                return
            }

        val wrapped = PlatformBuffer.wrapNativeAddress(nma.nativeAddress, 64)

        // The wrapped buffer should NOT be CloseableBuffer — it doesn't own the memory.
        // Note: On Linux, NativeBuffer always implements CloseableBuffer but with ownsMemory=false,
        // so freeNativeMemory is safe to call (it's a no-op). We test that separately below.
        // On JVM/Apple/WASM, the wrapped buffer should not be CloseableBuffer at all.
        if (wrapped is CloseableBuffer) {
            // Linux path: freeNativeMemory should be a no-op (ownsMemory=false)
            wrapped.freeNativeMemory()
            // Original should still be usable after wrapped's "free"
            original.writeInt(42)
            original.resetForRead()
            assertEquals(42, original.readInt())
        }

        // Clean up the original (which DOES own the memory)
        original.freeNativeMemory()
    }

    @Test
    fun freeingWrappedBufferDoesNotCorruptOriginal() {
        val original = BufferFactory.deterministic().allocate(64)
        val nma =
            original.nativeMemoryAccess ?: run {
                original.freeNativeMemory()
                return
            }

        val wrapped = PlatformBuffer.wrapNativeAddress(nma.nativeAddress, 64)

        // Write data through original
        original.writeInt(0xDEADBEEF.toInt())
        original.resetForRead()

        // "Free" the wrapped buffer — should be a no-op since it doesn't own memory
        wrapped.freeNativeMemory()

        // Original should still be readable — the data must not be corrupted
        assertEquals(0xDEADBEEF.toInt(), original.readInt())

        // Clean up the actual owner
        original.freeNativeMemory()
    }

    @Test
    fun originalOwnerFreeDoesNotDoubleFree() {
        // Ensure that freeing the original (owner) after wrapping doesn't cause issues
        val original = BufferFactory.deterministic().allocate(64)
        val nma =
            original.nativeMemoryAccess ?: run {
                original.freeNativeMemory()
                return
            }

        val wrapped = PlatformBuffer.wrapNativeAddress(nma.nativeAddress, 64)
        wrapped.writeInt(123)

        // Free the original — this frees the actual memory
        original.freeNativeMemory()
        // After this point, wrapped is dangling (undefined behavior if used)
        // The test verifies no crash/exception from the free itself

        // Freeing wrapped should also be safe (no-op, no double-free)
        wrapped.freeNativeMemory()
    }

    // ========== Fill capacity ==========

    @Test
    fun wrappedBufferCanFillEntireCapacity() {
        val original = BufferFactory.Default.allocate(1024)
        val nma = original.nativeMemoryAccess ?: return

        val wrapped = PlatformBuffer.wrapNativeAddress(nma.nativeAddress, 1024)

        // Fill the entire buffer with a pattern
        for (i in 0 until 256) {
            wrapped.writeInt(i)
        }
        assertEquals(0, wrapped.remaining())

        // Verify through original
        for (i in 0 until 256) {
            assertEquals(i, original.getInt(i * 4))
        }
    }

    // ========== String operations ==========

    @Test
    fun wrappedBufferSupportsStringReadWrite() {
        val original = BufferFactory.Default.allocate(256)
        val nma = original.nativeMemoryAccess ?: return

        val wrapped = PlatformBuffer.wrapNativeAddress(nma.nativeAddress, 256)
        val testString = "Hello, wrapNativeAddress!"
        wrapped.writeString(testString)
        wrapped.resetForRead()
        assertEquals(testString, wrapped.readString(testString.encodeToByteArray().size))
    }

    // ========== Multiple wraps of same memory ==========

    @Test
    fun multipleWrapsOfSameMemoryAreSafe() {
        val original = BufferFactory.Default.allocate(64)
        val nma = original.nativeMemoryAccess ?: return

        val wrap1 = PlatformBuffer.wrapNativeAddress(nma.nativeAddress, 64)
        val wrap2 = PlatformBuffer.wrapNativeAddress(nma.nativeAddress, 64)

        // Write through wrap1, read through wrap2
        wrap1.writeInt(0xCAFEBABE.toInt())
        assertEquals(0xCAFEBABE.toInt(), wrap2.readInt())

        // Both wraps are independent views with their own position
        assertEquals(4, wrap1.position())
        assertEquals(4, wrap2.position())

        // Freeing both is safe (both are non-owning)
        wrap1.freeNativeMemory()
        wrap2.freeNativeMemory()
    }
}
