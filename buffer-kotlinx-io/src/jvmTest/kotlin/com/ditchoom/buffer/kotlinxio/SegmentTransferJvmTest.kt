package com.ditchoom.buffer.kotlinxio

import com.ditchoom.buffer.BaseJvmBuffer
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.BufferOverflowException
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.JvmBuffer
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.unwrapFully
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Characterizes the JVM branch selection inside the segment-transfer helpers
 * ([readIntoSegment] / [writeSegmentBytes]) and pins the desktop-JVM buffer
 * shapes the `hasArray()` dispatch relies on.
 *
 * On desktop JVM, direct ByteBuffers are never array-backed, so the
 * `System.arraycopy` branch fires only for heap (managed) buffers here. On
 * Android, `ByteBuffer.allocateDirect()` is MemoryRef-backed (`hasArray()`
 * is `true` for `DirectJvmBuffer` too), which is the case the branch exists
 * for — covered by the device benchmark run, not this host test.
 */
class SegmentTransferJvmTest {
    @Test
    fun desktopShapes_managedIsArrayBacked_directIsNot() {
        val managed = BufferFactory.managed().allocate(16).unwrapFully() as BaseJvmBuffer
        assertTrue(managed.byteBuffer.hasArray(), "managed JVM buffer must be array-backed")

        val direct = BufferFactory.Default.allocate(16).unwrapFully() as BaseJvmBuffer
        assertFalse(direct.byteBuffer.hasArray(), "desktop direct ByteBuffer must not be array-backed")
    }

    @Test
    fun readIntoSegment_arrayBackedFastPath_copiesAndAdvances() {
        val expected = patternBytes(64)
        val buffer = readableBufferOf(expected, BufferFactory.managed())
        val dst = ByteArray(80)
        buffer.readIntoSegment(dst, 10, 64)
        assertContentEquals(expected, dst.copyOfRange(10, 74), "arraycopy fast path content")
        assertEquals(64, buffer.position(), "fast path must advance position")
    }

    @Test
    fun readIntoSegment_directFallback_copiesAndAdvances() {
        val expected = patternBytes(64)
        val buffer = readableBufferOf(expected, BufferFactory.Default)
        val dst = ByteArray(64)
        buffer.readIntoSegment(dst, 0, 64)
        assertContentEquals(expected, dst, "direct fallback content")
        assertEquals(64, buffer.position(), "fallback must advance position")
    }

    @Test
    fun readIntoSegment_readOnlyView_fallsBackAndCopies() {
        // hasArray() is false for read-only views, so array() is never touched.
        val expected = patternBytes(32)
        val readOnly = JvmBuffer(ByteBuffer.wrap(expected.copyOf()).asReadOnlyBuffer())
        val dst = ByteArray(32)
        readOnly.readIntoSegment(dst, 0, 32)
        assertContentEquals(expected, dst, "read-only view fallback content")
    }

    @Test
    fun writeSegmentBytes_arrayBackedFastPath_copiesAndAdvances() {
        val expected = patternBytes(64)
        val buffer = BufferFactory.managed().allocate(64)
        buffer.writeSegmentBytes(expected, 0, 64)
        assertEquals(64, buffer.position(), "fast path must advance position")
        buffer.resetForRead()
        assertContentEquals(expected, ByteArray(64) { buffer.readByte() }, "arraycopy fast path content")
    }

    @Test
    fun writeSegmentBytes_pooledWrapper_staysPositionSynced() {
        val p = BufferPool(defaultBufferSize = 256, factory = BufferFactory.managed())
        val pooled = p.acquire(64)
        val expected = patternBytes(48)
        pooled.writeSegmentBytes(expected, 0, 48)
        assertEquals(48, pooled.position(), "wrapper position must track the unwrapped buffer")
        pooled.resetForRead()
        assertContentEquals(expected, ByteArray(48) { pooled.readByte() }, "pooled content")
        p.release(pooled)
        p.clear()
    }

    @Test
    fun writeSegmentBytes_overCapacity_throwsLibraryOverflow() {
        // The fast-path guard rejects length > remaining; the fallback preserves
        // the library's overflow exception surface.
        val buffer = BufferFactory.managed().allocate(16)
        assertFailsWith<BufferOverflowException> {
            buffer.writeSegmentBytes(patternBytes(32), 0, 32)
        }
    }
}
