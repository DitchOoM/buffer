package com.ditchoom.buffer

import java.nio.ByteBuffer

/**
 * A DirectJvmBuffer with deterministic cleanup via Unsafe.invokeCleaner (JVM 9+).
 *
 * Unlike regular DirectJvmBuffer which relies on GC for cleanup, this buffer
 * implements [CloseableBuffer] and frees native memory immediately when
 * [freeNativeMemory] is called.
 *
 * Use-after-free safety: overrides the [byteBuffer] getter to throw
 * [IllegalStateException] when freed. Since ALL data operations in [BaseJvmBuffer]
 * go through [byteBuffer], this single guard protects every read/write method.
 *
 * Slices return [DeterministicSliceBuffer] which does NOT implement [CloseableBuffer]
 * and checks the parent's freed state on [nativeAddress]/[nativeSize] access.
 */
abstract class DeterministicDirectJvmBuffer(
    byteBuffer: ByteBuffer,
) : BaseJvmBuffer(byteBuffer),
    NativeMemoryAccess,
    CloseableBuffer {
    init {
        require(byteBuffer.isDirect) { "DeterministicDirectJvmBuffer requires a direct ByteBuffer" }
    }

    // --- byteBuffer guard: throws on ALL data operations after free ---

    override val byteBuffer: ByteBuffer
        get() {
            if (freed) throw IllegalStateException("Buffer has been freed")
            return super.byteBuffer
        }

    // --- NativeMemoryAccess ---

    internal val directAddress: Long by lazy { getDirectBufferAddress(super.byteBuffer) }

    override val nativeAddress: Long
        get() {
            if (freed) throw IllegalStateException("Buffer has been freed")
            return directAddress
        }

    override val nativeSize: Long
        get() {
            if (freed) throw IllegalStateException("Buffer has been freed")
            return capacity.toLong()
        }

    // --- CloseableBuffer ---

    private var freed = false
    override val isFreed: Boolean get() = freed

    override fun freeNativeMemory() {
        if (!freed) {
            freed = true
            invokeCleanerFn?.invoke(super.byteBuffer)
        }
    }

    // --- Slicing ---

    abstract override fun slice(): PlatformBuffer
}
