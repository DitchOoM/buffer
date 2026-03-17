@file:Suppress("unused") // Loaded at runtime via multi-release JAR

package com.ditchoom.buffer

import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer

/**
 * GC-managed FFM-backed PlatformBuffer for Java 21+.
 *
 * Uses [java.lang.foreign.Arena.ofAuto] — the native memory is collected by the garbage
 * collector when no references remain. Unlike [FfmBuffer], this class does **not** implement
 * [CloseableBuffer] because closing an auto arena throws [UnsupportedOperationException].
 *
 * This is the buffer returned by [BufferFactory.Default] on JVM 21+, providing the same
 * native-memory performance as [FfmBuffer] without requiring explicit lifecycle management.
 *
 * @param segment The MemorySegment backing this buffer (auto Arena scope).
 * @param byteBuffer A global-scope ByteBuffer view for JDK API compatibility.
 */
class FfmAutoBuffer(
    val segment: MemorySegment,
    byteBuffer: ByteBuffer,
) : BaseJvmBuffer(byteBuffer),
    NativeMemoryAccess {
    override val nativeAddress: Long
        get() = segment.address()

    override val nativeSize: Long
        get() = segment.byteSize()

    /** No-op — memory is GC-managed via [java.lang.foreign.Arena.ofAuto]. */
    override fun freeNativeMemory() {}

    override fun slice(): PlatformBuffer {
        val slicedSegment = segment.asSlice(position().toLong(), remaining().toLong())
        val globalView = MemorySegment.ofAddress(slicedSegment.address()).reinterpret(slicedSegment.byteSize())
        val sliceByteBuffer = globalView.asByteBuffer().order(byteBuffer.order())
        return FfmSliceBuffer(slicedSegment, sliceByteBuffer)
    }
}
