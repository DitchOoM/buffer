package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests to verify multi-release JAR structure and correctness.
 *
 * Note: When running from IDE/Gradle (not from JAR), classes are loaded from
 * build directories. For full multi-release JAR verification from the actual
 * JAR file, use ValidateMultiReleaseJar.main() with different JVM versions.
 */
class MultiReleaseJarTest {
    private val javaVersion: Int by lazy {
        System
            .getProperty("java.specification.version")
            ?.substringBefore('.')
            ?.toIntOrNull() ?: 8
    }

    @Test
    fun `mismatch produces correct results`() {
        val buffer1 = PlatformBuffer.allocate(100)
        val buffer2 = PlatformBuffer.allocate(100)

        // Write same data to both
        repeat(100) {
            buffer1.writeByte(it.toByte())
            buffer2.writeByte(it.toByte())
        }
        buffer1.resetForRead()
        buffer2.resetForRead()

        // Should match
        assertTrue(buffer1.contentEquals(buffer2), "Identical buffers should match")
        assertEquals(-1, buffer1.mismatch(buffer2), "Identical buffers should have no mismatch")

        // Reset and modify one byte
        buffer1.position(0)
        buffer2.position(0)
        buffer2.position(50)
        buffer2.writeByte(99.toByte()) // Change byte at position 50
        buffer1.position(0)
        buffer2.position(0)

        // Should find mismatch at position 50
        assertEquals(50, buffer1.mismatch(buffer2), "Should find mismatch at position 50")
    }

    @Test
    fun `mismatch handles edge cases`() {
        // Empty buffers
        val empty1 = PlatformBuffer.allocate(0)
        val empty2 = PlatformBuffer.allocate(0)
        assertEquals(-1, empty1.mismatch(empty2), "Empty buffers should match")

        // Different sizes
        val small = PlatformBuffer.allocate(10)
        val large = PlatformBuffer.allocate(20)
        repeat(10) {
            small.writeByte(it.toByte())
            large.writeByte(it.toByte())
        }
        repeat(10) { large.writeByte(it.toByte()) }
        small.resetForRead()
        large.resetForRead()

        // Should return index where they differ (at end of smaller buffer)
        assertEquals(10, small.mismatch(large), "Should find mismatch at end of smaller buffer")

        // First byte mismatch
        val a = PlatformBuffer.allocate(10)
        val b = PlatformBuffer.allocate(10)
        a.writeByte(1)
        b.writeByte(2)
        a.resetForRead()
        b.resetForRead()
        assertEquals(0, a.mismatch(b), "Should find mismatch at first byte")
    }

    @Test
    fun `DirectBufferAddressHelper returns valid address`() {
        val directBuffer = java.nio.ByteBuffer.allocateDirect(64)
        val address = getDirectBufferAddress(directBuffer)

        // Address should be non-zero for a direct buffer
        assertTrue(address != 0L, "Direct buffer address should be non-zero")

        // Verify it's actually the buffer's address by writing bytes and reading them back
        // Using individual bytes avoids byte order complications
        directBuffer.put(0, 0x42.toByte())
        directBuffer.put(1, 0x43.toByte())
        directBuffer.put(2, 0x44.toByte())
        directBuffer.put(3, 0x45.toByte())

        if (UnsafeMemory.isSupported) {
            assertEquals(0x42.toByte(), UnsafeMemory.getByte(address), "Byte 0 should match")
            assertEquals(0x43.toByte(), UnsafeMemory.getByte(address + 1), "Byte 1 should match")
            assertEquals(0x44.toByte(), UnsafeMemory.getByte(address + 2), "Byte 2 should match")
            assertEquals(0x45.toByte(), UnsafeMemory.getByte(address + 3), "Byte 3 should match")
        }
    }

    @Test
    fun `report loaded multi-release classes`() {
        println("=== Multi-Release JAR Class Loading Report ===")
        println("Java version: $javaVersion")
        println()

        // Report BufferMismatchHelper
        val mismatchClass = BufferMismatchHelper::class.java
        val mismatchUrl =
            mismatchClass.classLoader?.getResource(
                mismatchClass.name.replace('.', '/') + ".class",
            )
        println("BufferMismatchHelper: $mismatchUrl")

        // Report DirectBufferAddressHelperKt
        val addressClass = Class.forName("com.ditchoom.buffer.DirectBufferAddressHelperKt")
        val addressUrl =
            addressClass.classLoader?.getResource(
                addressClass.name.replace('.', '/') + ".class",
            )
        println("DirectBufferAddressHelperKt: $addressUrl")

        // Expected implementations based on Java version
        println()
        println("Expected implementations on Java $javaVersion:")
        println("  BufferMismatchHelper: ${if (javaVersion >= 11) "ByteBuffer.mismatch()" else "fallback"}")
        println("  DirectBufferAddressHelper: ${if (javaVersion >= 21) "FFM MemorySegment" else "reflection"}")
    }
}
