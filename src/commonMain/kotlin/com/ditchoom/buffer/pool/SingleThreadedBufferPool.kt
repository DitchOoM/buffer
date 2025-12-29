package com.ditchoom.buffer.pool

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.allocate

/**
 * Fast buffer pool implementation optimized for single-threaded access.
 *
 * NOT thread-safe. Use when pool is confined to a single coroutine/thread.
 * For multi-threaded access, use [LockFreeBufferPool] instead.
 */
internal class SingleThreadedBufferPool(
    private val maxPoolSize: Int,
    private val defaultBufferSize: Int,
    private val byteOrder: ByteOrder,
) : BufferPool {
    private val pool = ArrayDeque<PlatformBuffer>(maxPoolSize)

    private var totalAllocations = 0L
    private var poolHits = 0L
    private var poolMisses = 0L
    private var peakPoolSize = 0

    override fun acquire(minSize: Int): PooledBuffer {
        totalAllocations++
        val size = maxOf(minSize, defaultBufferSize)

        val buffer = pool.removeLastOrNull()

        return if (buffer != null && buffer.capacity >= size) {
            poolHits++
            buffer.resetForWrite()
            SimplePooledBuffer(buffer, this)
        } else {
            poolMisses++
            val newBuffer = PlatformBuffer.allocate(size, AllocationZone.Direct, byteOrder)
            SimplePooledBuffer(newBuffer, this)
        }
    }

    override fun release(buffer: PooledBuffer) {
        if (buffer !is SimplePooledBuffer) return

        if (pool.size < maxPoolSize) {
            buffer.inner.resetForWrite()
            pool.addLast(buffer.inner)
            if (pool.size > peakPoolSize) {
                peakPoolSize = pool.size
            }
        }
    }

    override fun stats(): PoolStats =
        PoolStats(
            totalAllocations = totalAllocations,
            poolHits = poolHits,
            poolMisses = poolMisses,
            currentPoolSize = pool.size,
            peakPoolSize = peakPoolSize,
        )

    override fun clear() {
        pool.clear()
    }
}

/**
 * Simple pooled buffer wrapper that delegates to the underlying PlatformBuffer.
 */
internal class SimplePooledBuffer(
    val inner: PlatformBuffer,
    private val pool: BufferPool,
) : PooledBuffer {
    override val capacity: Int get() = inner.capacity
    override val byteOrder: ByteOrder get() = inner.byteOrder

    override fun release() = pool.release(this)

    // Delegate ReadBuffer
    override fun resetForRead() = inner.resetForRead()

    override fun readByte(): Byte = inner.readByte()

    override fun get(index: Int): Byte = inner.get(index)

    override fun readByteArray(size: Int): ByteArray = inner.readByteArray(size)

    override fun readShort(): Short = inner.readShort()

    override fun getShort(index: Int): Short = inner.getShort(index)

    override fun readInt(): Int = inner.readInt()

    override fun getInt(index: Int): Int = inner.getInt(index)

    override fun readLong(): Long = inner.readLong()

    override fun getLong(index: Int): Long = inner.getLong(index)

    override fun readFloat(): Float = inner.readFloat()

    override fun getFloat(index: Int): Float = inner.getFloat(index)

    override fun readDouble(): Double = inner.readDouble()

    override fun getDouble(index: Int): Double = inner.getDouble(index)

    override fun readString(
        length: Int,
        charset: Charset,
    ): String = inner.readString(length, charset)

    override fun slice(): ReadBuffer = inner.slice()

    override fun limit(): Int = inner.limit()

    override fun position(): Int = inner.position()

    override fun position(newPosition: Int) = inner.position(newPosition)

    override fun setLimit(limit: Int) = inner.setLimit(limit)

    // Delegate WriteBuffer
    override fun resetForWrite() = inner.resetForWrite()

    override fun writeByte(byte: Byte): WriteBuffer {
        inner.writeByte(byte)
        return this
    }

    override fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer {
        inner.set(index, byte)
        return this
    }

    override fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        inner.writeBytes(bytes, offset, length)
        return this
    }

    override fun writeShort(short: Short): WriteBuffer {
        inner.writeShort(short)
        return this
    }

    override fun set(
        index: Int,
        short: Short,
    ): WriteBuffer {
        inner.set(index, short)
        return this
    }

    override fun writeInt(int: Int): WriteBuffer {
        inner.writeInt(int)
        return this
    }

    override fun set(
        index: Int,
        int: Int,
    ): WriteBuffer {
        inner.set(index, int)
        return this
    }

    override fun writeLong(long: Long): WriteBuffer {
        inner.writeLong(long)
        return this
    }

    override fun set(
        index: Int,
        long: Long,
    ): WriteBuffer {
        inner.set(index, long)
        return this
    }

    override fun writeFloat(float: Float): WriteBuffer {
        inner.writeFloat(float)
        return this
    }

    override fun set(
        index: Int,
        float: Float,
    ): WriteBuffer {
        inner.set(index, float)
        return this
    }

    override fun writeDouble(double: Double): WriteBuffer {
        inner.writeDouble(double)
        return this
    }

    override fun set(
        index: Int,
        double: Double,
    ): WriteBuffer {
        inner.set(index, double)
        return this
    }

    override fun writeString(
        text: CharSequence,
        charset: Charset,
    ): WriteBuffer {
        inner.writeString(text, charset)
        return this
    }

    override fun write(buffer: ReadBuffer) = inner.write(buffer)
}
