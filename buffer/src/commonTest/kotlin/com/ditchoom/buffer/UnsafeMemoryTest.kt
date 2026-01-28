package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals

class UnsafeMemoryTest {
    @Test
    fun testDirectBufferOperations() {
        if (!UnsafeMemory.isSupported) return

        val buffer = PlatformBuffer.allocate(64, AllocationZone.Direct)
        val address = (buffer as? NativeMemoryAccess)?.nativeAddress ?: return

        // Test byte
        UnsafeMemory.putByte(address, 0x42)
        assertEquals(0x42.toByte(), UnsafeMemory.getByte(address))

        // Test short
        UnsafeMemory.putShort(address + 2, 0x1234)
        assertEquals(0x1234.toShort(), UnsafeMemory.getShort(address + 2))

        // Test int
        UnsafeMemory.putInt(address + 8, 0x12345678)
        assertEquals(0x12345678, UnsafeMemory.getInt(address + 8))

        // Test long
        UnsafeMemory.putLong(address + 16, 0x123456789ABCDEF0L)
        assertEquals(0x123456789ABCDEF0L, UnsafeMemory.getLong(address + 16))
    }

    @Test
    fun testSetMemory() {
        if (!UnsafeMemory.isSupported) return

        val buffer = PlatformBuffer.allocate(32, AllocationZone.Direct)
        val address = (buffer as? NativeMemoryAccess)?.nativeAddress ?: return

        // Fill with 0xAA
        UnsafeMemory.setMemory(address, 32, 0xAA.toByte())

        // Verify all bytes are 0xAA
        for (i in 0 until 32) {
            assertEquals(0xAA.toByte(), UnsafeMemory.getByte(address + i), "Byte at offset $i should be 0xAA")
        }
    }

    @Test
    fun testCopyMemory() {
        if (!UnsafeMemory.isSupported) return

        val src = PlatformBuffer.allocate(16, AllocationZone.Direct)
        val dst = PlatformBuffer.allocate(16, AllocationZone.Direct)
        val srcAddr = (src as? NativeMemoryAccess)?.nativeAddress ?: return
        val dstAddr = (dst as? NativeMemoryAccess)?.nativeAddress ?: return

        // Write pattern to source
        for (i in 0 until 16) {
            UnsafeMemory.putByte(srcAddr + i, i.toByte())
        }

        // Copy to destination
        UnsafeMemory.copyMemory(srcAddr, dstAddr, 16)

        // Verify destination
        for (i in 0 until 16) {
            assertEquals(i.toByte(), UnsafeMemory.getByte(dstAddr + i), "Byte at offset $i should be $i")
        }
    }

    @Test
    fun testCopyMemoryEmptyArrays() {
        if (!UnsafeMemory.isSupported) return

        val buffer = PlatformBuffer.allocate(16, AllocationZone.Direct)
        val address = (buffer as? NativeMemoryAccess)?.nativeAddress ?: return

        // Initialize buffer with known pattern
        UnsafeMemory.setMemory(address, 16, 0xAA.toByte())

        // Test copyMemoryFromArray with empty array - should not throw
        val emptyArray = ByteArray(0)
        UnsafeMemory.copyMemoryFromArray(emptyArray, 0, address, 0)

        // Verify buffer unchanged
        assertEquals(0xAA.toByte(), UnsafeMemory.getByte(address))

        // Test copyMemoryToArray with empty array - should not throw
        UnsafeMemory.copyMemoryToArray(address, emptyArray, 0, 0)

        // Test copyMemoryFromArray with non-empty array but zero length
        val nonEmptyArray = byteArrayOf(0x11, 0x22, 0x33)
        UnsafeMemory.copyMemoryFromArray(nonEmptyArray, 0, address, 0)

        // Verify buffer still unchanged
        assertEquals(0xAA.toByte(), UnsafeMemory.getByte(address))

        // Test copyMemoryToArray with non-empty array but zero length
        val destArray = ByteArray(3)
        UnsafeMemory.copyMemoryToArray(address, destArray, 0, 0)

        // Verify destination array unchanged (should be all zeros)
        assertEquals(0.toByte(), destArray[0])
    }

    @Test
    fun testLargeBufferOperations() {
        if (!UnsafeMemory.isSupported) return

        // Test with 1MB buffer to verify Long size handling
        val size = 1024 * 1024 // 1MB
        val buffer = PlatformBuffer.allocate(size, AllocationZone.Direct)
        val address = (buffer as? NativeMemoryAccess)?.nativeAddress ?: return

        // Fill with 0x55 pattern
        UnsafeMemory.setMemory(address, size.toLong(), 0x55)

        // Verify first, middle, and last bytes
        assertEquals(0x55.toByte(), UnsafeMemory.getByte(address), "First byte should be 0x55")
        assertEquals(0x55.toByte(), UnsafeMemory.getByte(address + size / 2), "Middle byte should be 0x55")
        assertEquals(0x55.toByte(), UnsafeMemory.getByte(address + size - 1), "Last byte should be 0x55")

        // Test copyMemory with large buffer
        val dst = PlatformBuffer.allocate(size, AllocationZone.Direct)
        val dstAddr = (dst as? NativeMemoryAccess)?.nativeAddress ?: return

        UnsafeMemory.copyMemory(address, dstAddr, size.toLong())

        // Verify copy
        assertEquals(0x55.toByte(), UnsafeMemory.getByte(dstAddr), "First copied byte should be 0x55")
        assertEquals(0x55.toByte(), UnsafeMemory.getByte(dstAddr + size / 2), "Middle copied byte should be 0x55")
        assertEquals(0x55.toByte(), UnsafeMemory.getByte(dstAddr + size - 1), "Last copied byte should be 0x55")
    }
}
