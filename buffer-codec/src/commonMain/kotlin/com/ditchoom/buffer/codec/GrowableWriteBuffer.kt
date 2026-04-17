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
 * Used by [encodeToBuffer] so codecs can write without knowing the final size upfront.
 * On each growth event the old buffer is freed via [PlatformBuffer.freeNativeMemory]
 * and a new one is allocated through the same [BufferFactory], so pooled / deterministic
 * factories work correctly and intermediate buffers are never leaked.
 */
internal class GrowableWriteBuffer(
    private val factory: BufferFactory,
    initialSize: Int = 256,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
) : WriteBuffer {
    private var inner: PlatformBuffer = factory.allocate(initialSize, byteOrder)

    override val byteOrder: ByteOrder get() = inner.byteOrder

    override fun limit(): Int = inner.limit()

    override fun setLimit(limit: Int) {
        inner.setLimit(limit)
    }

    override fun position(): Int = inner.position()

    override fun position(newPosition: Int) {
        if (newPosition > inner.limit()) {
            ensureCapacity(newPosition - inner.position())
        }
        inner.position(newPosition)
    }

    /** Returns the inner buffer positioned for reading (position=0, limit=bytesWritten). */
    fun toReadBuffer(): ReadBuffer {
        inner.resetForRead()
        return inner
    }

    // ──────────────────────── Growth ────────────────────────

    private fun ensureCapacity(additionalBytes: Int) {
        if (inner.remaining() >= additionalBytes) return
        val needed = inner.position() + additionalBytes
        var newCapacity = inner.capacity * 2
        while (newCapacity < needed) newCapacity *= 2
        val old = inner
        val pos = old.position()
        old.resetForRead() // position=0, limit=pos
        val newBuffer = factory.allocate(newCapacity, byteOrder)
        newBuffer.write(old) // copies pos bytes; newBuffer.position == pos
        old.freeNativeMemory()
        inner = newBuffer
    }

    // ──────────────────────── Relative writes ────────────────────────

    override fun writeByte(byte: Byte): WriteBuffer {
        ensureCapacity(1)
        inner.writeByte(byte)
        return this
    }

    override fun writeShort(short: Short): WriteBuffer {
        ensureCapacity(2)
        inner.writeShort(short)
        return this
    }

    override fun writeInt(int: Int): WriteBuffer {
        ensureCapacity(4)
        inner.writeInt(int)
        return this
    }

    override fun writeLong(long: Long): WriteBuffer {
        ensureCapacity(8)
        inner.writeLong(long)
        return this
    }

    override fun writeFloat(float: Float): WriteBuffer {
        ensureCapacity(4)
        inner.writeFloat(float)
        return this
    }

    override fun writeDouble(double: Double): WriteBuffer {
        ensureCapacity(8)
        inner.writeDouble(double)
        return this
    }

    override fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        ensureCapacity(length)
        inner.writeBytes(bytes, offset, length)
        return this
    }

    override fun writeString(
        text: CharSequence,
        charset: Charset,
    ): WriteBuffer {
        ensureCapacity((text.length * charset.maxBytesPerChar).toInt())
        inner.writeString(text, charset)
        return this
    }

    override fun write(buffer: ReadBuffer) {
        ensureCapacity(buffer.remaining())
        inner.write(buffer)
    }

    // ──────────────────────── Absolute writes (back-patching) ────────────────────────

    override fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer {
        inner[index] = byte
        return this
    }

    // ──────────────────────── Reset ────────────────────────

    override fun resetForWrite() {
        inner.resetForWrite()
    }
}
