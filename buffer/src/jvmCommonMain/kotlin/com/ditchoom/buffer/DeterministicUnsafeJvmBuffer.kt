package com.ditchoom.buffer

import java.nio.ByteBuffer

/**
 * A DirectJvmBuffer-backed buffer with deterministic cleanup via Unsafe.freeMemory.
 *
 * Used on JVM 8 and Android where Unsafe.invokeCleaner is not available.
 * Allocates native memory with [UnsafeAllocator], wraps it as a DirectByteBuffer via
 * [UnsafeMemory.tryWrapAsDirectByteBuffer], and delegates all buffer operations to
 * [BaseJvmBuffer]. Implements [CloseableBuffer] — callers must call [freeNativeMemory]
 * or use `buffer.use {}`.
 *
 * Use-after-free safety: overrides the [byteBuffer] getter to throw
 * [IllegalStateException] when [freed]. Since ALL data operations in [BaseJvmBuffer]
 * go through [byteBuffer], this single guard protects every read/write method.
 * Metadata operations (position, limit, capacity) still work after free because
 * [BaseJvmBuffer] captures a separate `Buffer` reference at construction time.
 *
 * Slices return [DeterministicSliceBuffer] which does NOT implement [CloseableBuffer]
 * and checks the parent's freed state on [nativeAddress]/[nativeSize] access.
 */
abstract class DeterministicUnsafeJvmBuffer(
    byteBuffer: ByteBuffer,
    internal val unsafeAddress: Long,
) : BaseJvmBuffer(byteBuffer),
    NativeMemoryAccess,
    CloseableBuffer {
    init {
        require(byteBuffer.isDirect) { "DeterministicUnsafeJvmBuffer requires a direct ByteBuffer" }
    }

    // --- byteBuffer guard: throws on ALL data operations after free ---

    override val byteBuffer: ByteBuffer
        get() {
            if (freed) throw IllegalStateException("Buffer has been freed")
            return super.byteBuffer
        }

    // --- NativeMemoryAccess ---

    override val nativeAddress: Long
        get() {
            if (freed) throw IllegalStateException("Buffer has been freed")
            return unsafeAddress
        }

    override val nativeSize: Long
        get() {
            if (freed) throw IllegalStateException("Buffer has been freed")
            return capacity.toLong()
        }

    // --- CloseableBuffer ---

    @Volatile private var freed = false
    override val isFreed: Boolean get() = freed

    override fun freeNativeMemory() {
        if (!freed) {
            freed = true
            UnsafeAllocator.freeMemory(unsafeAddress)
        }
    }

    // --- Slicing ---

    override fun slice(): PlatformBuffer {
        // Check here too for a clear error message (vs generic byteBuffer guard)
        if (freed) throw IllegalStateException("Buffer has been freed")
        return sliceImpl()
    }

    protected abstract fun sliceImpl(): PlatformBuffer

    companion object
}
