package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests to validate the documentation samples from docs/docs/recipes/platform-interop.md
 * These tests ensure that the code examples in the documentation actually work.
 */
class PlatformInteropDocTests {
    // === toByteArray() documentation samples ===

    @Test
    fun toByteArrayBasicUsage() {
        val buffer = PlatformBuffer.allocate(100)
        buffer.writeInt(42)
        buffer.writeString("Hello")
        buffer.resetForRead()

        // Convert remaining bytes to ByteArray
        val bytes = buffer.toByteArray()

        // Position is unchanged - can read again
        val value = buffer.readInt() // 42
        assertEquals(42, value)
        assertTrue(bytes.isNotEmpty())
    }

    // === Position Invariance documentation samples ===

    @Test
    fun positionInvarianceExample() {
        val buffer = PlatformBuffer.allocate(100)
        buffer.writeInt(1)
        buffer.writeInt(2)
        buffer.writeInt(3)
        buffer.resetForRead()

        // Read first int
        buffer.readInt() // position is now 4

        val positionBefore = buffer.position() // 4
        val limitBefore = buffer.limit() // 12

        // Convert remaining bytes
        val bytes = buffer.toByteArray() // contains bytes for ints 2 and 3

        // Position and limit unchanged
        assertEquals(positionBefore, buffer.position()) // still 4
        assertEquals(limitBefore, buffer.limit()) // still 12

        // Can continue reading
        val second = buffer.readInt() // 2
        assertEquals(2, second)

        // bytes should contain 8 bytes (2 ints)
        assertEquals(8, bytes.size)
    }

    // === toNativeData() documentation samples ===

    @Test
    fun toNativeDataBasicUsage() {
        val buffer = PlatformBuffer.allocate(100)
        val data = byteArrayOf(1, 2, 3, 4, 5)
        buffer.writeBytes(data)
        buffer.resetForRead()

        // Convert to platform-native wrapper
        val native = buffer.toNativeData()

        // Verify the conversion worked (platform-specific access tested in platform tests)
        // Position should be unchanged
        assertEquals(0, buffer.position())
        assertEquals(5, buffer.limit())
    }

    // === toMutableNativeData() documentation samples ===

    @Test
    fun toMutableNativeDataBasicUsage() {
        val buffer = PlatformBuffer.allocate(100)
        val data = byteArrayOf(1, 2, 3, 4, 5)
        buffer.writeBytes(data)
        buffer.resetForRead()

        // Convert to mutable native wrapper
        val mutableNative = buffer.toMutableNativeData()

        // Verify the conversion worked (platform-specific access tested in platform tests)
        // Position should be unchanged
        assertEquals(0, buffer.position())
        assertEquals(5, buffer.limit())
    }

    // === Zero-copy behavior tests ===

    @Test
    fun toByteArrayOnPartialBuffer() {
        val buffer = PlatformBuffer.allocate(100)
        buffer.writeInt(1)
        buffer.writeInt(2)
        buffer.writeInt(3)
        buffer.resetForRead()

        // Skip first int
        buffer.position(4)

        // Convert remaining (should be ints 2 and 3)
        val bytes = buffer.toByteArray()
        assertEquals(8, bytes.size) // 2 ints = 8 bytes

        // Position unchanged
        assertEquals(4, buffer.position())

        // Verify the content by reading
        val int2 = buffer.readInt()
        val int3 = buffer.readInt()
        assertEquals(2, int2)
        assertEquals(3, int3)
    }

    @Test
    fun toNativeDataOnPartialBuffer() {
        val buffer = PlatformBuffer.allocate(100)
        buffer.writeInt(1)
        buffer.writeInt(2)
        buffer.writeInt(3)
        buffer.resetForRead()

        // Skip first int
        buffer.position(4)

        // Convert remaining
        val native = buffer.toNativeData()

        // Position unchanged
        assertEquals(4, buffer.position())
        assertEquals(12, buffer.limit())
    }

    @Test
    fun toMutableNativeDataOnPartialBuffer() {
        val buffer = PlatformBuffer.allocate(100)
        buffer.writeInt(1)
        buffer.writeInt(2)
        buffer.writeInt(3)
        buffer.resetForRead()

        // Skip first int
        buffer.position(4)

        // Convert remaining
        val mutableNative = buffer.toMutableNativeData()

        // Position unchanged
        assertEquals(4, buffer.position())
        assertEquals(12, buffer.limit())
    }

    // === Wrapping and round-trip tests ===

    @Test
    fun wrapByteArrayRoundTrip() {
        // Wrap existing byte array (no copy)
        val data = byteArrayOf(0, 0, 0, 42)
        val buffer = PlatformBuffer.wrap(data)

        val value = buffer.readInt() // 42 (big-endian)
        assertEquals(42, value)
    }

    @Test
    fun wrapByteArrayWithByteOrder() {
        // Big-endian: 0x0000002A = 42
        val bigEndianData = byteArrayOf(0, 0, 0, 42)
        val bigEndian = PlatformBuffer.wrap(bigEndianData, ByteOrder.BIG_ENDIAN)
        assertEquals(42, bigEndian.readInt())

        // Little-endian: 0x2A000000
        val littleEndianData = byteArrayOf(42, 0, 0, 0)
        val littleEndian = PlatformBuffer.wrap(littleEndianData, ByteOrder.LITTLE_ENDIAN)
        assertEquals(42, littleEndian.readInt())
    }

    // === Multiple conversions don't affect buffer ===

    @Test
    fun multipleConversionsDontAffectBuffer() {
        val buffer = PlatformBuffer.allocate(100)
        buffer.writeInt(42)
        buffer.writeString("Hello")
        buffer.resetForRead()

        val initialPosition = buffer.position()
        val initialLimit = buffer.limit()

        // Multiple conversions
        buffer.toByteArray()
        buffer.toNativeData()
        buffer.toMutableNativeData()
        buffer.toByteArray()
        buffer.toNativeData()

        // Position and limit unchanged after all conversions
        assertEquals(initialPosition, buffer.position())
        assertEquals(initialLimit, buffer.limit())

        // Can still read
        val value = buffer.readInt()
        assertEquals(42, value)
    }

    // === Empty buffer conversions ===

    @Test
    fun emptyBufferConversions() {
        val buffer = PlatformBuffer.allocate(100)
        buffer.resetForRead() // position=0, limit=0

        val bytes = buffer.toByteArray()
        assertEquals(0, bytes.size)

        // Should not throw
        buffer.toNativeData()
        buffer.toMutableNativeData()
    }

    // === Direct vs Heap allocation zones ===

    @Test
    fun directAllocationConversions() {
        val buffer = PlatformBuffer.allocate(100, AllocationZone.Direct)
        buffer.writeInt(42)
        buffer.resetForRead()

        val bytes = buffer.toByteArray()
        val native = buffer.toNativeData()
        val mutableNative = buffer.toMutableNativeData()

        assertEquals(0, buffer.position())
        assertEquals(4, buffer.limit())
        assertEquals(4, bytes.size)
    }

    @Test
    fun heapAllocationConversions() {
        val buffer = PlatformBuffer.allocate(100, AllocationZone.Heap)
        buffer.writeInt(42)
        buffer.resetForRead()

        val bytes = buffer.toByteArray()
        val native = buffer.toNativeData()
        val mutableNative = buffer.toMutableNativeData()

        assertEquals(0, buffer.position())
        assertEquals(4, buffer.limit())
        assertEquals(4, bytes.size)
    }
}
