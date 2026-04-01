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

    // Guard all entry points to prevent use-after-free (native memory access would crash the JVM)

    override fun readByte(): Byte {
        checkNotFreed()
        return super.readByte()
    }

    override fun readShort(): Short {
        checkNotFreed()
        return super.readShort()
    }

    override fun readInt(): Int {
        checkNotFreed()
        return super.readInt()
    }

    override fun readLong(): Long {
        checkNotFreed()
        return super.readLong()
    }

    override fun readFloat(): Float {
        checkNotFreed()
        return super.readFloat()
    }

    override fun readDouble(): Double {
        checkNotFreed()
        return super.readDouble()
    }

    override fun get(index: Int): Byte {
        checkNotFreed()
        return super.get(index)
    }

    override fun writeByte(byte: Byte): WriteBuffer {
        checkNotFreed()
        return super.writeByte(byte)
    }

    override fun writeShort(value: Short): WriteBuffer {
        checkNotFreed()
        return super.writeShort(value)
    }

    override fun writeInt(value: Int): WriteBuffer {
        checkNotFreed()
        return super.writeInt(value)
    }

    override fun writeLong(value: Long): WriteBuffer {
        checkNotFreed()
        return super.writeLong(value)
    }

    override fun writeFloat(value: Float): WriteBuffer {
        checkNotFreed()
        return super.writeFloat(value)
    }

    override fun writeDouble(value: Double): WriteBuffer {
        checkNotFreed()
        return super.writeDouble(value)
    }

    override fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer {
        checkNotFreed()
        return super.set(index, byte)
    }

    override fun slice(): PlatformBuffer {
        checkNotFreed()
        return sliceImpl()
    }

    protected abstract fun sliceImpl(): PlatformBuffer

    companion object
}
