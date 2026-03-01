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
    NativeMemoryAccess {
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
     * [java.nio.BufferOverflowException]. With assertions enabled (`-ea`),
     * lifecycle methods ([resetForRead], [resetForWrite], [slice]) will throw
     * [AssertionError] before the ByteBuffer check.
     *
     * This method is idempotent — calling it multiple times is safe.
     *
     * **Note:** Slices created before this call retain their own ByteBuffer position/limit
     * state and will NOT be invalidated. Slices must not outlive the parent FfmBuffer.
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

    override suspend fun close() {
        freeNativeMemory()
    }

    override fun resetForRead() {
        assertAlive()
        super.resetForRead()
    }

    override fun resetForWrite() {
        assertAlive()
        super.resetForWrite()
    }

    /**
     * Returns a [DirectJvmBuffer] slice that shares the parent's native memory.
     *
     * The slice does NOT own the Arena and will not close it — this prevents
     * use-after-free when the slice outlives the parent in pool scenarios.
     */
    override fun slice(): PlatformBuffer {
        assertAlive()
        return DirectJvmBuffer(byteBuffer.slice())
    }
}
