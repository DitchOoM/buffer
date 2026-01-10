package com.ditchoom.buffer

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import kotlin.test.assertEquals

/**
 * Direct tests of ByteBuffer.mismatch() on Android to diagnose issues.
 */
@RunWith(AndroidJUnit4::class)
@SmallTest
class ByteBufferMismatchTest {
    @Test
    fun testDirectByteBufferMismatch() {
        if (Build.VERSION.SDK_INT < 34) {
            println("Skipping test - requires API 34+")
            return
        }

        // Create simple byte arrays
        val bytes1 = ByteArray(100) { it.toByte() }
        val bytes2 = bytes1.copyOf()
        bytes2[50] = -1 // Differ at index 50

        // Test with heap buffers (wrap)
        val heap1 = ByteBuffer.wrap(bytes1)
        val heap2 = ByteBuffer.wrap(bytes2)
        val heapResult = heap1.mismatch(heap2)
        println("Heap buffer mismatch result: $heapResult (expected 50)")
        assertEquals(50, heapResult, "Heap buffers should mismatch at position 50")

        // Test with direct buffers
        val direct1 = ByteBuffer.allocateDirect(100)
        direct1.put(bytes1)
        direct1.flip()
        val direct2 = ByteBuffer.allocateDirect(100)
        direct2.put(bytes2)
        direct2.flip()
        val directResult = direct1.mismatch(direct2)
        println("Direct buffer mismatch result: $directResult (expected 50)")
        assertEquals(50, directResult, "Direct buffers should mismatch at position 50")
    }

    @Test
    fun testSlicedByteBufferMismatch() {
        if (Build.VERSION.SDK_INT < 34) {
            println("Skipping test - requires API 34+")
            return
        }

        // Create byte arrays
        val bytes1 = ByteArray(100) { it.toByte() }
        val bytes2 = bytes1.copyOf()
        bytes2[50] = -1

        // Test with sliced heap buffers
        val heap1 = ByteBuffer.wrap(bytes1).slice()
        val heap2 = ByteBuffer.wrap(bytes2).slice()
        val sliceResult = heap1.mismatch(heap2)
        println("Sliced heap buffer mismatch result: $sliceResult (expected 50)")
        assertEquals(50, sliceResult, "Sliced heap buffers should mismatch at position 50")

        // Test with sliced buffers with limit
        val heap3 = ByteBuffer.wrap(bytes1).slice()
        heap3.limit(100)
        val heap4 = ByteBuffer.wrap(bytes2).slice()
        heap4.limit(100)
        val limitResult = heap3.mismatch(heap4)
        println("Sliced+limited heap buffer mismatch result: $limitResult (expected 50)")
        assertEquals(50, limitResult, "Sliced+limited heap buffers should mismatch at position 50")
    }

    @Test
    fun testPositionedByteBufferMismatch() {
        if (Build.VERSION.SDK_INT < 34) {
            println("Skipping test - requires API 34+")
            return
        }

        // Create byte arrays with extra data at start
        val bytes1 = ByteArray(110) { it.toByte() }
        val bytes2 = bytes1.copyOf()
        bytes2[60] = -1 // Differ at absolute index 60, relative 50 after position 10

        // Test with positioned buffers
        val buf1 = ByteBuffer.wrap(bytes1)
        buf1.position(10)
        val buf2 = ByteBuffer.wrap(bytes2)
        buf2.position(10)

        val slice1 = buf1.slice()
        val slice2 = buf2.slice()
        val result = slice1.mismatch(slice2)
        println("Positioned slice mismatch result: $result (expected 50)")
        assertEquals(50, result, "Should find mismatch at relative position 50")
    }
}
