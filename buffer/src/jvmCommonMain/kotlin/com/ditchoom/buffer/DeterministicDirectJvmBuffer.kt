package com.ditchoom.buffer

import java.nio.ByteBuffer

/**
 * A DirectJvmBuffer with deterministic cleanup via Unsafe.invokeCleaner (JVM 9+).
 *
 * Unlike regular DirectJvmBuffer which relies on GC for cleanup, this buffer
 * implements [CloseableBuffer] and frees native memory immediately when
 * [freeNativeMemory] is called.
 */
abstract class DeterministicDirectJvmBuffer(
    byteBuffer: ByteBuffer,
) : BaseJvmBuffer(byteBuffer),
    NativeMemoryAccess,
    CloseableBuffer {
    init {
        require(byteBuffer.isDirect) { "DeterministicDirectJvmBuffer requires a direct ByteBuffer" }
    }

    override val nativeAddress: Long by lazy { getDirectBufferAddress(byteBuffer) }
    override val nativeSize: Long get() = capacity.toLong()

    private var freed = false
    override val isFreed: Boolean get() = freed

    override fun freeNativeMemory() {
        if (!freed) {
            freed = true
            invokeCleanerFn?.invoke(byteBuffer)
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
}
