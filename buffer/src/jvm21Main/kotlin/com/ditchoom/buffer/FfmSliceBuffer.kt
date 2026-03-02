@file:Suppress("unused") // Loaded at runtime via multi-release JAR

package com.ditchoom.buffer

import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer

/**
 * Arena-scoped slice of an [FfmBuffer].
 *
 * The [byteBuffer] is derived from [segment]`.asByteBuffer()`, which binds it to the
 * parent Arena's scope. When the parent calls `arena.close()`, the JDK automatically
 * invalidates ALL ByteBuffers derived from that Arena — any access throws
 * [IllegalStateException] ("Already closed"). This gives zero-overhead use-after-free
 * safety without any manual checking.
 *
 * Unlike [FfmBuffer], this class does **not** own the Arena and [freeNativeMemory] is a
 * no-op. The parent [FfmBuffer] is solely responsible for closing the Arena.
 *
 * @param segment Arena-scoped MemorySegment (not global-scope).
 * @param byteBuffer ByteBuffer derived from [segment]`.asByteBuffer()`.
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
        val bb = sliced.asByteBuffer().order(byteBuffer.order())
        return FfmSliceBuffer(sliced, bb)
    }
}
