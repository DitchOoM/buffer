package com.ditchoom.buffer.codec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/**
 * A [WriteBuffer] wrapper that automatically grows when writes exceed capacity.
 *
 * Used by the `@FramedBy` slicing-scheme encode emit so codecs can write a
 * variable-size body without knowing the final size upfront. On each growth
 * event the old buffer is freed via [PlatformBuffer.freeNativeMemory] and a
 * new one is allocated through the same [BufferFactory], so pooled /
 * deterministic factories work correctly and intermediate buffers are never
 * leaked. Bytes `[0, position)` are preserved across reallocations, which
 * keeps the slack region at offset 0 intact across growth — required by the
 * slicing scheme's right-flush of the prefix.
 *
 * Instances are recyclable via [GrowableWriteBufferPool]: [attach] sets up
 * state from a fresh factory + initial size, [detach] clears the inner
 * reference (without freeing — the FramedEncoder transfers ownership to the
 * returned slice before recycling the wrapper). This cuts one heap object per
 * encode call out of the hot wire-write path.
 */
internal class GrowableWriteBuffer : WriteBuffer {
    private var factory: BufferFactory? = null
    private var inner: PlatformBuffer? = null

    private fun requireInner(): PlatformBuffer = inner ?: error("GrowableWriteBuffer used before attach()")

    fun attach(
        factory: BufferFactory,
        initialSize: Int = 256,
        byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
    ) {
        check(inner == null) { "GrowableWriteBuffer already attached; detach() before reuse" }
        this.factory = factory
        this.inner = factory.allocate(initialSize, byteOrder)
    }

    /**
     * Clears the wrapper's state without freeing the inner buffer. Used by
     * [FramedEncoder] after ownership of the inner has been transferred to
     * the returned slice — the slice's refcount / chunk will free the inner.
     */
    fun detach() {
        inner = null
        factory = null
    }

    override val byteOrder: ByteOrder get() = requireInner().byteOrder

    override fun limit(): Int = requireInner().limit()

    override fun setLimit(limit: Int) {
        requireInner().setLimit(limit)
    }

    override fun position(): Int = requireInner().position()

    override fun position(newPosition: Int) {
        val buf = requireInner()
        if (newPosition > buf.limit()) {
            ensureCapacity(newPosition - buf.position())
        }
        requireInner().position(newPosition)
    }

    /** Returns the inner buffer positioned for reading (position=0, limit=bytesWritten). */
    fun toReadBuffer(): ReadBuffer {
        val buf = requireInner()
        buf.resetForRead()
        return buf
    }

    /** Returns the inner buffer for caller-controlled positioning (used by the slicing scheme). */
    fun innerBuffer(): PlatformBuffer = requireInner()

    private fun ensureCapacity(additionalBytes: Int) {
        val buf = requireInner()
        if (buf.remaining() >= additionalBytes) return
        val needed = buf.position() + additionalBytes
        var newCapacity = buf.capacity * 2
        while (newCapacity < needed) newCapacity *= 2
        val old = buf
        old.resetForRead()
        val newBuffer =
            checkNotNull(factory) { "factory cleared before grow" }
                .allocate(newCapacity, buf.byteOrder)
        newBuffer.write(old)
        old.freeNativeMemory()
        inner = newBuffer
    }

    override fun writeByte(byte: Byte): WriteBuffer {
        ensureCapacity(1)
        requireInner().writeByte(byte)
        return this
    }

    override fun writeShort(short: Short): WriteBuffer {
        ensureCapacity(2)
        requireInner().writeShort(short)
        return this
    }

    override fun writeInt(int: Int): WriteBuffer {
        ensureCapacity(Int.SIZE_BYTES)
        requireInner().writeInt(int)
        return this
    }

    override fun writeLong(long: Long): WriteBuffer {
        ensureCapacity(Long.SIZE_BYTES)
        requireInner().writeLong(long)
        return this
    }

    override fun writeFloat(float: Float): WriteBuffer {
        ensureCapacity(Float.SIZE_BYTES)
        requireInner().writeFloat(float)
        return this
    }

    override fun writeDouble(double: Double): WriteBuffer {
        ensureCapacity(Double.SIZE_BYTES)
        requireInner().writeDouble(double)
        return this
    }

    override fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        ensureCapacity(length)
        requireInner().writeBytes(bytes, offset, length)
        return this
    }

    override fun writeString(
        text: CharSequence,
        charset: Charset,
    ): WriteBuffer {
        ensureCapacity((text.length * charset.maxBytesPerChar).toInt())
        requireInner().writeString(text, charset)
        return this
    }

    override fun write(buffer: ReadBuffer) {
        ensureCapacity(buffer.remaining())
        requireInner().write(buffer)
    }

    override fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer {
        requireInner()[index] = byte
        return this
    }

    override fun resetForWrite() {
        requireInner().resetForWrite()
    }
}
