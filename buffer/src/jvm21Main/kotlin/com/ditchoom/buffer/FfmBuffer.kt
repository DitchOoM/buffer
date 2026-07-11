@file:Suppress("unused") // Loaded at runtime via multi-release JAR

package com.ditchoom.buffer

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer

/**
 * FFM-backed PlatformBuffer for Java 21+.
 *
 * Uses [Arena.ofShared] for deterministic native memory management. When [freeNativeMemory]
 * is called, the Arena is closed and the native memory is freed immediately from any thread —
 * no GC/Cleaner dependency.
 *
 * The ByteBuffer view is created from a global-scope reinterpretation of the segment address,
 * making it compatible with all JDK APIs (Deflater, Inflater, CRC32, NIO). The shared Arena
 * still owns the memory; the global-scope ByteBuffer is just a view.
 *
 * This transparently replaces [DirectJvmBuffer] on JVM 21+ via the multi-release JAR.
 * All buffer operations are inherited from [BaseJvmBuffer] via the ByteBuffer view.
 *
 * @param arena The shared FFM Arena that owns the native memory. Closed by [freeNativeMemory].
 * @param segment The MemorySegment backing this buffer (shared Arena scope).
 * @param byteBuffer A global-scope ByteBuffer view for JDK API compatibility.
 */
class FfmBuffer(
    private val arena: Arena,
    val segment: MemorySegment,
    byteBuffer: ByteBuffer,
) : BaseJvmBuffer(byteBuffer),
    NativeMemoryAccess,
    CloseableBuffer {
    override val isFreed: Boolean
        get() = !arena.scope().isAlive

    /**
     * The native memory address of the FFM MemorySegment.
     * With assertions enabled, throws if the buffer has been freed.
     */
    override val nativeAddress: Long
        get() {
            assertAlive()
            return segment.address()
        }

    /**
     * The size of the native memory region in bytes.
     * With assertions enabled, throws if the buffer has been freed.
     */
    override val nativeSize: Long
        get() {
            assertAlive()
            return segment.byteSize()
        }

    private fun assertAlive() {
        assert(arena.scope().isAlive) { "FfmBuffer used after freeNativeMemory()" }
    }

    /**
     * Frees the native memory by closing the Arena and invalidating the ByteBuffer view.
     *
     * After this call, the ByteBuffer's limit is set to 0, causing any subsequent
     * read/write operation to throw [java.nio.BufferUnderflowException] or
     * [java.nio.BufferOverflowException]. [BaseJvmBuffer] catches these and
     * rethrows as [BufferUnderflowException] / [BufferOverflowException] —
     * subclasses of the native nio types that carry a richer message. With
     * assertions enabled (`-ea`), lifecycle methods ([resetForRead],
     * [resetForWrite], [slice]) will throw [AssertionError] before the
     * ByteBuffer check.
     *
     * This method is idempotent — calling it multiple times is safe.
     *
     * **Note:** Slices ([FfmSliceBuffer]) retain the arena-scoped segment for lifecycle
     * checking via [FfmSliceBuffer.checkAlive], but use global-scope ByteBuffer views
     * for JDK API compatibility.
     */
    override fun freeNativeMemory() {
        if (arena.scope().isAlive) {
            arena.close()
            // Invalidate the ByteBuffer view so any subsequent access throws
            // BufferUnderflowException/BufferOverflowException instead of
            // silently accessing freed native memory.
            val buf = byteBuffer as java.nio.Buffer
            buf.position(0)
            buf.limit(0)
        }
    }

    override fun resetForRead() {
        assertAlive()
        super.resetForRead()
    }

    override fun resetForWrite() {
        assertAlive()
        super.resetForWrite()
    }

    override fun tryWriteUtf8ToNative(text: CharSequence): Boolean {
        // After free the arena is closed and byteBuffer.limit is 0; fall back so the encoder path's
        // limit=0 guard throws BufferOverflowException exactly as before (and we never read a closed
        // segment's address). While alive, encode straight to the shared segment's native memory.
        if (isFreed) return false
        position(encodeUtf8ToNative(text, position(), limit(), segment.address()))
        return true
    }

    /**
     * Returns an [FfmSliceBuffer] slice with a global-scope ByteBuffer view.
     * The slice retains the arena-scoped segment for lifecycle checking via [FfmSliceBuffer.checkAlive].
     */
    override fun slice(byteOrder: ByteOrder): PlatformBuffer {
        assertAlive()
        val slicedSegment = segment.asSlice(position().toLong(), remaining().toLong())
        val globalView = MemorySegment.ofAddress(slicedSegment.address()).reinterpret(slicedSegment.byteSize())
        val sliceByteBuffer = globalView.asByteBuffer().order(byteOrder.toJava())
        return FfmSliceBuffer(slicedSegment, sliceByteBuffer)
    }
}
