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
    override fun freeNativeMemory() {
        // no-op: GC-managed memory has no explicit free.
    }

    override fun tryWriteUtf8ToNative(text: CharSequence): Boolean {
        position(encodeUtf8ToNative(text, position(), limit(), segment.address()))
        return true
    }

    // Read mirror of tryWriteUtf8ToNative: decode UTF-8 straight off the segment's native address
    // instead of taking the direct-ByteBuffer decodeBufferLoop. Non-UTF-8 falls back to the base
    // CharsetDecoder path.
    override fun readString(
        length: Int,
        charset: Charset,
    ): String {
        if (charset == Charset.UTF8) {
            val start = position()
            val decoded = decodeUtf8FromNative(segment.address(), start, start + length)
            position(start + length)
            return decoded
        }
        return super.readString(length, charset)
    }

    override fun slice(byteOrder: ByteOrder): PlatformBuffer {
        val slicedSegment = segment.asSlice(position().toLong(), remaining().toLong())
        val globalView = MemorySegment.ofAddress(slicedSegment.address()).reinterpret(slicedSegment.byteSize())
        val sliceByteBuffer = globalView.asByteBuffer().order(byteOrder.toJava())
        return FfmSliceBuffer(slicedSegment, sliceByteBuffer)
    }
}
