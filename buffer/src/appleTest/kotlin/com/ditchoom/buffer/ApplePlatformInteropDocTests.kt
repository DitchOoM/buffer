@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.UnsafeNumber::class,
)

package com.ditchoom.buffer

import kotlinx.cinterop.convert
import platform.Foundation.NSData
import platform.Foundation.NSMutableData
import platform.Foundation.create
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests to validate the Apple-specific documentation samples from docs/docs/platforms/apple.md
 */
class ApplePlatformInteropDocTests {
    @Test
    fun toNativeDataReturnsNSData() {
        val buffer = PlatformBuffer.allocate(1024)
        val data = byteArrayOf(1, 2, 3, 4, 5)
        buffer.writeBytes(data)
        buffer.resetForRead()

        // Get read-only NSData (zero-copy for MutableDataBuffer)
        val nativeData = buffer.toNativeData()
        val nsData: NSData = nativeData.nsData

        assertNotNull(nsData)
        assertEquals(5.convert(), nsData.length)
    }

    @Test
    fun toMutableNativeDataReturnsNSMutableData() {
        val buffer = PlatformBuffer.allocate(1024)
        val data = byteArrayOf(1, 2, 3, 4, 5)
        buffer.writeBytes(data)
        buffer.resetForRead()

        // Get mutable NSMutableData
        val mutableData = buffer.toMutableNativeData()
        val nsMutableData: NSMutableData = mutableData.nsMutableData

        assertNotNull(nsMutableData)
        assertEquals(5.convert(), nsMutableData.length)
    }

    @Test
    fun wrapNSDataRoundTrip() {
        // Create NSMutableData with content
        val original = NSMutableData.create(length = 8.convert())!!
        val writeBuffer = PlatformBuffer.wrap(original)
        writeBuffer.writeInt(42)
        writeBuffer.writeInt(100)

        // Wrap NSData (read-only)
        val nsData: NSData = original
        val buffer = PlatformBuffer.wrapReadOnly(nsData)

        // Read values
        assertEquals(42, buffer.readInt())
        assertEquals(100, buffer.readInt())
    }

    @Test
    fun wrapNSMutableDataRoundTrip() {
        // Create and wrap NSMutableData (mutable, zero-copy)
        val nsMutableData: NSMutableData = NSMutableData.create(length = 8.convert())!!
        val buffer = PlatformBuffer.wrap(nsMutableData)

        // Write and read
        buffer.writeInt(42)
        buffer.writeInt(100)
        buffer.resetForRead()

        assertEquals(42, buffer.readInt())
        assertEquals(100, buffer.readInt())
    }

    @Test
    fun zeroCopyBehaviorForMutableDataBuffer() {
        // MutableDataBuffer should return same NSData for full buffer
        val mutableData = NSMutableData.create(length = 8.convert())!!
        val buffer = PlatformBuffer.wrap(mutableData)
        buffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        buffer.resetForRead()

        val native = buffer.toNativeData()
        // Should return the same underlying NSData
        assertTrue(native.nsData === mutableData)
    }

    @Test
    fun zeroCopySubdataWithRange() {
        // subdataWithRange creates a zero-copy view
        val mutableData = NSMutableData.create(length = 8.convert())!!
        val buffer = PlatformBuffer.wrap(mutableData)
        buffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        buffer.resetForRead()
        buffer.position(2) // Skip first 2 bytes

        val native = buffer.toNativeData()
        // Should return a subdata view (zero-copy)
        assertEquals(6.convert(), native.nsData.length)
    }

    @Test
    fun byteArrayBufferConversion() {
        // ByteArrayBuffer must copy to NSData
        val buffer = PlatformBuffer.allocate(8, AllocationZone.Heap)
        buffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        buffer.resetForRead()

        val native = buffer.toNativeData()
        assertEquals(8.convert(), native.nsData.length)
    }

    @Test
    fun positionInvarianceForNSData() {
        val buffer = PlatformBuffer.allocate(100)
        buffer.writeInt(1)
        buffer.writeInt(2)
        buffer.writeInt(3)
        buffer.resetForRead()
        buffer.readInt() // position = 4

        val positionBefore = buffer.position()
        val limitBefore = buffer.limit()

        // Convert to NSData
        buffer.toNativeData()

        // Position and limit unchanged
        assertEquals(positionBefore, buffer.position())
        assertEquals(limitBefore, buffer.limit())
    }

    @Test
    fun positionInvarianceForNSMutableData() {
        val buffer = PlatformBuffer.allocate(100)
        buffer.writeInt(1)
        buffer.writeInt(2)
        buffer.writeInt(3)
        buffer.resetForRead()
        buffer.readInt() // position = 4

        val positionBefore = buffer.position()
        val limitBefore = buffer.limit()

        // Convert to NSMutableData
        buffer.toMutableNativeData()

        // Position and limit unchanged
        assertEquals(positionBefore, buffer.position())
        assertEquals(limitBefore, buffer.limit())
    }
}
