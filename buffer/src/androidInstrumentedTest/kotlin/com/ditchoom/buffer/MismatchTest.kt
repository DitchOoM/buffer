package com.ditchoom.buffer

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for buffer mismatch functionality on Android.
 *
 * On API 34+, this exercises ByteBuffer.mismatch() via BufferMismatchHelper.
 * On older APIs, this exercises the Long comparison fallback.
 */
@RunWith(AndroidJUnit4::class)
@SmallTest
class MismatchTest {
    @Test
    fun mismatchIdenticalBuffers() {
        val buffer1 = PlatformBuffer.allocate(100)
        val buffer2 = PlatformBuffer.allocate(100)

        repeat(100) {
            buffer1.writeByte(it.toByte())
            buffer2.writeByte(it.toByte())
        }
        buffer1.resetForRead()
        buffer2.resetForRead()

        assertTrue(buffer1.contentEquals(buffer2), "Identical buffers should match")
        assertEquals(-1, buffer1.mismatch(buffer2), "Identical buffers should have no mismatch")
    }

    @Test
    fun mismatchDifferentBuffers() {
        val buffer1 = PlatformBuffer.allocate(100)
        val buffer2 = PlatformBuffer.allocate(100)

        repeat(100) {
            buffer1.writeByte(it.toByte())
            buffer2.writeByte(it.toByte())
        }

        // Modify byte at position 50
        buffer2.position(50)
        buffer2.writeByte(99.toByte())

        buffer1.resetForRead()
        buffer2.resetForRead()

        assertEquals(50, buffer1.mismatch(buffer2), "Should find mismatch at position 50")
    }

    @Test
    fun mismatchFirstByte() {
        val buffer1 = PlatformBuffer.allocate(10)
        val buffer2 = PlatformBuffer.allocate(10)

        buffer1.writeByte(1)
        buffer2.writeByte(2)

        buffer1.resetForRead()
        buffer2.resetForRead()

        assertEquals(0, buffer1.mismatch(buffer2), "Should find mismatch at first byte")
    }

    @Test
    fun mismatchDifferentSizes() {
        val small = PlatformBuffer.allocate(10)
        val large = PlatformBuffer.allocate(20)

        repeat(10) {
            small.writeByte(it.toByte())
            large.writeByte(it.toByte())
        }
        repeat(10) { large.writeByte(it.toByte()) }

        small.resetForRead()
        large.resetForRead()

        assertEquals(10, small.mismatch(large), "Should find mismatch at end of smaller buffer")
    }

    @Test
    fun mismatchEmptyBuffers() {
        val empty1 = PlatformBuffer.allocate(0)
        val empty2 = PlatformBuffer.allocate(0)

        assertEquals(-1, empty1.mismatch(empty2), "Empty buffers should match")
    }

    @Test
    fun mismatchLargeBuffers() {
        // Test with larger buffers to exercise the Long comparison optimization
        val size = 10_000
        val buffer1 = PlatformBuffer.allocate(size)
        val buffer2 = PlatformBuffer.allocate(size)

        repeat(size) {
            buffer1.writeByte(it.toByte())
            buffer2.writeByte(it.toByte())
        }

        // Modify byte near the end
        buffer2.position(9500)
        buffer2.writeByte(99.toByte())

        buffer1.resetForRead()
        buffer2.resetForRead()

        assertEquals(9500, buffer1.mismatch(buffer2), "Should find mismatch at position 9500")
    }

    @Test
    fun reportsApiLevel() {
        // Log which implementation path is being used
        val apiLevel = Build.VERSION.SDK_INT
        val usesOptimized = apiLevel >= 34
        println("Android API level: $apiLevel, uses optimized mismatch: $usesOptimized")

        // Just verify tests run correctly regardless of API level
        assertTrue(apiLevel >= 19, "Should be running on API 19+")
    }
}
