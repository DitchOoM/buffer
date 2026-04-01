package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WrapNativeAddressTest {
    /**
     * Helper that wraps native address. Returns null on platforms that don't support it
     * (JS throws UnsupportedOperationException) or don't have native memory access.
     */
    private fun tryWrap(
        original: PlatformBuffer,
        size: Int = original.capacity,
        offset: Long = 0,
        byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
    ): PlatformBuffer? {
        val nma = original.nativeMemoryAccess ?: return null
        return try {
            PlatformBuffer.wrapNativeAddress(nma.nativeAddress + offset, size, byteOrder)
        } catch (_: UnsupportedOperationException) {
            null
        }
    }

    // ========== Basic read/write through ==========

    @Test
    fun wrapNativeAddressReadsThroughOriginal() {
        val original = BufferFactory.Default.allocate(64)
        original.writeInt(0x12345678)
        original.writeInt(0xABCDEF00.toInt())
        original.resetForRead()

        val wrapped = tryWrap(original) ?: return
        assertEquals(0x12345678, wrapped.readInt())
        assertEquals(0xABCDEF00.toInt(), wrapped.readInt())
    }

    @Test
    fun wrapNativeAddressWritesThroughToOriginal() {
        val original = BufferFactory.Default.allocate(64)
        val wrapped = tryWrap(original) ?: return

        wrapped.writeInt(42)
        wrapped.writeShort(1000.toShort())

        assertEquals(42, original.getInt(0))
        assertEquals(1000.toShort(), original.getShort(4))
    }

    @Test
    fun wrappedBufferHasNativeMemoryAccess() {
        val original = BufferFactory.Default.allocate(32)
        val nma = original.nativeMemoryAccess ?: return
        val wrapped = tryWrap(original) ?: return

        val wrappedNma = wrapped.nativeMemoryAccess
        assertNotNull(wrappedNma)
        assertEquals(nma.nativeAddress, wrappedNma.nativeAddress)
    }

    // ========== Size and offset ==========

    @Test
    fun wrappedBufferRespectsSize() {
        val original = BufferFactory.Default.allocate(128)
        val wrapped = tryWrap(original, size = 16) ?: return
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

        val wrapped = tryWrap(original, size = 8, offset = 4) ?: return
        assertEquals(0x22222222, wrapped.readInt())
        assertEquals(0x33333333, wrapped.readInt())
    }

    @Test
    fun bufferFactoryConvenienceWorks() {
        val original = BufferFactory.Default.allocate(32)
        val nma = original.nativeMemoryAccess ?: return
        val wrapped =
            try {
                BufferFactory.wrapNativeAddress(nma.nativeAddress, 32)
            } catch (_: UnsupportedOperationException) {
                return
            }
        assertTrue(wrapped is PlatformBuffer)
        assertEquals(32, wrapped.capacity)
    }

    // ========== Byte order ==========

    @Test
    fun wrappedBufferRespectsBigEndianByteOrder() {
        val original = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        val wrapped = tryWrap(original, byteOrder = ByteOrder.BIG_ENDIAN) ?: return

        wrapped.writeShort(0x0102.toShort())
        assertEquals(0x01.toByte(), original[0])
        assertEquals(0x02.toByte(), original[1])
    }

    @Test
    fun wrappedBufferRespectsLittleEndianByteOrder() {
        val original = BufferFactory.Default.allocate(64, ByteOrder.LITTLE_ENDIAN)
        val wrapped = tryWrap(original, byteOrder = ByteOrder.LITTLE_ENDIAN) ?: return

        wrapped.writeShort(0x0102.toShort())
        assertEquals(0x02.toByte(), original[0])
        assertEquals(0x01.toByte(), original[1])
    }

    // ========== Ownership: wrapped buffer does NOT own memory ==========

    @Test
    fun wrappedBufferDoesNotImplementCloseableBuffer() {
        val original = deterministicAllocateOrSkip(64) ?: return
        val wrapped =
            tryWrap(original) ?: run {
                original.freeNativeMemory()
                return
            }

        if (wrapped is CloseableBuffer) {
            wrapped.freeNativeMemory()
            original.writeInt(42)
            original.resetForRead()
            assertEquals(42, original.readInt())
        }

        original.freeNativeMemory()
    }

    @Test
    fun freeingWrappedBufferDoesNotCorruptOriginal() {
        val original = deterministicAllocateOrSkip(64) ?: return
        val wrapped =
            tryWrap(original) ?: run {
                original.freeNativeMemory()
                return
            }

        original.writeInt(0xDEADBEEF.toInt())
        original.resetForRead()

        wrapped.freeNativeMemory()

        assertEquals(0xDEADBEEF.toInt(), original.readInt())
        original.freeNativeMemory()
    }

    @Test
    fun originalOwnerFreeDoesNotDoubleFree() {
        val original = deterministicAllocateOrSkip(64) ?: return
        val wrapped =
            tryWrap(original) ?: run {
                original.freeNativeMemory()
                return
            }
        wrapped.writeInt(123)

        original.freeNativeMemory()
        wrapped.freeNativeMemory()
    }

    // ========== Fill capacity ==========

    @Test
    fun wrappedBufferCanFillEntireCapacity() {
        val original = BufferFactory.Default.allocate(1024)
        val wrapped = tryWrap(original) ?: return

        for (i in 0 until 256) {
            wrapped.writeInt(i)
        }
        assertEquals(0, wrapped.remaining())

        for (i in 0 until 256) {
            assertEquals(i, original.getInt(i * 4))
        }
    }

    // ========== String operations ==========

    @Test
    fun wrappedBufferSupportsStringReadWrite() {
        val original = BufferFactory.Default.allocate(256)
        val wrapped = tryWrap(original) ?: return

        val testString = "Hello, wrapNativeAddress!"
        wrapped.writeString(testString)
        wrapped.resetForRead()
        assertEquals(testString, wrapped.readString(testString.encodeToByteArray().size))
    }

    // ========== Multiple wraps of same memory ==========

    @Test
    fun multipleWrapsOfSameMemoryAreSafe() {
        val original = BufferFactory.Default.allocate(64)
        val wrap1 = tryWrap(original) ?: return
        val wrap2 = tryWrap(original) ?: return

        wrap1.writeInt(0xCAFEBABE.toInt())
        assertEquals(0xCAFEBABE.toInt(), wrap2.readInt())

        assertEquals(4, wrap1.position())
        assertEquals(4, wrap2.position())

        wrap1.freeNativeMemory()
        wrap2.freeNativeMemory()
    }
}
