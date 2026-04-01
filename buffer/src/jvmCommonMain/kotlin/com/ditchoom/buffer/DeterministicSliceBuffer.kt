package com.ditchoom.buffer

import java.nio.ByteBuffer

/**
 * Non-closeable slice of a deterministic buffer (Unsafe or Direct).
 *
 * The parent buffer owns the native memory; this slice only provides a view.
 * [freeNativeMemory] is a no-op. [nativeAddress] and [nativeSize] are guarded:
 * they throw [IllegalStateException] if the parent has been freed, preventing
 * FFI use of stale pointers.
 *
 * This is the deterministic-buffer equivalent of [FfmSliceBuffer] (JVM 21+).
 *
 * @param byteBuffer sliced ByteBuffer view of the parent's native memory
 * @param parentNativeAddress native address of the parent's memory region
 * @param isParentFreed returns true once the parent has freed its memory
 */
abstract class DeterministicSliceBuffer(
    byteBuffer: ByteBuffer,
    internal val parentNativeAddress: Long,
    internal val isParentFreed: () -> Boolean,
) : BaseJvmBuffer(byteBuffer),
    NativeMemoryAccess {
    override val nativeAddress: Long
        get() {
            check(!isParentFreed()) { "Slice used after parent buffer freed" }
            return parentNativeAddress
        }

    override val nativeSize: Long
        get() {
            check(!isParentFreed()) { "Slice used after parent buffer freed" }
            return capacity.toLong()
        }

    /** No-op — this slice does not own the native memory. */
    override fun freeNativeMemory() {}

    abstract override fun slice(): PlatformBuffer
}
