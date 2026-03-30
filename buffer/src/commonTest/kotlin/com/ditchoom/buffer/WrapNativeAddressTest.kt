package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WrapNativeAddressTest {
    @Test
    fun wrapNativeAddressReadsThroughOriginal() {
        // Allocate a real buffer and get its native address
        val original = BufferFactory.Default.allocate(64)
        original.writeInt(0x12345678)
        original.writeInt(0xABCDEF00.toInt())
        original.resetForRead()

        val nma = original.nativeMemoryAccess ?: return // skip on platforms without native access

        // Wrap the same memory via nativeAddress
        val wrapped = PlatformBuffer.wrapNativeAddress(nma.nativeAddress, 64)
        assertEquals(0x12345678, wrapped.readInt())
        assertEquals(0xABCDEF00.toInt(), wrapped.readInt())
    }

    @Test
    fun wrapNativeAddressWritesThroughToOriginal() {
        val original = BufferFactory.Default.allocate(64)
        val nma = original.nativeMemoryAccess ?: return

        // Write through the wrapped buffer
        val wrapped = PlatformBuffer.wrapNativeAddress(nma.nativeAddress, 64)
        wrapped.writeInt(42)
        wrapped.writeShort(1000.toShort())

        // Read through the original using absolute reads (position-independent)
        // since the two buffers share memory but not position/limit state
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

    @Test
    fun wrappedBufferRespectsSize() {
        val original = BufferFactory.Default.allocate(128)
        val nma = original.nativeMemoryAccess ?: return

        // Wrap only 16 bytes
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

        // Use the BufferFactory.Companion convenience
        val wrapped = BufferFactory.wrapNativeAddress(nma.nativeAddress, 32)
        assertTrue(wrapped is PlatformBuffer)
        assertEquals(32, wrapped.capacity)
    }
}
