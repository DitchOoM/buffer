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
 */
abstract class DeterministicUnsafeJvmBuffer(
    byteBuffer: ByteBuffer,
    private val unsafeAddress: Long,
) : BaseJvmBuffer(byteBuffer),
    NativeMemoryAccess,
    CloseableBuffer {
    init {
        require(byteBuffer.isDirect) { "DeterministicUnsafeJvmBuffer requires a direct ByteBuffer" }
    }

    override val nativeAddress: Long get() = unsafeAddress
    override val nativeSize: Long get() = capacity.toLong()

    private var freed = false
    override val isFreed: Boolean get() = freed

    override fun freeNativeMemory() {
        if (!freed) {
            freed = true
            UnsafeAllocator.freeMemory(unsafeAddress)
        }
    }

    private fun checkNotFreed() {
        if (freed) throw IllegalStateException("Buffer has been freed")
    }

    override fun readByte(): Byte {
        checkNotFreed()
        return super.readByte()
    }

    override fun get(index: Int): Byte {
        checkNotFreed()
        return super.get(index)
    }

    override fun writeByte(byte: Byte): WriteBuffer {
        checkNotFreed()
        return super.writeByte(byte)
    }

    override fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer {
        checkNotFreed()
        return super.set(index, byte)
    }

    abstract override fun slice(): PlatformBuffer

    companion object
}
