@file:Suppress("unused") // Loaded at runtime via multi-release JAR

package com.ditchoom.buffer

import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer

/**
 * Slice of an [FfmBuffer].
 *
 * The [byteBuffer] is a global-scope view of the slice's memory, created via
 * `MemorySegment.ofAddress(...).reinterpret(...)`. This makes it compatible with all
 * JDK APIs (Deflater, Inflater, CRC32, NIO channels) that reject ByteBuffers from
 * closeable sessions.
 *
 * Use-after-free safety is provided by [checkAlive], which verifies the parent Arena's
 * scope on every access to [nativeAddress] and [nativeSize].
 *
 * Unlike [FfmBuffer], this class does **not** own the Arena and [freeNativeMemory] is a
 * no-op. The parent [FfmBuffer] is solely responsible for closing the Arena.
 *
 * @param segment Arena-scoped MemorySegment (retains scope for [checkAlive]).
 * @param byteBuffer Global-scope ByteBuffer view for JDK API compatibility.
 */
class FfmSliceBuffer(
    val segment: MemorySegment,
    byteBuffer: ByteBuffer,
) : BaseJvmBuffer(byteBuffer),
    NativeMemoryAccess {
    override val nativeAddress: Long
        get() {
            checkAlive()
            return segment.address()
        }

    override val nativeSize: Long
        get() {
            checkAlive()
            return segment.byteSize()
        }

    private fun checkAlive() {
        check(segment.scope().isAlive) { "FfmSliceBuffer used after parent Arena closed" }
    }

    /** No-op — this slice does not own the Arena. */
    override fun freeNativeMemory() {}

    override fun slice(): PlatformBuffer {
        val sliced = segment.asSlice(position().toLong(), remaining().toLong())
        val globalView = MemorySegment.ofAddress(sliced.address()).reinterpret(sliced.byteSize())
        val bb = globalView.asByteBuffer().order(byteBuffer.order())
        return FfmSliceBuffer(sliced, bb)
    }
}
