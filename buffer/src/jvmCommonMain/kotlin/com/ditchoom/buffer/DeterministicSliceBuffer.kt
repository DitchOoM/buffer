package com.ditchoom.buffer

import java.nio.ByteBuffer

/**
 * Non-closeable slice of a deterministic buffer (Unsafe or Direct).
 *
 * The parent buffer owns the native memory; this slice only provides a view.
 * [freeNativeMemory] is a no-op. All data operations and [nativeAddress]/[nativeSize]
 * are guarded: they throw [IllegalStateException] if the parent has been freed,
 * preventing use of stale memory.
 *
 * This is the deterministic-buffer equivalent of [FfmSliceBuffer] (JVM 21+).
 *
 * @param byteBuffer sliced ByteBuffer view of the parent's native memory
 * @param isParentFreed returns true once the parent has freed its memory
 */
abstract class DeterministicSliceBuffer(
    byteBuffer: ByteBuffer,
    isParentFreed: () -> Boolean,
) : BaseJvmBuffer(byteBuffer),
    NativeMemoryAccess {
    // Nullable to handle virtual-call-from-constructor: BaseJvmBuffer's property
    // initializers call the overridden byteBuffer getter before this field is set.
    // Null during super-init is safe — the parent can't be freed during construction.
    @JvmField
    internal val parentFreedCheck: (() -> Boolean)? = isParentFreed

    // --- byteBuffer guard: throws on ALL data operations after parent free ---

    override val byteBuffer: ByteBuffer
        get() {
            if (parentFreedCheck?.invoke() == true) {
                throw IllegalStateException("Slice used after parent buffer freed")
            }
            return super.byteBuffer
        }

    // --- NativeMemoryAccess ---

    /** Lazily resolved address of the sliced ByteBuffer's native memory region. */
    private val sliceAddress: Long by lazy { getDirectBufferAddress(super.byteBuffer) }

    override val nativeAddress: Long
        get() {
            if (parentFreedCheck?.invoke() == true) {
                throw IllegalStateException("Slice used after parent buffer freed")
            }
            return sliceAddress
        }

    override val nativeSize: Long
        get() {
            if (parentFreedCheck?.invoke() == true) {
                throw IllegalStateException("Slice used after parent buffer freed")
            }
            return capacity.toLong()
        }

    /** No-op — this slice does not own the native memory. */
    override fun freeNativeMemory() {}

    abstract override fun slice(): PlatformBuffer
}
